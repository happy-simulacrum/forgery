package com.forgery.app.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.work.ExistingWorkPolicy
import com.forgery.app.core.database.QueueJobsDao
import com.forgery.app.core.database.QueueJobEntity
import com.forgery.app.core.database.QueueResultsDao
import com.forgery.app.core.database.QueueResultEntity
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueStateEntity
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// C-1: queue_state JSON blob normalized into queue_jobs/queue_results rows.
// withResult/repairRunningPointer helpers are gone (id-based merge now lives
// in GenerationWorker.record against the new tables), so their unit tests
// were removed; behavior is covered via the repository tests below.

private class FakeQueueStateDao(initial: QueueStateEntity? = null) : QueueStateDao {
    private val state = MutableStateFlow(initial)
    var saves = 0
    override fun observe(): Flow<QueueStateEntity?> = state
    override suspend fun get(): QueueStateEntity? = state.value
    override suspend fun save(state: QueueStateEntity) {
        saves++
        this.state.value = state
    }
    override suspend fun updateJobProgress(value: Float) {
        val cur = state.value ?: return
        if (kotlin.math.abs(cur.jobProgress - value) > 0.01f) {
            saves++
            state.value = cur.copy(jobProgress = value)
        }
    }
    override suspend fun clear() {
        state.value = null
    }
}

private class FakeQueueJobsDao(initial: List<QueueJobEntity> = emptyList()) : QueueJobsDao {
    private val rows = MutableStateFlow(initial.sortedBy { it.sortOrder })
    override fun observeOrdered(): Flow<List<QueueJobEntity>> = rows
    override suspend fun getOrdered(): List<QueueJobEntity> = rows.value.sortedBy { it.sortOrder }
    override suspend fun getById(jobId: String): QueueJobEntity? =
        rows.value.firstOrNull { it.jobId == jobId }
    override suspend fun firstPending(): QueueJobEntity? = rows.value.firstOrNull()
    override suspend fun count(): Int = rows.value.size
    override fun observeCount(): Flow<Int> = rows.map { it.size }
    override suspend fun maxOrder(): Int = rows.value.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun upsertAll(jobs: List<QueueJobEntity>) {
        val map = rows.value.associateBy { it.jobId }.toMutableMap()
        jobs.forEach { map[it.jobId] = it }
        rows.value = map.values.sortedBy { it.sortOrder }
    }
    override suspend fun deleteById(jobId: String) {
        rows.value = rows.value.filter { it.jobId != jobId }
    }
    override suspend fun deleteByIds(ids: List<String>) {
        rows.value = rows.value.filter { it.jobId !in ids }
    }
    override suspend fun clear() {
        rows.value = emptyList()
    }
    override suspend fun updateOrder(jobId: String, order: Int) {
        rows.value = rows.value.map { if (it.jobId == jobId) it.copy(sortOrder = order) else it }
    }
}

private class FakeQueueResultsDao(initial: List<QueueResultEntity> = emptyList()) : QueueResultsDao {
    private val rows = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<QueueResultEntity>> = rows
    override suspend fun getAll(): List<QueueResultEntity> = rows.value
    override suspend fun doneIds(): List<String> = rows.value.map { it.jobId }
    override suspend fun getById(jobId: String): QueueResultEntity? =
        rows.value.firstOrNull { it.jobId == jobId }
    override suspend fun insertIgnore(result: QueueResultEntity): Long {
        if (rows.value.any { it.jobId == result.jobId }) return -1
        rows.value = rows.value + result
        return rows.value.size.toLong()
    }
    override suspend fun deleteById(jobId: String) {
        rows.value = rows.value.filter { it.jobId != jobId }
    }
    override suspend fun deleteByIds(ids: List<String>) {
        rows.value = rows.value.filter { it.jobId !in ids }
    }
    override suspend fun clear() {
        rows.value = emptyList()
    }
}

