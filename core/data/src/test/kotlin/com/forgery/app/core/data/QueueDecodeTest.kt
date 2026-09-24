package com.forgery.app.core.data

import android.content.Context
import android.content.ContextWrapper
import com.forgery.app.core.database.QueueJobsDao
import com.forgery.app.core.database.QueueJobEntity
import com.forgery.app.core.database.QueueResultsDao
import com.forgery.app.core.database.QueueResultEntity
import com.forgery.app.core.database.QueueStateDao
import com.forgery.app.core.database.QueueStateEntity
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private class DecodeFakeStateDao : QueueStateDao {
    private val state = MutableStateFlow<QueueStateEntity?>(null)
    override fun observe(): Flow<QueueStateEntity?> = state
    override suspend fun get(): QueueStateEntity? = state.value
    override suspend fun save(state: QueueStateEntity) {
        this.state.value = state
    }
    override suspend fun updateJobProgress(value: Float) = Unit
    override suspend fun clear() {
        state.value = null
    }
}

private class DecodeFakeJobsDao(initial: List<QueueJobEntity>) : QueueJobsDao {
    private val rows = MutableStateFlow(initial)
    override fun observeOrdered(): Flow<List<QueueJobEntity>> = rows
    override suspend fun getOrdered(): List<QueueJobEntity> = rows.value
    override suspend fun getById(jobId: String): QueueJobEntity? =
        rows.value.firstOrNull { it.jobId == jobId }
    override suspend fun firstPending(): QueueJobEntity? = rows.value.firstOrNull()
    override suspend fun count(): Int = rows.value.size
    override fun observeCount(): Flow<Int> = rows.map { it.size }
    override suspend fun maxOrder(): Int = rows.value.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun upsertAll(jobs: List<QueueJobEntity>) {
        rows.value = rows.value + jobs
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
    override suspend fun updateOrder(jobId: String, order: Int) = Unit
}

private class DecodeFakeResultsDao(initial: List<QueueResultEntity>) : QueueResultsDao {
    private val rows = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<QueueResultEntity>> = rows
    override suspend fun getAll(): List<QueueResultEntity> = rows.value
    override suspend fun doneIds(): List<String> = rows.value.map { it.jobId }
    override suspend fun getById(jobId: String): QueueResultEntity? =
        rows.value.firstOrNull { it.jobId == jobId }
    override suspend fun insertIgnore(result: QueueResultEntity): Long = -1
    override suspend fun deleteById(jobId: String) = Unit
    override suspend fun deleteByIds(ids: List<String>) = Unit
    override suspend fun clear() {
        rows.value = emptyList()
    }
}

private class DecodeFakeTx : QueueTx {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

private class DecodeFakeConnectionRepo : ConnectionRepository {
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

private class DecodeFakeInputs : QueueInputs {
    override fun saveBase64(jobId: String, initB64: String, maskB64: String?): Pair<String, String?> =
        "/inputs/$jobId/init.png" to null
    override fun loadBase64OrNull(path: String?): String? = null
    override fun deleteJob(jobId: String) = Unit
    override fun sweepOrphans(activeIds: Set<String>) = Unit
}

private class DecodeStubContext : ContextWrapper(null)

private fun decodeStubContext(): Context {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe")
    field.isAccessible = true
    val unsafe = field.get(null)
    val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, DecodeStubContext::class.java) as Context
}

private fun decodeJobEntity(id: String, modulesJson: String) = QueueJobEntity(
    jobId = id,
    sortOrder = 0,
    descr = "desc $id",
    mode = "txt",
    modelTitle = "m",
    payload = "{}",
    modulesJson = modulesJson,
)

private fun decodeResultEntity(id: String, filesJson: String) = QueueResultEntity(
    jobId = id,
    descr = "desc $id",
    filesJson = filesJson,
    error = null,
)

class QueueDecodeTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun repo(
        jobs: List<QueueJobEntity> = emptyList(),
        results: List<QueueResultEntity> = emptyList(),
    ) = DefaultQueueRepository(
        decodeStubContext(),
        DecodeFakeStateDao(),
        DecodeFakeJobsDao(jobs),
        DecodeFakeResultsDao(results),
        DecodeFakeConnectionRepo(),
        DecodeFakeTx(),
        DecodeFakeInputs(),
    )

    @Test
    fun `observeJobs maps broken modulesJson to emptyList without throwing`() = runTest {
        listOf("{broken", "not json", "").forEachIndexed { i, raw ->
            val jobs = repo(jobs = listOf(decodeJobEntity("job-$i", raw)))
                .observeJobs().first()
            assertEquals(emptyList<String>(), jobs.single().additionalModules)
        }
    }

    @Test
    fun `observeJobs decodes valid modulesJson`() = runTest {
        val jobs = repo(jobs = listOf(decodeJobEntity("job-1", "[\"a\",\"b\"]")))
            .observeJobs().first()
        assertEquals(listOf("a", "b"), jobs.single().additionalModules)
    }

    @Test
    fun `observeResults maps broken filesJson to emptyList without throwing`() = runTest {
        listOf("{broken", "not json", "").forEachIndexed { i, raw ->
            val results = repo(results = listOf(decodeResultEntity("job-$i", raw)))
                .observeResults().first()
            assertEquals(emptyList<String>(), results.single().files)
        }
    }

    @Test
    fun `observeResults decodes valid filesJson`() = runTest {
        val results = repo(results = listOf(decodeResultEntity("job-1", "[\"a\",\"b\"]")))
            .observeResults().first()
        assertEquals(listOf("a", "b"), results.single().files)
    }
}
