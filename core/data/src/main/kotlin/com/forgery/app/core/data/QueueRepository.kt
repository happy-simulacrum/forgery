package com.forgery.app.core.data

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueStateEntity
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val QueueJson = Json { ignoreUnknownKeys = true }

interface QueueRepository {
    fun observeSnapshot(): Flow<QueueSnapshot?>
    fun observeJobs(): Flow<List<QueueJob>>
    fun observeResults(): Flow<List<QueueResult>>

    /**
     * Persist jobs without starting execution (QUEUE button). Always appends,
     * never replaces: repeated presses accumulate items in TO DO.
     * Idle: appends to staged jobs, keeps results/currentIndex (a finished
     * batch keeps its DONE section, new jobs land behind it).
     * Running with a non-empty queue: appends to the tail so the executing
     * queue is never reset (running/currentIndex/resultsJson/host/origin kept).
     */
    suspend fun stage(jobs: List<QueueJob>, origin: String)

    /** Start execution of staged jobs (QUE "Start Queue"). No-op when empty/running/no pending. */
    suspend fun start()

    /**
     * GENERATE button: append behind existing jobs and start everything when
     * idle (never replaces the staged queue); when a batch is running,
     * insert right after the currently executing job.
     */
    suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String)

    suspend fun cancel()

    /**
     * Remove a single job (from TO DO or DONE) plus its [QueueResult] if any.
     * Returns false and changes nothing when the job is currently executing
     * ([QueueSnapshot.currentIndex] while running) or does not exist.
     * Removing a job before the execution pointer shifts `currentIndex` down
     * so the worker (which re-reads the list every iteration) stays aligned.
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
     */
    suspend fun clearCompleted()

    /**
     * Clear pending section (TO DO):
     * - idle: removes all jobs without a result, keeps results/history;
     * - running: removes all jobs after the currently executing one
     *   (index = currentIndex), keeps past + current. Worker is not
     *   cancelled; it finishes the current job and stops (it re-reads
     *   the job list from DB every iteration).
     */
    suspend fun clearPending()
}