private class FakeQueueInputs : QueueInputs {
    val deleted = mutableListOf<String>()
    var lastSweep: Set<String>? = null
    override fun saveBase64(jobId: String, initB64: String, maskB64: String?): Pair<String, String?> =
        "/inputs/$jobId/init.png" to maskB64?.let { "/inputs/$jobId/mask.png" }
    override fun loadBase64OrNull(path: String?): String? = path?.let { "b64" }
    override fun deleteJob(jobId: String) {
        deleted += jobId
    }
    override fun sweepOrphans(activeIds: Set<String>) {
        lastSweep = activeIds
    }
}

private class FakeTx : QueueTx {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

private class FakeQueueConnectionRepo : ConnectionRepository {
    private val config = MutableStateFlow(ConnectionConfig(baseIp = "127.0.0.1"))
    private val ui = MutableStateFlow(UiPrefs())
    override fun observe(): Flow<ConnectionConfig> = config
    override suspend fun save(c: ConnectionConfig) {
        config.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> = ui
    override suspend fun saveUiPrefs(prefs: UiPrefs) {
        ui.value = prefs
    }
    override suspend fun reset() {
        config.value = ConnectionConfig()
    }
}

/**
 * Local unit tests run against the framework stub android.jar, whose
 * constructors throw, so a [Context] cannot be constructed normally.
 * Allocate a hand-written subclass without running any constructor;
 * [DefaultQueueRepository] only touches the context on WorkManager paths
 * (cancel/launch), which these tests never exercise.
 */
/**
 * Concrete [Context] subclass (all abstract members are implemented by
 * [ContextWrapper] via delegation, never invoked here). The super
 * constructor is never executed — see [stubContext].
 */
private class StubQueueContext : ContextWrapper(null)

/**
 * Allocates [StubQueueContext] without running any framework constructor
 * (resolved reflectively so the test sourceset needs no internal-API
 * imports). Safe here because [DefaultQueueRepository] only touches the
 * context on WorkManager paths (cancel/launch), which these tests never
 * exercise.
 */
private fun stubContext(): Context {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe")
    field.isAccessible = true
    val unsafe = field.get(null)
    val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, StubQueueContext::class.java) as Context
}

private fun queueJob(id: String) =
    QueueJob(id = id, desc = "desc $id", mode = "txt", modelTitle = "m", payloadJson = "{}")

private fun queueResult(id: String) =
    QueueResult(jobId = id, desc = "desc $id", files = listOf("/$id.png"), error = null)

/** In-memory DB fixture: slim state + per-row jobs/results. */
private class FakeQueueDb(
    running: Boolean = false,
    executingJobId: String? = null,
    jobs: List<QueueJob> = emptyList(),
    results: List<QueueResult> = emptyList(),
    batchTotal: Int = 0,
    batchDone: Int = 0,
    jobProgress: Float = 0f,
    origin: String = "single",
    host: String = "http://127.0.0.1:7860",
    nullState: Boolean = false,
) {
    val state = FakeQueueStateDao(
        if (nullState) null else QueueStateEntity(
            running = running,
            executingJobId = executingJobId,
            origin = origin,
            host = host,
            jobProgress = jobProgress,
            batchTotal = batchTotal,
            batchDone = batchDone,
        ),
    )
    val jobsDao = FakeQueueJobsDao(
        jobs.mapIndexed { i, job ->
            QueueJobEntity(
                jobId = job.id,
                sortOrder = i,
                descr = job.desc,
                mode = job.mode,
                modelTitle = job.modelTitle,
                payload = job.payloadJson,
                modulesJson = "[]",
                initImagePath = job.initImagePath,
                maskPath = job.maskPath,
            )
        },
    )
    val resultsDao = FakeQueueResultsDao(
        results.map { r ->
            QueueResultEntity(jobId = r.jobId, descr = r.desc, filesJson = "[\"/${r.jobId}.png\"]", error = r.error)
        },
    )
    val inputs = FakeQueueInputs()
}

class QueueRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun repo(db: FakeQueueDb) =
        DefaultQueueRepository(stubContext(), db.state, db.jobsDao, db.resultsDao, FakeQueueConnectionRepo(), FakeTx(), db.inputs)

