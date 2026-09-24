package com.forgery.app.core.data

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueStateEntity
import com.forgery.app.core.database.QueueTx
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

internal val QueueJson = Json { ignoreUnknownKeys = true }

internal fun decodeQueueJobs(raw: String?): List<QueueJob> =
    if (raw.isNullOrBlank()) emptyList()
    else QueueJson.decodeFromString(ListSerializer(QueueJob.serializer()), raw)

internal fun encodeQueueJobs(jobs: List<QueueJob>): String =
    QueueJson.encodeToString(ListSerializer(QueueJob.serializer()), jobs)

internal fun decodeQueueResults(raw: String?): List<QueueResult> =
    if (raw.isNullOrBlank()) emptyList()
    else QueueJson.decodeFromString(ListSerializer(QueueResult.serializer()), raw)

internal fun encodeQueueResults(results: List<QueueResult>): String =
    QueueJson.encodeToString(ListSerializer(QueueResult.serializer()), results)

internal const val QueueLogTag = "ForgeryQueue"

/**
 * Applies a finished [result] to a freshly-read [QueueStateEntity].
 *
 * Id-based (not index-based): the worker captures the job id before the long
 * network call and merges against the state as it is *now*, so concurrent
 * list mutations (clear/remove mid-flight) cannot resurrect dropped jobs or
 * shift the pointer. Returns null when there is nothing to apply: the result
 * is a duplicate, or the job vanished from the list (caller repairs the
 * pointer via [repairRunningPointer] and continues).
 */
internal fun QueueStateEntity.withResult(
    jobId: String,
    result: QueueResult,
    failed: Boolean,
): QueueStateEntity? {
    val jobs = decodeQueueJobs(jobsJson)
    val prev = decodeQueueResults(resultsJson)
    if (prev.any { it.jobId == jobId }) return null
    val at = jobs.indexOfFirst { it.id == jobId }
    if (at < 0) return null
    return copy(
        currentIndex = at + 1,
        running = if (failed) false else running,
        jobProgress = 0f,
        resultsJson = encodeQueueResults(prev + result),
        batchDone = batchDone + 1,
    )
}

/**
 * Self-heal for a stale execution pointer while running: re-derives
 * `currentIndex` as the first result-less job. Returns null when the pointer
 * is already healthy (points at a result-less job) or the queue is idle, so
 * callers only write on actual repair. Never rewinds past a healthy pointer.
 */
internal fun repairRunningPointer(
    state: QueueStateEntity,
    jobs: List<QueueJob>,
    doneIds: Set<String>,
): QueueStateEntity? {
    if (!state.running) return null
    val cur = jobs.getOrNull(state.currentIndex)
    if (cur != null && cur.id !in doneIds) return null
    val firstPending = jobs.indexOfFirst { it.id !in doneIds }
    val fixed = if (firstPending < 0) jobs.size else firstPending
    if (fixed == state.currentIndex) return null
    Log.d(QueueLogTag, "repair pointer ${state.currentIndex} -> $fixed (jobs=${jobs.map { it.id }})")
    return state.copy(currentIndex = fixed)
}

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
     * queue is never reset (running/currentIndex/resultsJson/host/origin kept);
     * the launched batch grows ([QueueSnapshot.batchTotal] += appended).
     */
    suspend fun stage(jobs: List<QueueJob>, origin: String)

    /**
     * Start execution of staged jobs (QUE "Start Queue"). The execution
     * pointer is re-derived as the first result-less job (never trusts the
     * stored `currentIndex`), and the batch counter is frozen as
     * `batchTotal = pending count, batchDone = 0`. No-op when empty/no pending.
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
     * ([QueueSnapshot.currentIndex] while running) or does not exist.
     * Removing a job before the execution pointer shifts `currentIndex` down
     * so the worker (which re-reads the list every iteration) stays aligned.
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
     * - running: removes all jobs after the currently executing one
     *   (index = currentIndex), keeps past + current. Worker is not
     *   cancelled; it finishes the current job and stops (it re-reads
     *   the job list from DB every iteration). The launched batch shrinks by
     *   the removed upcoming count (never below already-completed).
     */
    suspend fun clearPending()
}

private data class WorkerLaunch(
    val host: String,
    val jobsJson: String,
    val origin: String,
    val policy: ExistingWorkPolicy,
)