@Singleton
open class DefaultQueueRepository @Inject constructor(
        @ApplicationContext private val context: Context,
    private val dao: QueueStateDao,
    private val connectionRepository: ConnectionRepository,
) : QueueRepository {

    override fun observeSnapshot(): Flow<QueueSnapshot?> =
        dao.observe().map { e ->
            e?.let {
                QueueSnapshot(
                    running = it.running,
                    currentIndex = it.currentIndex,
                    total = it.total,
                    origin = it.origin,
                    stopReason = null,
                    jobProgress = it.jobProgress,
                )
            }
        }

    override fun observeJobs(): Flow<List<QueueJob>> =
        dao.observe().map { e -> decodeJobs(e?.jobsJson) }

    override fun observeResults(): Flow<List<QueueResult>> =
        dao.observe().map { e ->
            if (e == null || e.resultsJson.isBlank()) emptyList()
            else QueueJson.decodeFromString(ListSerializer(QueueResult.serializer()), e.resultsJson)
        }

    override suspend fun stage(jobs: List<QueueJob>, origin: String) {
        require(jobs.isNotEmpty())
        val state = dao.get()
        val currentJobs = decodeJobs(state?.jobsJson)
        if (state != null && state.running && currentJobs.isNotEmpty()) {
            // Append to tail: never reset a running queue (only cancel()
            // may clear running). Worker re-reads jobs from DB each iteration.
            val newJobs = currentJobs + jobs
            dao.save(
                state.copy(
                    jobsJson = encodeJobs(newJobs),
                    total = newJobs.size,
                    jobProgress = 0f,
                ),
            )
            return
        }
        if (state != null && !state.running) {
            // Idle with staged state: append behind existing jobs (DONE section
            // and results stay intact), never replace.
            val newJobs = currentJobs + jobs
            dao.save(
                state.copy(
                    running = false,
                    total = newJobs.size,
                    origin = origin,
                    host = connectionRepository.observe().first().webUiBaseUrl(),
                    jobsJson = encodeJobs(newJobs),
                    jobProgress = 0f,
                ),
            )
            return
        }
        val host = connectionRepository.observe().first().webUiBaseUrl()
        dao.save(
            QueueStateEntity(
                running = false,
                currentIndex = 0,
                total = jobs.size,
                origin = origin,
                host = host,
                jobsJson = encodeJobs(jobs),
                resultsJson = "[]",
                jobProgress = 0f,
            ),
        )
    }

    override suspend fun start() {
        val state = dao.get() ?: return
        val jobs = decodeJobs(state.jobsJson)
        if (state.running || jobs.isEmpty()) return
        val doneIds = decodeResults(state.resultsJson).map { it.jobId }.toSet()
        val pending = jobs.filter { it.id !in doneIds }
        if (pending.isEmpty()) return
        dao.save(state.copy(running = true, jobProgress = 0f))
        launchWorker(state.host, state.jobsJson, state.origin)
    }

    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) {
        require(jobs.isNotEmpty())
        val state = dao.get()
        val currentJobs = decodeJobs(state?.jobsJson)
        if (state != null && state.running && currentJobs.isNotEmpty()) {
            // Insert right after the currently executing job; the worker
            // re-reads the job list from the DB every iteration.
            val at = (state.currentIndex + 1).coerceIn(0, currentJobs.size)
            val merged = currentJobs.take(at) + jobs + currentJobs.drop(at)
            dao.save(
                state.copy(
                    jobsJson = encodeJobs(merged),
                    total = merged.size,
                ),
            )
        } else if (state != null && currentJobs.isNotEmpty()) {
            // Idle with an existing queue: append behind existing jobs (DONE
            // results and currentIndex are kept — currentIndex already points
            // at the first pending job) and start the whole queue. Never
            // replaces the staged queue.
            val host = connectionRepository.observe().first().webUiBaseUrl()
            val merged = currentJobs + jobs
            val mergedJson = encodeJobs(merged)
            dao.save(
                state.copy(
                    running = true,
                    total = merged.size,
                    origin = origin,
                    host = host,
                    jobsJson = mergedJson,
                    jobProgress = 0f,
                ),
            )
            launchWorker(host, mergedJson, origin)
        } else {
            val host = connectionRepository.observe().first().webUiBaseUrl()
            val jobsJson = encodeJobs(jobs)
            dao.save(
                QueueStateEntity(
                    running = true,
                    currentIndex = 0,
                    total = jobs.size,
                    origin = origin,
                    host = host,
                    jobsJson = jobsJson,
                    resultsJson = "[]",
                    jobProgress = 0f,
                ),
            )
            launchWorker(host, jobsJson, origin)
        }
    }

    override suspend fun removeJob(jobId: String): Boolean {
        val state = dao.get() ?: return false
        val jobs = decodeJobs(state.jobsJson)
        val index = jobs.indexOfFirst { it.id == jobId }
        if (index < 0) return false
        if (state.running && index == state.currentIndex) return false
        val newJobs = jobs.filterIndexed { i, _ -> i != index }
        val newResults = decodeResults(state.resultsJson).filter { it.jobId != jobId }
        val newIndex = if (index < state.currentIndex) {
            state.currentIndex - 1
        } else {
            minOf(state.currentIndex, newJobs.size)
        }
        dao.save(
            state.copy(
                jobsJson = encodeJobs(newJobs),
                resultsJson = encodeResults(newResults),
                total = newJobs.size,
                currentIndex = newIndex,
            ),
        )
        return true
    }

    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean {
        val state = dao.get() ?: return false
        val jobs = decodeJobs(state.jobsJson)
        if (jobs.isEmpty()) return false
        val doneIds = decodeResults(state.resultsJson).map { it.jobId }.toSet()
        val pendingSlots = jobs.mapIndexedNotNull { i, job -> i.takeIf { job.id !in doneIds } }
        if (pendingSlots.isEmpty()) return false
        val fromPos = pendingSlots.indexOfFirst { jobs[it].id == jobId }
        if (fromPos < 0) return false
        // Movable window: idle allows any pending slot; running pins the
        // executing slot (and everything before it) — only later pending
        // slots may be permuted, in place, so currentIndex stays valid.
        val firstMovable = if (state.running) {
            val execPos = pendingSlots.indexOf(state.currentIndex)
            if (execPos < 0) return false
            execPos + 1
        } else {
            0
        }
        if (fromPos < firstMovable) return false
        if (toPendingIndex < firstMovable || toPendingIndex >= pendingSlots.size) return false
        if (fromPos == toPendingIndex) return true
        val windowJobs = pendingSlots.subList(firstMovable, pendingSlots.size)
            .map { jobs[it] }.toMutableList()
        val moving = windowJobs.removeAt(fromPos - firstMovable)
        windowJobs.add(toPendingIndex - firstMovable, moving)
        val newJobs = jobs.toMutableList()
        pendingSlots.subList(firstMovable, pendingSlots.size).forEachIndexed { k, slot ->
            newJobs[slot] = windowJobs[k]
        }
        dao.save(state.copy(jobsJson = encodeJobs(newJobs)))
        return true
    }

    override suspend fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(GenerationWorker.UNIQUE_QUEUE)
        dao.get()?.let { dao.save(it.copy(running = false)) }
    }

    override suspend fun clearCompleted() {
        val state = dao.get() ?: return
        val jobs = decodeJobs(state.jobsJson)
        val results = decodeResults(state.resultsJson)
        val doneIds = results.map { it.jobId }.toSet()
        val newJobs = jobs.filter { it.id !in doneIds }
        val newResults = results.filter { it.jobId !in doneIds }
        dao.save(
            state.copy(
                jobsJson = encodeJobs(newJobs),
                resultsJson = encodeResults(newResults),
                total = newJobs.size,
                currentIndex = minOf(state.currentIndex, newJobs.size),
            ),
        )
    }

    override suspend fun clearPending() {
        val state = dao.get() ?: return
        val jobs = decodeJobs(state.jobsJson)
        val results = decodeResults(state.resultsJson)
        if (!state.running) {
            val doneIds = results.map { it.jobId }.toSet()
            val newJobs = jobs.filter { it.id in doneIds }
            dao.save(
                state.copy(
                    jobsJson = encodeJobs(newJobs),
                    total = newJobs.size,
                    currentIndex = 0,
                ),
            )
        } else {
            val keep = jobs.take((state.currentIndex + 1).coerceIn(0, jobs.size))
            val removed = jobs.drop(keep.size)
            val newResults = results.filter { r -> removed.none { it.id == r.jobId } }
            dao.save(
                state.copy(
                    jobsJson = encodeJobs(keep),
                    resultsJson = encodeResults(newResults),
                    total = keep.size,
                ),
            )
        }
    }

    protected open fun launchWorker(host: String, jobsJson: String, origin: String) {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(
                workDataOf(
                    GenerationWorker.KEY_HOST to host,
                    GenerationWorker.KEY_JOBS to jobsJson,
                    GenerationWorker.KEY_ORIGIN to origin,
                ),
            )
            .addTag(GenerationWorker.TAG_QUEUE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GenerationWorker.UNIQUE_QUEUE, ExistingWorkPolicy.REPLACE, request)
        scheduleWatchdog()
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

    private fun encodeJobs(jobs: List<QueueJob>): String =
        QueueJson.encodeToString(ListSerializer(QueueJob.serializer()), jobs)

    private fun decodeJobs(raw: String?): List<QueueJob> =
        if (raw.isNullOrBlank()) emptyList()
        else QueueJson.decodeFromString(ListSerializer(QueueJob.serializer()), raw)

    private fun encodeResults(results: List<QueueResult>): String =
        QueueJson.encodeToString(ListSerializer(QueueResult.serializer()), results)

    private fun decodeResults(raw: String?): List<QueueResult> =
        if (raw.isNullOrBlank()) emptyList()
        else QueueJson.decodeFromString(ListSerializer(QueueResult.serializer()), raw)
}
