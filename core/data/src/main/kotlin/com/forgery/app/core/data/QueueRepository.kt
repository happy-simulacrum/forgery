package com.forgery.app.core.data

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.forgery.app.core.database.QueueJobsDao
import com.forgery.app.core.database.QueueJobEntity
import com.forgery.app.core.database.QueueResultsDao
import com.forgery.app.core.database.QueueResultEntity
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueStateEntity
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.common.Result
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

internal val QueueJson = Json { ignoreUnknownKeys = true }

internal const val QueueLogTag = "ForgeryQueue"

private fun encodeStrings(values: List<String>): String =
    QueueJson.encodeToString(ListSerializer(String.serializer()), values)

private fun decodeStrings(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching {
        QueueJson.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrElse { emptyList() }

private fun QueueJob.toEntity(order: Int) = QueueJobEntity(
    jobId = id,
    sortOrder = order,
    descr = desc,
    mode = mode,
    modelTitle = modelTitle,
    payload = payloadJson,
    modulesJson = encodeStrings(additionalModules),
    initImagePath = initImagePath,
    maskPath = maskPath,
)

private fun QueueJobEntity.toJob() = QueueJob(
    id = jobId,
    desc = descr,
    mode = mode,
    modelTitle = modelTitle,
    payloadJson = payload,
    additionalModules = decodeStrings(modulesJson),
    initImagePath = initImagePath,
    maskPath = maskPath,
)

private fun QueueResult.toEntity() = QueueResultEntity(
    jobId = jobId,
    descr = desc,
    filesJson = encodeStrings(files),
    error = error,
)

private fun QueueResultEntity.toResult() = QueueResult(
    jobId = jobId,
    desc = descr,
    files = decodeStrings(filesJson),
    error = error,
)

interface QueueRepository {
    fun observeSnapshot(): Flow<QueueSnapshot?>
    fun observeJobs(): Flow<List<QueueJob>>
    fun observeResults(): Flow<List<QueueResult>>

    /**
     * Persist jobs without starting execution (QUEUE button). Always appends,
     * never replaces: repeated presses accumulate items in TO DO.
     * Idle: appends to staged jobs, keeps results (a finished
     * batch keeps its DONE section, new jobs land behind it).
     * Running: appends to the tail so the executing
     * queue is never reset (running/executingId/results/host/origin kept);
     * the launched batch grows ([QueueSnapshot.batchTotal] += appended).
     */
    suspend fun stage(jobs: List<QueueJob>, origin: String)

    /**
     * Start execution of staged jobs (QUE "Start Queue"). The execution
     * pointer is re-derived as the first result-less job, and the batch
     * counter is frozen as `batchTotal = pending count, batchDone = 0`.
     * No-op when empty/no pending.
     */
    suspend fun start()

    /**
     * GENERATE button: append behind existing jobs and start everything when
     * idle (never replaces the staged queue); when a batch is running,
     * insert right after the currently executing job. The running branch
     * always re-enqueues the worker with KEEP: a no-op when the worker is
     * alive, a relaunch when it died leaving a stale `running=true`
     * (second GENERATE used to stall with "Added behind current job" until
     * an app restart).
     */
    suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String)

    suspend fun cancel()

    /**
     * Remove a single job (from TO DO or DONE) plus its [QueueResult] if any.
     * Returns false and changes nothing when the job is currently executing
     * ([QueueSnapshot.executingJobId] while running) or does not exist.
     * Removing an upcoming job while running also shrinks the launched batch
     * ([QueueSnapshot.batchTotal]).
     */
    suspend fun removeJob(jobId: String): Boolean

    /**
     * Move a pending (TO DO, result-less) job to another pending position
     * ([toPendingIndex] counts result-less jobs in list order). DONE jobs are
     * not movable. While running, the currently executing job is pinned: it
     * cannot be moved and nothing may be moved onto/before its slot.
     * Returns false and changes nothing on any rule violation.
     */
    suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean

    /**
     * Clear completed section (DONE): removes jobs that have a [QueueResult]
     * (success or failure) plus their results. History table is untouched —
     * images stay in Gallery/HISTORY.
     * While running, the execution pointer tracks the executing job by id so
     * the active card does not slide down and the rest of the queue keeps
     * executing. The launched batch counters are untouched (DONE is outside
     * the batch).
     */
    suspend fun clearCompleted()

    /**
     * Clear pending section (TO DO):
     * - idle: removes all jobs without a result, keeps results/history;
     * - running: removes all jobs after the currently executing one,
     *   keeps past + current. Worker is not
     *   cancelled; it finishes the current job and stops (it re-reads
     *   the next pending job from DB every iteration). The launched batch shrinks by
     *   the removed upcoming count (never below already-completed).
     */
    suspend fun clearPending()
}

private data class WorkerLaunch(
    val host: String,
    val origin: String,
    val policy: ExistingWorkPolicy,
)

/**
 * Cancel()-safe backend stub for the compat constructor above: interrupt()
 * inherits the [GenerationRepository] default ([Result.Error], never throws),
 * so cancel() falls through to the local reset. Anything else is unused.
 */
private object NoopGenerationRepository : GenerationRepository {
    override suspend fun fetchSdModels(): Result<List<String>> = TODO("noop backend")
    override suspend fun fetchSamplers(): Result<List<String>> = TODO("noop backend")
    override suspend fun fetchUpscalers(): Result<List<String>> = TODO("noop backend")
    override suspend fun fetchSchedulers(): Result<List<String>> = TODO("noop backend")
    override suspend fun fetchModules(): Result<List<String>> = TODO("noop backend")
    override suspend fun fetchLoras(): Result<List<com.forgery.app.core.model.LoraItem>> =
        TODO("noop backend")
    override suspend fun fetchLoraSidecar(basePath: String): Result<com.forgery.app.core.model.LoraMeta> =
        TODO("noop backend")
    override suspend fun fetchPromptStyles(): Result<List<com.forgery.app.core.model.StylePreset>> =
        TODO("noop backend")
    override suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit> =
        TODO("noop backend")
    override suspend fun ensureAdditionalModules(modules: List<String>): Result<Unit> =
        TODO("noop backend")
    override suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>> =
        TODO("noop backend")
    override suspend fun img2img(payload: Map<String, Any?>): Result<List<String>> =
        TODO("noop backend")
    override suspend fun progress(): Result<Double> = TODO("noop backend")
    override suspend fun unloadModel(): Result<Unit> = TODO("noop backend")
}

@Singleton
open class DefaultQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: QueueStateDao,
    private val jobsDao: QueueJobsDao,
    private val resultsDao: QueueResultsDao,
    private val connectionRepository: ConnectionRepository,
    private val tx: QueueTx,
    private val inputFiles: QueueInputs,
    private val generationRepository: GenerationRepository,
) : QueueRepository {

    /**
     * Backward-compat entry point for call sites without a generation
     * backend (e.g. decode-only unit tests): cancel() degrades to a
     * local-only reset, the server interrupt is a no-op [Result.Error].
     */
    constructor(
        context: Context,
        dao: QueueStateDao,
        jobsDao: QueueJobsDao,
        resultsDao: QueueResultsDao,
        connectionRepository: ConnectionRepository,
        tx: QueueTx,
        inputFiles: QueueInputs,
    ) : this(
        context,
        dao,
        jobsDao,
        resultsDao,
        connectionRepository,
        tx,
        inputFiles,
        NoopGenerationRepository,
    )

    override fun observeSnapshot(): Flow<QueueSnapshot?> =
        combine(dao.observe(), jobsDao.observeOrdered(), resultsDao.observeAll()) { state, jobs, results ->
            state?.let {
                val doneIds = results.map { r -> r.jobId }.toSet()
                val validExecuting = it.executingJobId?.takeIf { id ->
                    jobs.any { j -> j.jobId == id } && id !in doneIds
                }
                QueueSnapshot(
                    running = it.running,
                    executingJobId = if (it.running) validExecuting else null,
                    total = jobs.size,
                    origin = it.origin,
                    stopReason = null,
                    jobProgress = it.jobProgress,
                    batchTotal = it.batchTotal,
                    batchDone = it.batchDone,
                )
            }
        }

    override fun observeJobs(): Flow<List<QueueJob>> =
        jobsDao.observeOrdered().map { list -> list.map { it.toJob() } }

    override fun observeResults(): Flow<List<QueueResult>> =
        resultsDao.observeAll().map { list -> list.map { it.toResult() } }

    override suspend fun stage(jobs: List<QueueJob>, origin: String) {
        require(jobs.isNotEmpty())
        tx.run {
            val state = dao.get()
            val host = state?.host?.takeIf { it.isNotBlank() }
                ?: connectionRepository.observe().first().webUiBaseUrl()
            if (state == null) {
                dao.save(
                    QueueStateEntity(
                        running = false,
                        executingJobId = null,
                        origin = origin,
                        host = host,
                        jobProgress = 0f,
                    ),
                )
                jobsDao.upsertAll(jobs.mapIndexed { i, job -> job.toEntity(i) })
                return@run
            }
            val base = jobsDao.maxOrder()
            jobsDao.upsertAll(jobs.mapIndexed { i, job -> job.toEntity(base + 1 + i) })
            if (state.running) {
                dao.save(state.copy(batchTotal = state.batchTotal + jobs.size))
            } else {
                dao.save(state.copy(origin = origin, host = host, jobProgress = 0f))
            }
        }
    }

    override suspend fun start() {
        val launch: WorkerLaunch? = tx.run {
            val state = dao.get() ?: return@run null
            val ordered = jobsDao.getOrdered()
            if (ordered.isEmpty()) return@run null
            val doneIds = resultsDao.doneIds().toSet()
            inputFiles.sweepOrphans(ordered.map { it.jobId }.toSet())
            if (state.running) {
                val repaired = repairExecuting(state, ordered, doneIds)
                if (repaired != state.executingJobId) dao.save(state.copy(executingJobId = repaired))
                val fresh = state.copy(executingJobId = repaired)
                return@run WorkerLaunch(fresh.host, fresh.origin, ExistingWorkPolicy.KEEP)
            }
            val firstPending = ordered.firstOrNull { it.jobId !in doneIds }
                ?: return@run null
            val pendingCount = ordered.count { it.jobId !in doneIds }
            Log.d(
                QueueLogTag,
                "start: firstPending=${firstPending.jobId} pending=$pendingCount jobs=${ordered.map { it.jobId }}",
            )
            dao.save(
                state.copy(
                    running = true,
                    executingJobId = firstPending.jobId,
                    jobProgress = 0f,
                    batchTotal = pendingCount,
                    batchDone = 0,
                ),
            )
            WorkerLaunch(state.host, state.origin, ExistingWorkPolicy.REPLACE)
        }
        if (launch != null) {
            launchWorker(launch.host, launch.origin, launch.policy)
        }
    }

    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) {
        require(jobs.isNotEmpty())
        val launch: WorkerLaunch? = tx.run {
            val state = dao.get()
            val ordered = jobsDao.getOrdered()
            val host = state?.host?.takeIf { it.isNotBlank() }
                ?: connectionRepository.observe().first().webUiBaseUrl()
            if (state != null && state.running && ordered.isNotEmpty()) {
                // Insert right after the currently executing job; the worker
                // picks the next pending row from the DB every iteration. Always
                // re-enqueue with KEEP: no-op when the worker is alive, relaunch
                // when it died leaving a stale running flag.
                val doneIds = resultsDao.doneIds().toSet()
                val repaired = repairExecuting(state, ordered, doneIds)
                val base = if (repaired != state.executingJobId) {
                    dao.save(state.copy(executingJobId = repaired))
                    state.copy(executingJobId = repaired)
                } else {
                    state
                }
                val execIdx = ordered.indexOfFirst { it.jobId == base.executingJobId }
                val at = if (execIdx < 0) ordered.size else execIdx + 1
                val merged = ordered.toMutableList()
                jobs.forEachIndexed { i, job ->
                    merged.add((at + i).coerceIn(0, merged.size), job.toEntity(0))
                }
                merged.forEachIndexed { i, e -> merged[i] = e.copy(sortOrder = i) }
                jobsDao.upsertAll(merged)
                dao.save(base.copy(batchTotal = base.batchTotal + jobs.size))
                return@run WorkerLaunch(base.host, base.origin, ExistingWorkPolicy.KEEP)
            } else if (state != null && ordered.isNotEmpty()) {
                // Idle with an existing queue: append behind existing jobs and
                // start. Batch scope is derived from results.
                val mergedResults = doneIdsOf()
                val doneIds = mergedResults.toSet()
                val base = jobsDao.maxOrder()
                jobsDao.upsertAll(jobs.mapIndexed { i, job -> job.toEntity(base + 1 + i) })
                val all = jobsDao.getOrdered()
                val firstPending = all.firstOrNull { it.jobId !in doneIds }
                val pendingCount = all.count { it.jobId !in doneIds }
                Log.d(
                    QueueLogTag,
                    "enqueueImmediate idle: firstPending=${firstPending?.jobId} pending=$pendingCount",
                )
                dao.save(
                    state.copy(
                        running = true,
                        executingJobId = firstPending?.jobId,
                        origin = origin,
                        host = host,
                        jobProgress = 0f,
                        batchTotal = pendingCount,
                        batchDone = 0,
                    ),
                )
                if (firstPending == null) return@run null
                return@run WorkerLaunch(host, origin, ExistingWorkPolicy.REPLACE)
            } else {
                val target = state ?: QueueStateEntity(
                    running = false,
                    executingJobId = null,
                    origin = origin,
                    host = host,
                )
                jobsDao.upsertAll(jobs.mapIndexed { i, job -> job.toEntity(i) })
                dao.save(
                    target.copy(
                        running = true,
                        executingJobId = jobs.first().id,
                        origin = origin,
                        host = host,
                        jobProgress = 0f,
                        batchTotal = jobs.size,
                        batchDone = 0,
                    ),
                )
                return@run WorkerLaunch(host, origin, ExistingWorkPolicy.REPLACE)
            }
        }
        if (launch != null) {
            launchWorker(launch.host, launch.origin, launch.policy)
        }
    }

    override suspend fun removeJob(jobId: String): Boolean {
        val removed = tx.run {
            val state = dao.get() ?: return@run false
            val entity = jobsDao.getById(jobId) ?: return@run false
            if (state.running && state.executingJobId == jobId) return@run false
            val doneIds = resultsDao.doneIds().toSet()
            val isDone = jobId in doneIds
            jobsDao.deleteById(jobId)
            resultsDao.deleteById(jobId)
            if (!isDone && state.running) {
                val execOrder = jobsDao.getOrdered()
                    .indexOfFirst { it.jobId == state.executingJobId }
                val removedOrder = entity.sortOrder
                if (removedOrder > execOrder) {
                    dao.save(
                        state.copy(
                            batchTotal = maxOf(state.batchDone, state.batchTotal - 1),
                        ),
                    )
                }
            }
            true
        }
        if (removed) inputFiles.deleteJob(jobId)
        return removed
    }

    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean = tx.run {
        val state = dao.get() ?: return@run false
        val ordered = jobsDao.getOrdered()
        if (ordered.isEmpty()) return@run false
        val doneIds = resultsDao.doneIds().toSet()
        val pending = ordered.filter { it.jobId !in doneIds }
        if (pending.isEmpty()) return@run false
        val fromPos = pending.indexOfFirst { it.jobId == jobId }
        if (fromPos < 0) return@run false
        // Movable window: idle allows any pending slot; running pins the
        // executing slot — only later pending slots may be permuted.
        val firstMovable = if (state.running) {
            val execPos = pending.indexOfFirst { it.jobId == state.executingJobId }
            if (execPos < 0) return@run false
            execPos + 1
        } else {
            0
        }
        if (fromPos < firstMovable) return@run false
        if (toPendingIndex < firstMovable || toPendingIndex >= pending.size) return@run false
        if (fromPos == toPendingIndex) return@run true
        val window = pending.toMutableList()
        val moving = window.removeAt(fromPos)
        window.add(toPendingIndex, moving)
        // Rebuild full order: done rows stay fixed, reordered pending goes
        // into pending slots in order; then dense sortOrder rewrite.
        val result = ordered.toMutableList()
        val slots = ordered.mapIndexedNotNull { i, e -> i.takeIf { e.jobId !in doneIds } }
        slots.forEachIndexed { k, slot -> result[slot] = window[k] }
        result.forEachIndexed { i, e -> result[i] = e.copy(sortOrder = i) }
        jobsDao.upsertAll(result)
        true
    }

    override suspend fun cancel() {
        // H-2 real cancel: stop the server-side task first (the in-flight
        // txt2img would otherwise keep running after the local flag reset),
        // then clear local state + worker exactly as before.
        if (dao.get()?.running == true) {
            try {
                withTimeout(10_000) {
                    generationRepository.interrupt()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(QueueLogTag, "cancel: interrupt timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(QueueLogTag, "cancel: interrupt failed", e)
            }
        }
        tx.run {
            dao.get()?.let { dao.save(it.copy(running = false, executingJobId = null)) }
        }
        cancelWork()
    }

    override suspend fun clearCompleted() {
        val removedIds = tx.run {
            val state = dao.get() ?: return@run emptyList()
            val doneIds = resultsDao.doneIds().toSet()
            if (doneIds.isEmpty()) return@run emptyList()
            val jobs = jobsDao.getOrdered()
            val toDelete = jobs.filter { it.jobId in doneIds }.map { it.jobId }
            if (toDelete.isNotEmpty()) {
                jobsDao.deleteByIds(toDelete)
                resultsDao.deleteByIds(toDelete)
            }
            toDelete
        }
        removedIds.forEach { inputFiles.deleteJob(it) }
    }

    override suspend fun clearPending() {
        val removedIds = tx.run {
            val state = dao.get() ?: return@run emptyList()
            val ordered = jobsDao.getOrdered()
            val doneIds = resultsDao.doneIds().toSet()
            if (!state.running) {
                val toDelete = ordered.filter { it.jobId !in doneIds }.map { it.jobId }
                if (toDelete.isNotEmpty()) {
                    jobsDao.deleteByIds(toDelete)
                }
                toDelete
            } else {
                val execIdx = ordered.indexOfFirst { it.jobId == state.executingJobId }
                val keep = if (execIdx < 0) ordered else ordered.take(execIdx + 1)
                val removed = if (execIdx < 0) ordered else ordered.drop(keep.size)
                val removedUpcoming = removed.count { it.jobId !in doneIds }
                // Drop the whole tail (including done rows of future jobs —
                // their results must not dangle without jobs).
                val toDelete = removed.map { it.jobId }
                if (toDelete.isNotEmpty()) {
                    jobsDao.deleteByIds(toDelete)
                    resultsDao.deleteByIds(toDelete)
                }
                val newBatchTotal = maxOf(state.batchDone, state.batchTotal - removedUpcoming)
                Log.d(
                    QueueLogTag,
                    "clearPending running: keep=${keep.size} removedUpcoming=$removedUpcoming batch ${state.batchDone}/${state.batchTotal} -> $newBatchTotal",
                )
                dao.save(state.copy(batchTotal = newBatchTotal))
                // Delete input files only for removed pending (keep executing + done).
                toDelete
            }
        }
        removedIds.forEach { inputFiles.deleteJob(it) }
    }

    private suspend fun doneIdsOf(): List<String> = resultsDao.doneIds()

    /** Re-derives executing id: null when idle/empty/done; first pending otherwise. */
    private fun repairExecuting(
        state: QueueStateEntity,
        ordered: List<QueueJobEntity>,
        doneIds: Set<String>,
    ): String? {
        if (!state.running) return null
        val cur = state.executingJobId
        if (cur != null && ordered.any { it.jobId == cur } && cur !in doneIds) return cur
        val firstPending = ordered.firstOrNull { it.jobId !in doneIds }
        Log.d(QueueLogTag, "repair executing $cur -> ${firstPending?.jobId} (jobs=${ordered.map { it.jobId }})")
        return firstPending?.jobId
    }

    protected open fun launchWorker(
        host: String,
        origin: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        // C-1: inputData carries only host/origin (no jobsJson — WorkManager 10KB cap).
        // Job list lives in Room; worker re-reads the next pending row every iteration.
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(
                workDataOf(
                    GenerationWorker.KEY_HOST to host,
                    GenerationWorker.KEY_ORIGIN to origin,
                ),
            )
            .addTag(GenerationWorker.TAG_QUEUE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GenerationWorker.UNIQUE_QUEUE, policy, request)
        scheduleWatchdog()
    }

    /** Test seam (like [launchWorker]): unit tests record instead of touching WorkManager. */
    protected open fun cancelWork() {
        WorkManager.getInstance(context).cancelUniqueWork(GenerationWorker.UNIQUE_QUEUE)
    }

    private fun scheduleWatchdog() {
        val request = PeriodicWorkRequestBuilder<QueueWatchdogWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES,
        ).addTag(QueueWatchdogWorker.TAG_WATCHDOG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            QueueWatchdogWorker.UNIQUE_WATCHDOG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
