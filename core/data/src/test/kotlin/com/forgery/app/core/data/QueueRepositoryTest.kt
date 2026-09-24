package com.forgery.app.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.work.ExistingWorkPolicy
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val TestQueueJson = Json { ignoreUnknownKeys = true }

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

private fun queueState(
    running: Boolean,
    currentIndex: Int,
    jobs: List<QueueJob>,
    results: List<QueueResult>,
    batchTotal: Int = 0,
    batchDone: Int = 0,
) = QueueStateEntity(
    running = running,
    currentIndex = currentIndex,
    total = jobs.size,
    origin = "single",
    host = "http://127.0.0.1:7860",
    jobsJson = TestQueueJson.encodeToString(ListSerializer(QueueJob.serializer()), jobs),
    resultsJson = TestQueueJson.encodeToString(ListSerializer(QueueResult.serializer()), results),
    batchTotal = batchTotal,
    batchDone = batchDone,
)

private fun decodeTestJobs(raw: String): List<QueueJob> =
    TestQueueJson.decodeFromString(ListSerializer(QueueJob.serializer()), raw)

private fun decodeTestResults(raw: String): List<QueueResult> =
    TestQueueJson.decodeFromString(ListSerializer(QueueResult.serializer()), raw)

class QueueRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun repo(dao: FakeQueueStateDao) =
        DefaultQueueRepository(stubContext(), dao, FakeQueueConnectionRepo(), FakeTx())

    /**
     * Test double that records worker launches instead of touching WorkManager
     * (unavailable under local unit tests).
     */
    private class LaunchRecordingRepository(dao: FakeQueueStateDao) :
        DefaultQueueRepository(stubContext(), dao, FakeQueueConnectionRepo(), FakeTx()) {
        data class Launch(val host: String, val jobsJson: String, val origin: String, val policy: ExistingWorkPolicy)
        val launches = mutableListOf<Launch>()
        override fun launchWorker(host: String, jobsJson: String, origin: String, policy: ExistingWorkPolicy) {
            launches += Launch(host, jobsJson, origin, policy)
        }
    }

    @Test
    fun `clearCompleted removes done jobs and their results, keeps pending`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 2,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1"), queueResult("2")),
            ),
        )
        repo(dao).clearCompleted()

        val saved = dao.get()!!
        assertEquals(listOf("3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals("[]", saved.resultsJson)
        assertEquals(1, saved.total)
        // Idle: everything left is pending, pointer restarts at head.
        assertEquals(0, saved.currentIndex)
        assertFalse(saved.running)
        assertEquals("single", saved.origin)
        assertEquals("http://127.0.0.1:7860", saved.host)

        // Observables reflect the new state; DefaultQueueRepository holds no
        // HistoryDao reference, so the history table cannot be touched.
        val check = repo(dao)
        assertEquals(listOf("3"), check.observeJobs().first().map { it.id })
        assertTrue(check.observeResults().first().isEmpty())
    }

    @Test
    fun `clearPending idle removes pending only, keeps results`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1")),
            ),
        )
        repo(dao).clearPending()

        val saved = dao.get()!!
        assertEquals(listOf("1"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertEquals(1, saved.total)
        assertEquals(0, saved.currentIndex)
        assertFalse(saved.running)
    }

    @Test
    fun `clearPending running keeps past plus current, drops tail`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 2,
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
            ),
        )
        repo(dao).clearPending()

        val saved = dao.get()!!
        assertEquals(listOf("0", "1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("0", "1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertEquals(3, saved.total)
        assertTrue(saved.running)
        assertEquals(2, saved.currentIndex)
    }

    @Test
    fun `stage while running appends to tail without touching execution state`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = listOf(queueResult("1")),
            ),
        )
        repo(dao).stage(listOf(queueJob("3"), queueJob("4")), "queue")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2", "3", "4"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertEquals(4, saved.total)
        assertEquals("single", saved.origin)
        assertEquals("http://127.0.0.1:7860", saved.host)
    }

    @Test
    fun `stage while idle appends behind staged jobs`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = listOf(queueResult("1")),
            ),
        )
        repo(dao).stage(listOf(queueJob("9")), "queue")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2", "9"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertEquals(3, saved.total)
        assertEquals(1, saved.currentIndex)
        assertFalse(saved.running)
        assertEquals("queue", saved.origin)
    }

    @Test
    fun `repeated stage presses accumulate items`() = runTest {
        val dao = FakeQueueStateDao(null)
        val repository = repo(dao)
        repository.stage(listOf(queueJob("1")), "single")
        repository.stage(listOf(queueJob("2")), "single")
        repository.stage(listOf(queueJob("3")), "single")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2", "3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(3, saved.total)
        assertFalse(saved.running)
    }

    @Test
    fun `stage after completed batch keeps DONE section`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1")),
                results = listOf(queueResult("1")),
            ),
        )
        repo(dao).stage(listOf(queueJob("2")), "single")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertEquals(2, saved.total)
        assertFalse(saved.running)
    }

    @Test
    fun `clearCompleted and clearPending are no-ops when state is null`() = runTest {
        val dao = FakeQueueStateDao(null)
        val repository = repo(dao)
        repository.clearCompleted()
        repository.clearPending()
        assertNull(dao.get())
        assertEquals(0, dao.saves)
    }

    @Test
    fun `observeSnapshot maps jobProgress`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 0,
                jobs = listOf(queueJob("1")),
                results = emptyList(),
            ).copy(jobProgress = 0.42f),
        )
        val snapshot = repo(dao).observeSnapshot().first()!!
        assertEquals(0.42f, snapshot.jobProgress, 0.0001f)
        assertTrue(snapshot.running)
    }

    @Test
    fun `stage while idle initializes jobProgress to zero`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1")),
                results = listOf(queueResult("1")),
            ).copy(jobProgress = 0.7f),
        )
        repo(dao).stage(listOf(queueJob("9")), "queue")

        val saved = dao.get()!!
        assertEquals(0f, saved.jobProgress, 0.0001f)
    }

    @Test
    fun `stage while running resets jobProgress to zero`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 0,
                jobs = listOf(queueJob("1")),
                results = emptyList(),
            ).copy(jobProgress = 0.55f),
        )
        repo(dao).stage(listOf(queueJob("2")), "queue")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(0f, saved.jobProgress, 0.0001f)
        assertTrue(saved.running)
    }

    @Test
    fun `start with all-done is no-op`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = listOf(queueResult("1"), queueResult("2")),
            ),
        )
        val before = dao.get()!!
        repo(dao).start()

        val saved = dao.get()!!
        assertFalse(saved.running)
        assertEquals(0, dao.saves)
        assertEquals(decodeTestJobs(before.jobsJson).map { it.id }, decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(
            decodeTestResults(before.resultsJson).map { it.jobId },
            decodeTestResults(saved.resultsJson).map { it.jobId },
        )
        assertEquals(before.total, saved.total)
        assertEquals(before.currentIndex, saved.currentIndex)
    }

    @Test
    fun `start with pending launches worker`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 0,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = emptyList(),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.start()

        val saved = dao.get()!!
        assertTrue(saved.running)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate idle appends behind existing queue and starts it`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = listOf(queueResult("1")),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.enqueueImmediate(listOf(queueJob("9")), "single")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2", "9"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(3, saved.total)
        assertEquals("single", saved.origin)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate idle clamps stale currentIndex past end`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 2,
                jobs = listOf(queueJob("1")),
                results = listOf(queueResult("1")),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.enqueueImmediate(listOf(queueJob("2")), "single")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(1, saved.currentIndex)
        assertEquals(2, saved.total)
        assertTrue(saved.running)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate idle launches worker with REPLACE`() = runTest {
        val dao = FakeQueueStateDao(null)
        val repository = LaunchRecordingRepository(dao)
        repository.enqueueImmediate(listOf(queueJob("1")), "single")

        assertEquals(1, repository.launches.size)
        assertEquals(ExistingWorkPolicy.REPLACE, repository.launches.first().policy)
    }

    @Test
    fun `enqueueImmediate idle after completed batch keeps DONE section`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1")),
                results = listOf(queueResult("1")),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.enqueueImmediate(listOf(queueJob("2")), "single")

        val saved = dao.get()!!
        assertEquals(listOf("1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(2, saved.total)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `enqueueImmediate running inserts after current and pings worker with KEEP`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 0,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = emptyList(),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.enqueueImmediate(listOf(queueJob("9")), "single")

        val saved = dao.get()!!
        assertEquals(listOf("1", "9", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertTrue(saved.running)
        assertEquals(0, saved.currentIndex)
        assertEquals(3, saved.total)
        // KEEP: no-op when the worker is alive, relaunch when it died stale.
        assertEquals(1, repository.launches.size)
        assertEquals(ExistingWorkPolicy.KEEP, repository.launches.first().policy)
        assertEquals(saved.jobsJson, repository.launches.first().jobsJson)
    }

    @Test
    fun `start while running pings worker with KEEP instead of no-op`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 0,
                jobs = listOf(queueJob("1")),
                results = emptyList(),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.start()

        assertTrue(dao.get()!!.running)
        assertEquals(1, repository.launches.size)
        assertEquals(ExistingWorkPolicy.KEEP, repository.launches.first().policy)
    }

    @Test
    fun `removeJob idle pending removes the job only`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1")),
            ),
        )
        assertTrue(repo(dao).removeJob("2"))

        val saved = dao.get()!!
        assertEquals(listOf("1", "3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertEquals(2, saved.total)
        assertEquals(1, saved.currentIndex)
        assertFalse(saved.running)
    }

    @Test
    fun `removeJob DONE removes the job and its result, shifts pointer`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1")),
            ),
        )
        assertTrue(repo(dao).removeJob("1"))

        val saved = dao.get()!!
        assertEquals(listOf("2", "3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertTrue(decodeTestResults(saved.resultsJson).isEmpty())
        assertEquals(2, saved.total)
        assertEquals(0, saved.currentIndex)
    }

    @Test
    fun `removeJob running current is refused`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1")),
            ),
        )
        assertFalse(repo(dao).removeJob("2"))
        assertEquals(0, dao.saves)
    }

    @Test
    fun `removeJob running future keeps pointer, shrinks total`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1")),
            ),
        )
        assertTrue(repo(dao).removeJob("3"))

        val saved = dao.get()!!
        assertEquals(listOf("1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(2, saved.total)
    }

    @Test
    fun `removeJob running past DONE shifts pointer`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 2,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1"), queueResult("2")),
            ),
        )
        assertTrue(repo(dao).removeJob("1"))

        val saved = dao.get()!!
        assertEquals(listOf("2", "3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("2"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(2, saved.total)
    }

    @Test
    fun `removeJob unknown id and null state are no-ops`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 0,
                jobs = listOf(queueJob("1")),
                results = emptyList(),
            ),
        )
        assertFalse(repo(dao).removeJob("nope"))
        assertEquals(0, dao.saves)

        val empty = FakeQueueStateDao(null)
        assertFalse(repo(empty).removeJob("1"))
        assertNull(empty.get())
    }

    @Test
    fun `moveJob idle reorders pending, keeps rest`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 0,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = emptyList(),
            ),
        )
        assertTrue(repo(dao).moveJob("3", 0))

        val saved = dao.get()!!
        assertEquals(listOf("3", "1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(3, saved.total)
        assertEquals(0, saved.currentIndex)
        assertFalse(saved.running)
    }

    @Test
    fun `moveJob DONE is refused`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 0,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = listOf(queueResult("1")),
            ),
        )
        assertFalse(repo(dao).moveJob("1", 1))
        assertEquals(0, dao.saves)
    }

    @Test
    fun `moveJob running pins executing and earlier slots`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3"), queueJob("4")),
                results = listOf(queueResult("1")),
            ),
        )
        val repository = repo(dao)
        // Executing job itself cannot move…
        assertFalse(repository.moveJob("2", 2))
        // …and nothing may land on/before its slot.
        assertFalse(repository.moveJob("3", 0))
        assertEquals(0, dao.saves)

        // Future pending jobs permute freely behind it.
        assertTrue(repository.moveJob("4", 1))
        val saved = dao.get()!!
        assertEquals(listOf("1", "2", "4", "3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(listOf("1"), decodeTestResults(saved.resultsJson).map { it.jobId })
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(4, saved.total)
    }

    @Test
    fun `moveJob to same position is a no-op success`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 0,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = emptyList(),
            ),
        )
        assertTrue(repo(dao).moveJob("1", 0))
        assertEquals(0, dao.saves)
    }

    @Test
    fun `moveJob unknown id and null state are no-ops`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                currentIndex = 0,
                jobs = listOf(queueJob("1")),
                results = emptyList(),
            ),
        )
        assertFalse(repo(dao).moveJob("nope", 0))

        val empty = FakeQueueStateDao(null)
        assertFalse(repo(empty).moveJob("1", 0))
        assertNull(empty.get())
    }

    @Test
    fun `start re-derives stale pointer and freezes batch without DONE`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                // Stale: points at the already-done job instead of job 2.
                currentIndex = 0,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3")),
                results = listOf(queueResult("1")),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.start()

        val saved = dao.get()!!
        assertEquals(1, saved.currentIndex)
        assertEquals(2, saved.batchTotal)
        assertEquals(0, saved.batchDone)
        assertTrue(saved.running)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `start past-the-end pointer self-heals to first pending`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = false,
                // Wedged: pointer past end after an older clear; job 3 stuck.
                currentIndex = 2,
                jobs = listOf(queueJob("1"), queueJob("2")),
                results = listOf(queueResult("1")),
            ),
        )
        val repository = LaunchRecordingRepository(dao)
        repository.start()

        val saved = dao.get()!!
        assertTrue(saved.running)
        assertEquals(1, saved.currentIndex)
        assertEquals(1, saved.batchTotal)
        assertEquals(1, repository.launches.size)
    }

    @Test
    fun `clearCompleted running tracks executing job by id`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 2,
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
            ),
        )
        repo(dao).clearCompleted()

        val saved = dao.get()!!
        // Executing job 3 kept its slot: no slide-down, batch untouched.
        assertEquals(listOf("3", "4", "5"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(0, saved.currentIndex)
        assertTrue(saved.running)
        assertEquals(3, saved.total)
        assertEquals(5, saved.batchTotal)
        assertEquals(2, saved.batchDone)
    }

    @Test
    fun `withResult appends by id and advances batch`() = runTest {
        val state = queueState(
            running = true,
            currentIndex = 1,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
            batchTotal = 2,
            batchDone = 1,
        )
        val updated = state.withResult("2", queueResult("2"), failed = false)!!

        assertEquals(2, updated.currentIndex)
        assertEquals(listOf("1", "2"), decodeTestResults(updated.resultsJson).map { it.jobId })
        assertEquals(2, updated.batchDone)
        assertEquals(2, updated.batchTotal)
        assertTrue(updated.running)
        assertEquals(listOf("1", "2"), decodeTestJobs(updated.jobsJson).map { it.id })
    }

    @Test
    fun `withResult failure stops batch but still counts done`() = runTest {
        val state = queueState(
            running = true,
            currentIndex = 0,
            jobs = listOf(queueJob("1")),
            results = emptyList(),
            batchTotal = 1,
            batchDone = 0,
        )
        val failed = QueueResult(jobId = "1", desc = "desc 1", files = emptyList(), error = "boom")
        val updated = state.withResult("1", failed, failed = true)!!

        assertEquals(1, updated.currentIndex)
        assertEquals(1, updated.batchDone)
        assertEquals("boom", decodeTestResults(updated.resultsJson).single().error)
        assertFalse(updated.running)
    }

    @Test
    fun `withResult ignores duplicates and vanished jobs`() = runTest {
        val dup = queueState(
            running = true,
            currentIndex = 1,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
            batchTotal = 2,
            batchDone = 1,
        )
        assertNull(dup.withResult("1", queueResult("1"), failed = false))

        val truncated = queueState(
            running = true,
            currentIndex = 1,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
        )
        // Job 3 was cleared mid-flight: no resurrection, no pointer move.
        assertNull(truncated.withResult("3", queueResult("3"), failed = false))
    }

    @Test
    fun `record after clearPending keeps truncation and appends current`() = runTest {
        // Running clear dropped the tail; the in-flight job finishes after.
        val afterClear = queueState(
            running = true,
            currentIndex = 1,
            jobs = listOf(queueJob("1"), queueJob("2")),
            results = listOf(queueResult("1")),
            batchTotal = 2,
            batchDone = 1,
        )
        val updated = afterClear.withResult("2", queueResult("2"), failed = false)!!

        assertEquals(listOf("1", "2"), decodeTestJobs(updated.jobsJson).map { it.id })
        assertEquals(listOf("1", "2"), decodeTestResults(updated.resultsJson).map { it.jobId })
        assertEquals(2, updated.currentIndex)
        assertEquals(2, updated.batchDone)
    }

    @Test
    fun `clearPending running shrinks batch to exact close`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 1,
                jobs = listOf(queueJob("1"), queueJob("2"), queueJob("3"), queueJob("4")),
                results = listOf(queueResult("1")),
                batchTotal = 4,
                batchDone = 1,
            ),
        )
        repo(dao).clearPending()

        val saved = dao.get()!!
        assertEquals(listOf("1", "2"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(1, saved.currentIndex)
        // Two upcoming dropped: batch 4 -> 2, closes at 2/2 after job 2.
        assertEquals(2, saved.batchTotal)
        assertEquals(1, saved.batchDone)
        assertTrue(saved.running)
    }

    @Test
    fun `removeJob future shrinks batch, past DONE leaves it`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 2,
                jobs = listOf(
                    queueJob("1"),
                    queueJob("2"),
                    queueJob("3"),
                    queueJob("4"),
                ),
                results = listOf(queueResult("1"), queueResult("2")),
                batchTotal = 4,
                batchDone = 2,
            ),
        )
        val repository = repo(dao)
        assertTrue(repository.removeJob("4"))
        var saved = dao.get()!!
        assertEquals(3, saved.batchTotal)
        assertEquals(2, saved.batchDone)

        assertTrue(repository.removeJob("1"))
        saved = dao.get()!!
        assertEquals(listOf("2", "3"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals(3, saved.batchTotal)
        assertEquals(1, saved.currentIndex)
    }

    @Test
    fun `stage while running grows batch total`() = runTest {
        val dao = FakeQueueStateDao(
            queueState(
                running = true,
                currentIndex = 0,
                jobs = listOf(queueJob("1")),
                results = emptyList(),
                batchTotal = 1,
                batchDone = 0,
            ),
        )
        repo(dao).stage(listOf(queueJob("2")), "queue")

        val saved = dao.get()!!
        assertEquals(2, saved.batchTotal)
        assertEquals(0, saved.batchDone)
        assertEquals(0, saved.currentIndex)
    }
}
