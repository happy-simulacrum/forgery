package com.forgery.app.core.data

import android.content.Context
import android.content.ContextWrapper
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueStateEntity
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
    override suspend fun clear() {
        state.value = null
    }
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
) = QueueStateEntity(
    running = running,
    currentIndex = currentIndex,
    total = jobs.size,
    origin = "single",
    host = "http://127.0.0.1:7860",
    jobsJson = TestQueueJson.encodeToString(ListSerializer(QueueJob.serializer()), jobs),
    resultsJson = TestQueueJson.encodeToString(ListSerializer(QueueResult.serializer()), results),
)

private fun decodeTestJobs(raw: String): List<QueueJob> =
    TestQueueJson.decodeFromString(ListSerializer(QueueJob.serializer()), raw)

private fun decodeTestResults(raw: String): List<QueueResult> =
    TestQueueJson.decodeFromString(ListSerializer(QueueResult.serializer()), raw)

class QueueRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun repo(dao: FakeQueueStateDao) =
        DefaultQueueRepository(stubContext(), dao, FakeQueueConnectionRepo())

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
        assertEquals(1, saved.currentIndex)
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
    fun `stage while idle overwrites with running false`() = runTest {
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
        assertEquals(listOf("9"), decodeTestJobs(saved.jobsJson).map { it.id })
        assertEquals("[]", saved.resultsJson)
        assertEquals(1, saved.total)
        assertEquals(0, saved.currentIndex)
        assertFalse(saved.running)
        assertEquals("queue", saved.origin)
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
}