@Singleton
open class DefaultQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: QueueStateDao,
    private val connectionRepository: ConnectionRepository,
    private val tx: QueueTx,
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
                    batchTotal = it.batchTotal,
                    batchDone = it.batchDone,
                )
            }
        }

    override fun observeJobs(): Flow<List<QueueJob>> =
        dao.observe().map { e -> decodeQueueJobs(e?.jobsJson) }

    override fun observeResults(): Flow<List<QueueResult>> =
        dao.observe().map { e ->
            if (e == null || e.resultsJson.isBlank()) emptyList()
            else QueueJson.decodeFromString(ListSerializer(QueueResult.serializer()), e.resultsJson)
        }

    override suspend fun stage(jobs: List<QueueJob>, origin: String) {
        require(jobs.isNotEmpty())
        tx.run {
            val state = dao.get()
            val currentJobs = decodeQueueJobs(state?.jobsJson)
            if (state != null && state.running && currentJobs.isNotEmpty()) {
                // Append to tail: never reset a running queue (only cancel()
                // may clear running). Worker re-reads jobs from DB each iteration.
                // The launched batch grows: the counter must include additions.
                val newJobs = currentJobs + jobs
                dao.save(
                    state.copy(
                        jobsJson = encodeQueueJobs(newJobs),
                        total = newJobs.size,
                        jobProgress = 0f,
                        batchTotal = state.batchTotal + jobs.size,
                    ),
                )
                return@run
            }
            if (state != null && !state.running) {
                // Idle with staged state: append behind existing jobs (DONE section
                // and results stay intact), never replace. Pointer/batch are
                // (re-)derived at start(), so they are left alone here.
                val newJobs = currentJobs + jobs
                dao.save(
                    state.copy(
                        running = false,
                        total = newJobs.size,
                        origin = origin,
                        host = connectionRepository.observe().first().webUiBaseUrl(),
                        jobsJson = encodeQueueJobs(newJobs),
                        jobProgress = 0f,
                    ),
                )
                return@run
            }
            val host = connectionRepository.observe().first().webUiBaseUrl()
            dao.save(
                QueueStateEntity(
                    running = false,
                    currentIndex = 0,
                    total = jobs.size,
                    origin = origin,
                    host = host,
                    jobsJson = encodeQueueJobs(jobs),
                    resultsJson = "[]",
                    jobProgress = 0f,
                ),
            )
        }
    }

    override suspend fun start() {
        val launch: WorkerLaunch? = tx.run {
            val state = dao.get() ?: return@run null
            val jobs = decodeQueueJobs(state.jobsJson)
            if (jobs.isEmpty()) return@run null
            if (state.running) {
                // Stale-running self-heal (same as enqueueImmediate below): KEEP
                // is a no-op when the worker is alive, a relaunch when it died.
                // Repair a wedged pointer first (out of bounds or aimed at an
                // already-done job); a healthy pointer is left untouched so a
                // live worker is never rewound.
                val results = decodeQueueResults(state.resultsJson)
                val repaired = repairRunningPointer(state, jobs, results.map { it.jobId }.toSet())
                if (repaired != null) dao.save(repaired)
                val fresh = repaired ?: state
                return@run WorkerLaunch(fresh.host, fresh.jobsJson, fresh.origin, ExistingWorkPolicy.KEEP)
            }
            val doneIds = decodeQueueResults(state.resultsJson).map { it.jobId }.toSet()
            // Never trust the stored pointer: re-derive the first pending job
            // (stale values after clears/stages used to start mid-list or
            // re-run done jobs). Batch scope is frozen here: TODO at launch,
            // DONE excluded.
            val firstPending = jobs.indexOfFirst { it.id !in doneIds }
            if (firstPending < 0) return@run null
            val pendingCount = jobs.count { it.id !in doneIds }
            Log.d(
                QueueLogTag,
                "start: firstPending=$firstPending pending=$pendingCount jobs=${jobs.map { it.id }}",
            )
            dao.save(
                state.copy(
                    running = true,
                    currentIndex = firstPending,
                    jobProgress = 0f,
                    batchTotal = pendingCount,
                    batchDone = 0,
                ),
            )
            WorkerLaunch(state.host, state.jobsJson, state.origin, ExistingWorkPolicy.REPLACE)
        }
        if (launch != null) {
            launchWorker(launch.host, launch.jobsJson, launch.origin, launch.policy)
        }
    }

    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) {
        require(jobs.isNotEmpty())
        val launch: WorkerLaunch? = tx.run {
            val state = dao.get()
            val currentJobs = decodeQueueJobs(state?.jobsJson)
            if (state != null && state.running && currentJobs.isNotEmpty()) {
                // Insert right after the currently executing job; the worker
                // re-reads the job list from the DB every iteration. Always
                // re-enqueue with KEEP: no-op when the worker is alive, relaunch
                // when it died leaving a stale running flag.
                val results = decodeQueueResults(state.resultsJson)
                val repaired =
                    repairRunningPointer(state, currentJobs, results.map { it.jobId }.toSet())
                val base = repaired ?: state
                val at = (base.currentIndex + 1).coerceIn(0, currentJobs.size)
                val merged = currentJobs.take(at) + jobs + currentJobs.drop(at)
                val mergedJson = encodeQueueJobs(merged)
                dao.save(
                    base.copy(
                        jobsJson = mergedJson,
                        total = merged.size,
                        batchTotal = base.batchTotal + jobs.size,
                    ),
                )
                return@run WorkerLaunch(base.host, mergedJson, base.origin, ExistingWorkPolicy.KEEP)
            } else if (state != null && currentJobs.isNotEmpty()) {
                // Idle with an existing queue: append behind existing jobs and
                // start. Pointer and batch scope are derived from results, so a
                // stale pointer (e.g. past-the-end after older builds) can
                // neither skip jobs nor re-run done ones.
                val host = connectionRepository.observe().first().webUiBaseUrl()
                val merged = currentJobs + jobs
                val mergedJson = encodeQueueJobs(merged)
                val mergedResults = decodeQueueResults(state.resultsJson)
                val doneIds = mergedResults.map { it.jobId }.toSet()
                val firstPending = merged.indexOfFirst { it.id !in doneIds }
                val pendingCount = merged.count { it.id !in doneIds }
                Log.d(
                    QueueLogTag,
                    "enqueueImmediate idle: firstPending=$firstPending pending=$pendingCount",
                )
                dao.save(
                    state.copy(
                        running = true,
                        currentIndex = if (firstPending < 0) merged.size else firstPending,
                        total = merged.size,
                        origin = origin,
                        host = host,
                        jobsJson = mergedJson,
                        jobProgress = 0f,
                        batchTotal = pendingCount,
                        batchDone = 0,
                    ),
                )
                if (firstPending < 0) return@run null
                return@run WorkerLaunch(host, mergedJson, origin, ExistingWorkPolicy.REPLACE)
            } else {
                val host = connectionRepository.observe().first().webUiBaseUrl()
                val jobsJson = encodeQueueJobs(jobs)
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
                        batchTotal = jobs.size,
                        batchDone = 0,
                    ),
                )
                return@run WorkerLaunch(host, jobsJson, origin, ExistingWorkPolicy.REPLACE)
            }
        }
        if (launch != null) {
            launchWorker(launch.host, launch.jobsJson, launch.origin, launch.policy)
        }
    }

    override suspend fun removeJob(jobId: String): Boolean = tx.run {
        val state = dao.get() ?: return@run false
        val jobs = decodeQueueJobs(state.jobsJson)
        val index = jobs.indexOfFirst { it.id == jobId }
        if (index < 0) return@run false
        if (state.running && index == state.currentIndex) return@run false
        val results = decodeQueueResults(state.resultsJson)
        val newJobs = jobs.filterIndexed { i, _ -> i != index }
        val newResults = results.filter { it.jobId != jobId }
        val newIndex = if (index < state.currentIndex) {
            state.currentIndex - 1
        } else {
            minOf(state.currentIndex, newJobs.size)
        }
        // Removing an upcoming (result-less, after the pointer) job while
        // running shrinks the launched batch; past DONE removals are outside
        // the batch and leave counters alone.
        val upcomingRemoved =
            state.running && index > state.currentIndex && results.none { it.jobId == jobId }
        val newBatchTotal = if (upcomingRemoved) {
            maxOf(state.batchDone, state.batchTotal - 1)
        } else {
            state.batchTotal
        }
        dao.save(
            state.copy(
                jobsJson = encodeQueueJobs(newJobs),
                resultsJson = encodeQueueResults(newResults),
                total = newJobs.size,
                currentIndex = newIndex,
                batchTotal = newBatchTotal,
            ),
        )
        true
    }

    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean = tx.run {
        val state = dao.get() ?: return@run false
        val jobs = decodeQueueJobs(state.jobsJson)
        if (jobs.isEmpty()) return@run false
        val doneIds = decodeQueueResults(state.resultsJson).map { it.jobId }.toSet()
        val pendingSlots = jobs.mapIndexedNotNull { i, job -> i.takeIf { job.id !in doneIds } }
        if (pendingSlots.isEmpty()) return@run false
        val fromPos = pendingSlots.indexOfFirst { jobs[it].id == jobId }
        if (fromPos < 0) return@run false
        // Movable window: idle allows any pending slot; running pins the
        // executing slot (and everything before it) — only later pending
        // slots may be permuted, in place, so currentIndex stays valid.
        val firstMovable = if (state.running) {
            val execPos = pendingSlots.indexOf(state.currentIndex)
            if (execPos < 0) return@run false
            execPos + 1
        } else {
            0
        }
        if (fromPos < firstMovable) return@run false
        if (toPendingIndex < firstMovable || toPendingIndex >= pendingSlots.size) return@run false
        if (fromPos == toPendingIndex) return@run true
        val windowJobs = pendingSlots.subList(firstMovable, pendingSlots.size)
            .map { jobs[it] }.toMutableList()
        val moving = windowJobs.removeAt(fromPos - firstMovable)
        windowJobs.add(toPendingIndex - firstMovable, moving)
        val newJobs = jobs.toMutableList()
        pendingSlots.subList(firstMovable, pendingSlots.size).forEachIndexed { k, slot ->
            newJobs[slot] = windowJobs[k]
        }
        dao.save(state.copy(jobsJson = encodeQueueJobs(newJobs)))
        true
    }

    override suspend fun cancel() {
        tx.run {
            dao.get()?.let { dao.save(it.copy(running = false)) }
        }
        WorkManager.getInstance(context).cancelUniqueWork(GenerationWorker.UNIQUE_QUEUE)
    }

    override suspend fun clearCompleted() {
        tx.run {
            val state = dao.get() ?: return@run
            val jobs = decodeQueueJobs(state.jobsJson)
            val results = decodeQueueResults(state.resultsJson)
            val doneIds = results.map { it.jobId }.toSet()
            // Track the executing job by id (not position): DONE rows above
            // the pointer disappear, so a positional pointer would slide down
            // and mislabel/skip the rest of the queue.
            val executingId = if (state.running) jobs.getOrNull(state.currentIndex)?.id else null
            val newJobs = jobs.filter { it.id !in doneIds }
            val newResults = results.filter { it.jobId !in doneIds }
            val newIndex = if (state.running && executingId != null) {
                val at = newJobs.indexOfFirst { it.id == executingId }
                if (at >= 0) at else minOf(state.currentIndex, newJobs.size)
            } else {
                // Idle: everything left is pending, pointer restarts at head
                // (start() re-derives it anyway).
                0
            }
            Log.d(
                QueueLogTag,
                "clearCompleted: removed=${jobs.size - newJobs.size} index ${state.currentIndex} -> $newIndex",
            )
            dao.save(
                state.copy(
                    jobsJson = encodeQueueJobs(newJobs),
                    resultsJson = encodeQueueResults(newResults),
                    total = newJobs.size,
                    currentIndex = newIndex,
                ),
            )
        }
    }

    override suspend fun clearPending() {
        tx.run {
            val state = dao.get() ?: return@run
            val jobs = decodeQueueJobs(state.jobsJson)
            val results = decodeQueueResults(state.resultsJson)
            if (!state.running) {
                val doneIds = results.map { it.jobId }.toSet()
                val newJobs = jobs.filter { it.id in doneIds }
                dao.save(
                    state.copy(
                        jobsJson = encodeQueueJobs(newJobs),
                        total = newJobs.size,
                        currentIndex = 0,
                    ),
                )
            } else {
                val keep = jobs.take((state.currentIndex + 1).coerceIn(0, jobs.size))
                val removed = jobs.drop(keep.size)
                val newResults = results.filter { r -> removed.none { it.id == r.jobId } }
                // Dropped upcoming jobs leave the launched batch; the counter
                // never sinks below already-completed so x/y still closes at 100%.
                val removedUpcoming = removed.count { job ->
                    results.none { it.jobId == job.id }
                }
                val newBatchTotal = maxOf(state.batchDone, state.batchTotal - removedUpcoming)
                Log.d(
                    QueueLogTag,
                    "clearPending running: keep=${keep.size} removedUpcoming=$removedUpcoming batch ${state.batchDone}/${state.batchTotal} -> $newBatchTotal",
                )
                dao.save(
                    state.copy(
                        jobsJson = encodeQueueJobs(keep),
                        resultsJson = encodeQueueResults(newResults),
                        total = keep.size,
                        batchTotal = newBatchTotal,
                    ),
                )
            }
        }
    }

    protected open fun launchWorker(
        host: String,
        jobsJson: String,
        origin: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
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
            .enqueueUniqueWork(GenerationWorker.UNIQUE_QUEUE, policy, request)
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
}
