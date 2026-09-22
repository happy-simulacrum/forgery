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
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.forgery.app.core.common.Result as ForgeResult
import com.forgery.app.core.common.overallProgress
import com.forgery.app.core.database.QueueStateDao
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
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_QUEUE = "forgery_generation_queue"
        const val TAG_QUEUE = "forgery_queue"
        const val KEY_HOST = "host"
        const val KEY_JOBS = "jobs"
        const val KEY_ORIGIN = "origin"
        const val CHANNEL_ID = "forgery_queue"
        const val NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        // Job list lives in the DB: it is re-read every iteration so jobs
        // appended mid-run (Generate while running) are picked up, and the
        // watchdog resume path continues from persisted currentIndex.
        // Input extras are a fallback only.
        val fallbackHost = inputData.getString(KEY_HOST).orEmpty()
        val fallbackJobs = decodeJobs(inputData.getString(KEY_JOBS).orEmpty())

        val wakeLock = acquireWakeLock()
        val wifiLock = acquireWifiLock()
        try {
            setForeground(createForegroundInfo("Batch Running", "Starting queue…", 1))
            var finished = 0
            while (true) {
                val state = queueDao.get()
                val jobs = state?.let { decodeJobs(it.jobsJson) }?.takeIf { it.isNotEmpty() }
                    ?: fallbackJobs
                val host = state?.host?.takeIf { it.isNotBlank() } ?: fallbackHost
                val index = state?.currentIndex ?: 0
                if (state?.running != true || host.isBlank() || jobs.isEmpty() ||
                    index >= jobs.size || index >= (state?.total ?: jobs.size)
                ) {
                    break
                }
                runJob(host, jobs[index], index, jobs.size)
                finished = index + 1
            }
            queueDao.get()?.let { queueDao.save(it.copy(running = false)) }
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
        host: String,
        job: QueueJob,
        index: Int,
        total: Int,
    ): QueueResult {
        queueDao.get()?.let { queueDao.save(it.copy(jobProgress = 0f)) }
        updateProgress("Batch Running", "Job ${index + 1}/$total: preparing…", overallProgress(index, total, 0f))
        return try {
            val isInpaint = job.mode == "inp"
            when (val aligned = generationRepository.ensureModel(job.modelTitle, isInpaint)) {
                is ForgeResult.Error ->
                    return abort(job, index, total, aligned.message)
                else -> Unit
            }

            val payload = jsonStringToPayload(job.payloadJson)
            // Progress poller, best-effort (legacy: 3s interval thread).
            val poller = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                while (isActive) {
                    val p = (generationRepository.progress()
                        as? ForgeResult.Success)?.data ?: 0.0
                    if (p > 0) {
                        updateProgress(
                            "Batch Running",
                            "Job ${index + 1}/$total: ${(p * 100).toInt()}%",
                            overallProgress(index, total, p.toFloat()),
                        )
                        val frac = p.toFloat().coerceIn(0f, 1f)
                        val cur = queueDao.get()
                        if (cur != null && kotlin.math.abs(cur.jobProgress - frac) > 0.01f) {
                            queueDao.save(cur.copy(jobProgress = frac))
                        }
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
        val state = queueDao.get()
        if (state != null) {
            val prev = if (state.resultsJson.isBlank() || state.resultsJson == "[]") {
                emptyList()
            } else {
                WorkerJson.decodeFromString(ListSerializer(QueueResult.serializer()), state.resultsJson)
            }
            val next = prev + result
            queueDao.save(
                state.copy(
                    currentIndex = index + 1,
                    running = if (error != null) false else state.running,
                    jobProgress = 0f,
                    resultsJson = WorkerJson.encodeToString(
                        ListSerializer(QueueResult.serializer()), next,
                    ),
                ),
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
        queueDao.get()?.let { queueDao.save(it.copy(running = running)) }
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

    private fun decodeJobs(raw: String): List<QueueJob> =
        if (raw.isBlank()) emptyList()
        else WorkerJson.decodeFromString(ListSerializer(QueueJob.serializer()), raw)

    private class JobFailedException(message: String) : Exception(message)
}