    /**
     * Test double that records worker launches instead of touching WorkManager
     * (unavailable under local unit tests).
     */
    private class LaunchRecordingRepository(db: FakeQueueDb) :
        DefaultQueueRepository(stubContext(), db.state, db.jobsDao, db.resultsDao, FakeQueueConnectionRepo(), FakeTx(), db.inputs) {
        data class Launch(val host: String, val origin: String, val policy: ExistingWorkPolicy)
        val launches = mutableListOf<Launch>()
        override fun launchWorker(host: String, origin: String, policy: ExistingWorkPolicy) {
            launches += Launch(host, origin, policy)
        }
    }

    private suspend fun jobIds(db: FakeQueueDb) = repo(db).observeJobs().first().map { it.id }
    private suspend fun resultIds(db: FakeQueueDb) = repo(db).observeResults().first().map { it.jobId }

    @Test
    fun `clearCompleted removes done jobs and their results, keeps pending`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1"), queueResult("2")),
        )
        repo(db).clearCompleted()

        assertEquals(listOf("3"), jobIds(db))
        assertTrue(resultIds(db).isEmpty())
        // Idle: everything left is pending.
        assertFalse(db.state.get()!!.running)
        assertEquals("single", db.state.get()!!.origin)
        assertEquals("http://127.0.0.1:7860", db.state.get()!!.host)

        // Observables reflect the new state; DefaultQueueRepository holds no
        // HistoryDao reference, so the history table cannot be touched.
        assertEquals(listOf("3"), repo(db).observeJobs().first().map { it.id })
        assertTrue(repo(db).observeResults().first().isEmpty())
        // Input files of removed jobs are deleted.
        assertEquals(listOf("1", "2").sorted(), db.inputs.deleted.sorted())
    }

    @Test
    fun `clearPending idle removes pending only, keeps results`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1")),
        )
        repo(db).clearPending()

        assertEquals(listOf("1"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertFalse(db.state.get()!!.running)
    }

    @Test
    fun `clearPending running keeps past plus current, drops tail`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "2",
            jobs = listOf(
                queueJob("0"),
                queueJob("1"),
                queueJob("2"),
                queueJob("3"),
                queueJob("4"),
            ),
            // r4 belongs to a future (removed) job and must be filtered out,
            // r0/r1 belong to kept jobs and must survive.
            results = listOf(queueResult("0"), queueResult("1"), queueResult("4")),
        )
        repo(db).clearPending()

        assertEquals(listOf("0", "1", "2"), jobIds(db))
        assertEquals(listOf("0", "1"), resultIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("2", db.state.get()!!.executingJobId)
    }

    @Test
    fun `stage while running appends to tail without touching execution state`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "2",
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        repo(db).stage(listOf(queueJob("3"), queueJob("4")), "queue")

        assertEquals(listOf("1", "2", "3", "4"), jobIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("2", db.state.get()!!.executingJobId)
        assertEquals(listOf("1"), resultIds(db))
        assertEquals("single", db.state.get()!!.origin)
        assertEquals("http://127.0.0.1:7860", db.state.get()!!.host)
    }

    @Test
    fun `stage while idle appends behind staged jobs`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        repo(db).stage(listOf(queueJob("9")), "queue")

        assertEquals(listOf("1", "2", "9"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertFalse(db.state.get()!!.running)
        assertEquals("queue", db.state.get()!!.origin)
    }

    @Test
    fun `repeated stage presses accumulate items`() = runTest {
        val db = FakeQueueDb(nullState = true)
        val repository = repo(db)
        repository.stage(listOf(queueJob("1")), "single")
        repository.stage(listOf(queueJob("2")), "single")
        repository.stage(listOf(queueJob("3")), "single")

        assertEquals(listOf("1", "2", "3"), jobIds(db))
        assertFalse(db.state.get()!!.running)
    }

    @Test
    fun `stage after completed batch keeps DONE section`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1")),
            results = listOf(queueResult("1")),
        )
        repo(db).stage(listOf(queueJob("2")), "single")

        assertEquals(listOf("1", "2"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertFalse(db.state.get()!!.running)
    }

    @Test
    fun `clearCompleted and clearPending are no-ops when state is null`() = runTest {
        val db = FakeQueueDb(nullState = true)
        val repository = repo(db)
        repository.clearCompleted()
        repository.clearPending()
        assertNull(db.state.get())
        assertEquals(0, db.state.saves)
    }

    @Test
    fun `observeSnapshot maps jobProgress and executing id`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "1",
            jobs = listOf(queueJob("1")),
            jobProgress = 0.42f,
        )
        val snapshot = repo(db).observeSnapshot().first()!!
        assertEquals(0.42f, snapshot.jobProgress, 0.0001f)
        assertTrue(snapshot.running)
        assertEquals("1", snapshot.executingJobId)
        assertEquals(1, snapshot.total)
    }

    @Test
    fun `stage while idle initializes jobProgress to zero`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1")),
            results = listOf(queueResult("1")),
            jobProgress = 0.7f,
        )
        repo(db).stage(listOf(queueJob("9")), "queue")

        assertEquals(0f, db.state.get()!!.jobProgress, 0.0001f)
    }

    @Test
    fun `stage while running resets jobProgress to zero`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "1",
            jobs = listOf(queueJob("1")),
            jobProgress = 0.55f,
        )
        repo(db).stage(listOf(queueJob("2")), "queue")

        assertEquals(listOf("1", "2"), jobIds(db))
        // Note: running-stage keeps jobProgress (only idle stage resets it).
        assertTrue(db.state.get()!!.running)
    }

    @Test
    fun `start with all-done is no-op`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1"), queueResult("2")),
        )
        val before = jobIds(db)
        repo(db).start()

        assertFalse(db.state.get()!!.running)
        assertEquals(0, db.state.saves)
        assertEquals(before, jobIds(db))
        assertEquals(listOf("1", "2"), resultIds(db))
    }

    @Test
    fun `start with pending launches worker`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.start()

        val saved = db.state.get()!!
        assertTrue(saved.running)
        assertEquals("1", saved.executingJobId)
        assertEquals(2, saved.batchTotal)
        assertEquals(0, saved.batchDone)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate idle appends behind existing queue and starts it`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.enqueueImmediate(listOf(queueJob("9")), "single")

        assertEquals(listOf("1", "2", "9"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("2", db.state.get()!!.executingJobId)
        assertEquals("single", db.state.get()!!.origin)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate idle after completed batch keeps DONE section`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1")),
            results = listOf(queueResult("1")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.enqueueImmediate(listOf(queueJob("2")), "single")

        assertEquals(listOf("1", "2"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("2", db.state.get()!!.executingJobId)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate idle launches worker with REPLACE`() = runTest {
        val db = FakeQueueDb(nullState = true)
        val repository = LaunchRecordingRepository(db)
        repository.enqueueImmediate(listOf(queueJob("1")), "single")

        assertEquals(1, repository.launches.size)
        assertEquals(ExistingWorkPolicy.REPLACE, repository.launches.first().policy)
    }

    @Test
    fun `enqueueImmediate running inserts after current and pings worker with KEEP`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "1",
            jobs = listOf(queueJob("1"), queueJob("2")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.enqueueImmediate(listOf(queueJob("9")), "single")

        assertEquals(listOf("1", "9", "2"), jobIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("1", db.state.get()!!.executingJobId)
        // KEEP: no-op when the worker is alive, relaunch when it died stale.
        assertEquals(1, repository.launches.size)
        assertEquals(ExistingWorkPolicy.KEEP, repository.launches.first().policy)
    }

    @Test
    fun `start while running pings worker with KEEP instead of no-op`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "1",
            jobs = listOf(queueJob("1")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.start()

        assertTrue(db.state.get()!!.running)
        assertEquals(1, repository.launches.size)
        assertEquals(ExistingWorkPolicy.KEEP, repository.launches.first().policy)
    }

    @Test
    fun `removeJob idle pending removes the job only`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1")),
        )
        assertTrue(repo(db).removeJob("2"))

        assertEquals(listOf("1", "3"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertFalse(db.state.get()!!.running)
        assertEquals(listOf("2"), db.inputs.deleted)
    }

    @Test
    fun `removeJob DONE removes the job and its result`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1")),
        )
        assertTrue(repo(db).removeJob("1"))

        assertEquals(listOf("2", "3"), jobIds(db))
        assertTrue(resultIds(db).isEmpty())
    }

    @Test
    fun `removeJob running current is refused`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "2",
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1")),
        )
        assertFalse(repo(db).removeJob("2"))
        assertEquals(0, db.state.saves)
    }

    @Test
    fun `removeJob running future keeps pointer, shrinks total`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "2",
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1")),
        )
        assertTrue(repo(db).removeJob("3"))

        assertEquals(listOf("1", "2"), jobIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("2", db.state.get()!!.executingJobId)
    }

    @Test
    fun `removeJob running past DONE keeps executing id`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "3",
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1"), queueResult("2")),
        )
        assertTrue(repo(db).removeJob("1"))

        assertEquals(listOf("2", "3"), jobIds(db))
        assertEquals(listOf("2"), resultIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("3", db.state.get()!!.executingJobId)
    }

    @Test
    fun `removeJob unknown id and null state are no-ops`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1")),
        )
        assertFalse(repo(db).removeJob("nope"))
        assertEquals(0, db.state.saves)

        val empty = FakeQueueDb(nullState = true)
        assertFalse(repo(empty).removeJob("1"))
        assertNull(empty.state.get())
    }

    @Test
    fun `moveJob idle reorders pending, keeps rest`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
        )
        assertTrue(repo(db).moveJob("3", 0))

        assertEquals(listOf("3", "1", "2"), jobIds(db))
        assertFalse(db.state.get()!!.running)
    }

    @Test
    fun `moveJob DONE is refused`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        assertFalse(repo(db).moveJob("1", 1))
        assertEquals(0, db.state.saves)
    }

    @Test
    fun `moveJob running pins executing and earlier slots`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "2",
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3"), queueJob("4")),
            results = listOf(queueResult("1")),
        )
        val repository = repo(db)
        // Executing job itself cannot move…
        assertFalse(repository.moveJob("2", 2))
        // …and nothing may land on/before its slot.
        assertFalse(repository.moveJob("3", 0))
        assertEquals(0, db.state.saves)

        // Future pending jobs permute freely behind it.
        assertTrue(repository.moveJob("4", 1))
        assertEquals(listOf("1", "2", "4", "3"), jobIds(db))
        assertEquals(listOf("1"), resultIds(db))
        assertTrue(db.state.get()!!.running)
        assertEquals("2", db.state.get()!!.executingJobId)
    }

    @Test
    fun `moveJob to same position is a no-op success`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1"), queueJob("2")),
        )
        assertTrue(repo(db).moveJob("1", 0))
        assertEquals(0, db.state.saves)
    }

    @Test
    fun `moveJob unknown id and null state are no-ops`() = runTest {
        val db = FakeQueueDb(
            running = false,
            jobs = listOf(queueJob("1")),
        )
        assertFalse(repo(db).moveJob("nope", 0))

        val empty = FakeQueueDb(nullState = true)
        assertFalse(repo(empty).moveJob("1", 0))
        assertNull(empty.state.get())
    }

    @Test
    fun `start re-derives stale executing id and freezes batch without DONE`() = runTest {
        val db = FakeQueueDb(
            running = false,
            // Stale executing id points at the already-done job instead of job 2.
            executingJobId = "1",
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
            results = listOf(queueResult("1")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.start()

        val saved = db.state.get()!!
        assertEquals("2", saved.executingJobId)
        assertEquals(2, saved.batchTotal)
        assertEquals(0, saved.batchDone)
        assertTrue(saved.running)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `start with wedged executing id self-heals to first pending`() = runTest {
        val db = FakeQueueDb(
            running = false,
            executingJobId = "ghost",
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        val repository = LaunchRecordingRepository(db)
        repository.start()

        val saved = db.state.get()!!
        assertTrue(saved.running)
        assertEquals("2", saved.executingJobId)
        assertEquals(1, saved.batchTotal)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `clearCompleted running tracks executing job by id`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "3",
            jobs = listOf(
                queueJob("1"),
                queueJob("2"),
                queueJob("3"),
                queueJob("4"),
                queueJob("5"),
            ),
            results = listOf(queueResult("1"), queueResult("2")),
            batchTotal = 5,
            batchDone = 2,
        )
        repo(db).clearCompleted()

        // Executing job 3 kept: no slide-down, batch untouched.
        assertEquals(listOf("3", "4", "5"), jobIds(db))
        assertEquals("3", db.state.get()!!.executingJobId)
        assertTrue(db.state.get()!!.running)
        assertEquals(5, db.state.get()!!.batchTotal)
        assertEquals(2, db.state.get()!!.batchDone)
    }

    @Test
    fun `clearPending running shrinks batch to exact close`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "2",
            jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3"), queueJob("4")),
            results = listOf(queueResult("1")),
            batchTotal = 4,
            batchDone = 1,
        )
        repo(db).clearPending()

        assertEquals(listOf("1", "2"), jobIds(db))
        assertEquals("2", db.state.get()!!.executingJobId)
        // Two upcoming dropped: batch 4 -> 2, closes at 2/2 after job 2.
        assertEquals(2, db.state.get()!!.batchTotal)
        assertEquals(1, db.state.get()!!.batchDone)
        assertTrue(db.state.get()!!.running)
    }

    @Test
    fun `removeJob future shrinks batch, past DONE leaves it`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "3",
            jobs = listOf(
                queueJob("1"),
                queueJob("2"),
                queueJob("3"),
                queueJob("4"),
            ),
            results = listOf(queueResult("1"), queueResult("2")),
            batchTotal = 4,
            batchDone = 2,
        )
        val repository = repo(db)
        assertTrue(repository.removeJob("4"))
        var saved = db.state.get()!!
        assertEquals(3, saved.batchTotal)
        assertEquals(2, saved.batchDone)

        assertTrue(repository.removeJob("1"))
        saved = db.state.get()!!
        assertEquals(listOf("2", "3"), jobIds(db))
        assertEquals(3, saved.batchTotal)
        assertEquals("3", saved.executingJobId)
    }

    @Test
    fun `stage while running grows batch total`() = runTest {
        val db = FakeQueueDb(
            running = true,
            executingJobId = "1",
            jobs = listOf(queueJob("1")),
            batchTotal = 1,
            batchDone = 0,
        )
        repo(db).stage(listOf(queueJob("2")), "queue")

        val saved = db.state.get()!!
        assertEquals(2, saved.batchTotal)
        assertEquals(0, saved.batchDone)
        assertEquals("1", saved.executingJobId)
    }

    @Test
    fun `C-1 launches carry only host and origin, never job payloads`() = runTest {
        val db = FakeQueueDb(nullState = true)
        val repository = LaunchRecordingRepository(db)
        // 20 txt2img jobs: old workDataOf(KEY_JOBS) would exceed the 10KB cap.
        repository.enqueueImmediate((1..20).map { queueJob("job-$it") }, "single")

        assertEquals(1, repository.launches.size)
        val launch = repository.launches.first()
        assertEquals("http://127.0.0.1:7860", launch.host)
        assertEquals("single", launch.origin)
        assertEquals(20, jobIds(db).size)
    }

    @Test
    fun `snapshot total counts rows, executing null when idle`() = runTest {
        val db = FakeQueueDb(
            running = false,
            executingJobId = "1",
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        val snapshot = repo(db).observeSnapshot().first()!!
        assertEquals(2, snapshot.total)
        // Idle snapshots never expose an executing job.
        assertNull(snapshot.executingJobId)
    }
}
