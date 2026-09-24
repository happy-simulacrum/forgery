package com.forgery.app.core.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.forgery.app.core.common.Result as ForgeResult
import com.forgery.app.core.common.overallProgress
import com.forgery.app.core.database.QueueJobsDao
import com.forgery.app.core.database.QueueResultsDao
import com.forgery.app.core.database.QueueResultEntity
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WorkerJson = Json { ignoreUnknownKeys = true }

/**
 * Native queue executor. Ports `NativeQueueExecutor.java`: runs outside the
 * UI process lifecycle via WorkManager expedited work + foreground
 * notification, persists progress after every job, aborts the batch on the
 * first error (legacy JS behavior).
 */
@HiltWorker
class GenerationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val generationRepository: GenerationRepository,
    private val historyRepository: HistoryRepository,
    private val queueDao: QueueStateDao,
    private val jobsDao: QueueJobsDao,
    private val resultsDao: QueueResultsDao,
    private val inputFiles: QueueInputs,
    private val tx: QueueTx,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_QUEUE = "forgery_generation_queue"
        const val TAG_QUEUE = "forgery_queue"
        const val KEY_HOST = "host"
        const val KEY_ORIGIN = "origin"
        const val CHANNEL_ID = "forgery_queue"
        const val NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        // C-1: job list lives in Room per-row tables (no jobsJson in inputData —
        // WorkManager 10KB cap). Re-read the next pending row every iteration so
        // jobs appended mid-run are picked up and the watchdog resumes from
        // persisted executingJobId.
        val fallbackHost = inputData.getString(KEY_HOST).orEmpty()

        val wakeLock = acquireWakeLock()
        val wifiLock = acquireWifiLock()
        try {
            setForeground(createForegroundInfo("Batch Running", "Starting queue…", 1))
            var finished = 0
            while (true) {
                val next = tx.run {
                    val state = queueDao.get()
                    if (state?.running != true) return@run null
                    val done = resultsDao.doneIds().toSet()
                    val ordered = jobsDao.getOrdered()
                    var exec = state.executingJobId
                    if (exec == null || ordered.none { it.jobId == exec } || exec in done) {
                        exec = ordered.firstOrNull { it.jobId !in done }?.jobId
                        if (exec != state.executingJobId) {
                            queueDao.save(state.copy(executingJobId = exec))
                        }
                    }
                    val id = exec ?: return@run null
                    val entity = jobsDao.getById(id) ?: return@run null
                    val host = state.host.takeIf { it.isNotBlank() } ?: fallbackHost
                    if (host.isBlank()) return@run null
                    val modules = runCatching {
                        WorkerJson.decodeFromString(
                            ListSerializer(String.serializer()),
                            entity.modulesJson,
                        )
                    }.getOrElse { emptyList() }
                    val job = QueueJob(
                        id = entity.jobId,
                        desc = entity.descr,
                        mode = entity.mode,
                        modelTitle = entity.modelTitle,
                        payloadJson = entity.payload,
                        additionalModules = modules,
                        initImagePath = entity.initImagePath,
                        maskPath = entity.maskPath,
                    )
                    val index = ordered.indexOfFirst { it.jobId == id }
                    PendingJob(job, state, host, index, ordered.size)
                } ?: break
                runJob(next.snap, next.host, next.job, next.index, next.total)
                finished++
            }
            tx.run { queueDao.get()?.let { queueDao.save(it.copy(running = false, executingJobId = null)) } }
            setForeground(createForegroundInfo("Batch Complete", "$finished job(s) finished.", 100))
            WorkResult.success()
        } catch (e: CancellationException) {
            persistRunningFlag(false)
            throw e
        } catch (e: Exception) {
            persistRunningFlag(false)
            WorkResult.success()
        } finally {
            releaseQuietly(wakeLock, wifiLock)
        }
    }

    private suspend fun runJob(
        snap: com.forgery.app.core.database.QueueStateEntity?,
        host: String,
        job: QueueJob,
        index: Int,
        total: Int,
    ): QueueResult {
        // Batch-scoped labels: the launched batch (TODO at start, no DONE
        // prefix). Legacy rows mid-flight during upgrade carry zeroes — fall
        // back to positional labels then.
        val useBatch = snap != null && snap.batchTotal > 0
        val labelIndex = if (useBatch) snap.batchDone + 1 else index + 1
        val labelTotal = if (useBatch) snap.batchTotal else total
        fun batchPct(p: Float) = overallProgress(
            if (useBatch) snap!!.batchDone else index,
            labelTotal,
            p,
        )
        queueDao.updateJobProgress(0f)
        updateProgress("Batch Running", "Job $labelIndex/$labelTotal: preparing…", batchPct(0f))
        return try {
            val isInpaint = job.mode == "inp"
            when (val aligned = generationRepository.ensureModel(job.modelTitle, isInpaint)) {
                is ForgeResult.Error ->
                    return abort(job, index, total, aligned.message)
                else -> Unit
            }
            // Neo VAE / Text Encoder pre-flight: aligns the server-global to the
            // job selection, clearing it when the job carries no modules.
            when (val aligned = generationRepository.ensureAdditionalModules(job.additionalModules)) {
                is ForgeResult.Error ->
                    return abort(job, index, total, aligned.message)
                else -> Unit
            }

            val payload = jsonStringToPayload(job.payloadJson).toMutableMap()
            // C-1 file-back: expand input file paths to base64 right before POST.
            job.initImagePath?.let { path ->
                val b64 = inputFiles.loadBase64OrNull(path)
                    ?: return abort(job, index, total, "Missing input image file.")
                payload["init_images"] = listOf(b64)
            }
            job.maskPath?.let { path ->
                inputFiles.loadBase64OrNull(path)?.let { payload["mask"] = it }
            }
            // Progress poller, best-effort (legacy: 3s interval thread).
            // Single-column atomic update: never reads the row, so it cannot
            // resurrect jobs dropped by a concurrent clear.
            val poller = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                while (isActive) {
                    val p = (generationRepository.progress()
                        as? ForgeResult.Success)?.data ?: 0.0
                    if (p > 0) {
                        updateProgress(
                            "Batch Running",
                            "Job $labelIndex/$labelTotal: ${(p * 100).toInt()}%",
                            batchPct(p.toFloat()),
                        )
                        queueDao.updateJobProgress(p.toFloat().coerceIn(0f, 1f))
                    }
                    kotlinx.coroutines.delay(3_000)
                }
            }
            val images: List<String>
            try {
                val endpoint = if (isInpaint) {
                    generationRepository.img2img(payload)
                } else {
                    generationRepository.txt2img(payload)
                }
                images = when (endpoint) {
                    is ForgeResult.Success -> endpoint.data
                    is ForgeResult.Error -> {
                        poller.cancel()
                        return abort(job, index, total, endpoint.message)
                    }
                    is ForgeResult.Loading -> emptyList()
                }
            } finally {
                poller.cancel()
            }

            val files = images.mapIndexed { k, b64 -> saveImage(b64, index, k) }
            files.forEach { path ->
                historyRepository.add(
                    imagePath = path,
                    thumbPath = makeThumbnail(path),
                    paramsJson = job.payloadJson,
                    date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                )
            }
            record(job, index, total, files, null)
        } catch (e: JobFailedException) {
            throw e
        } catch (e: Exception) {
            abort(job, index, total, e.message ?: e.toString())
        }
    }

    private suspend fun record(
        job: QueueJob,
        index: Int,
        total: Int,
        files: List<String>,
        error: String?,
    ): QueueResult {
        val result = QueueResult(job.id, job.desc, files, error)
        tx.run {
            val state = queueDao.get() ?: return@run
            // Id-based merge against freshly-read state: the loop-top row may
            // be minutes stale after the network call; concurrent clear/remove
            // must never be overwritten. Duplicate results are ignored.
            if (resultsDao.getById(job.id) != null) {
                Log.d(QueueLogTag, "record ${job.id} skipped (duplicate)")
                return@run
            }
            val entity = jobsDao.getById(job.id)
            if (entity == null) {
                // Job vanished mid-flight (concurrent clear): repair pointer.
                val done = resultsDao.doneIds().toSet()
                val firstPending = jobsDao.getOrdered().firstOrNull { it.jobId !in done }?.jobId
                if (state.executingJobId == job.id) {
                    queueDao.save(state.copy(executingJobId = firstPending))
                }
                Log.d(QueueLogTag, "record ${job.id} skipped (vanished)")
                return@run
            }
            val filesJson = runCatching {
                WorkerJson.encodeToString(ListSerializer(String.serializer()), files)
            }.getOrElse { "[]" }
            resultsDao.insertIgnore(
                QueueResultEntity(jobId = job.id, descr = job.desc, filesJson = filesJson, error = error),
            )
            val done = resultsDao.doneIds().toSet()
            val next = jobsDao.getOrdered().firstOrNull { it.jobId !in done }?.jobId
            queueDao.save(
                state.copy(
                    executingJobId = next,
                    running = if (error != null) false else state.running,
                    jobProgress = 0f,
                    batchDone = state.batchDone + 1,
                ),
            )
            Log.d(
                QueueLogTag,
                "record ${job.id} batch=${state.batchDone + 1}/${state.batchTotal}",
            )
        }
        return result
    }

    /** Persists the failure, updates the notification and aborts the batch. */
    private suspend fun abort(
        job: QueueJob,
        index: Int,
        total: Int,
        error: String,
    ): QueueResult {
        val result = record(job, index, total, emptyList(), error)
        updateProgress("Batch Paused", "Job ${index + 1}/$total failed: $error", 100)
        throw JobFailedException(error)
    }

    private suspend fun persistRunningFlag(running: Boolean) {
        tx.run {
            queueDao.get()?.let {
                queueDao.save(it.copy(running = running, executingJobId = if (running) it.executingJobId else null))
            }
        }
    }

    // -- files --

    private fun queueDir(): File =
        File(applicationContext.filesDir, "native_queue").also { it.mkdirs() }

    private fun saveImage(base64: String, jobIndex: Int, imageIndex: Int): String {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val out = File(queueDir(), "${System.currentTimeMillis()}_job${jobIndex + 1}_$imageIndex.png")
        FileOutputStream(out).use { it.write(bytes) }
        return out.absolutePath
    }

    private fun makeThumbnail(imagePath: String): String? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(imagePath, decode) ?: return null
        val scale = 256f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val out = File(queueDir(), "thumb_${File(imagePath).nameWithoutExtension}.jpg")
        FileOutputStream(out).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (thumb != bmp) bmp.recycle()
        thumb.recycle()
        out.absolutePath
    } catch (_: Exception) {
        null
    }

    // -- foreground --

    private suspend fun updateProgress(title: String, body: String, progress: Int) {
        setForeground(createForegroundInfo(title, body, progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("Batch Running", "Starting queue…", 1)

    private fun createForegroundInfo(title: String, body: String, progress: Int): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Forgery generation", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    // -- locks (legacy: PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF) --

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Forgery:QueueWakeLock").also {
            it.setReferenceCounted(false)
            it.acquire(30 * 60 * 1_000L)
        }
    } catch (_: Exception) {
        null
    }

    private fun acquireWifiLock(): WifiManager.WifiLock? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Forgery:QueueWifiLock").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    } catch (_: Exception) {
        null
    }

    private fun releaseQuietly(wake: PowerManager.WakeLock?, wifi: WifiManager.WifiLock?) {
        try {
            if (wake?.isHeld == true) wake.release()
        } catch (_: Exception) { }
        try {
            if (wifi?.isHeld == true) wifi.release()
        } catch (_: Exception) { }
    }

    private class JobFailedException(message: String) : Exception(message)

    private data class PendingJob(
        val job: QueueJob,
        val snap: com.forgery.app.core.database.QueueStateEntity,
        val host: String,
        val index: Int,
        val total: Int,
    )
}
