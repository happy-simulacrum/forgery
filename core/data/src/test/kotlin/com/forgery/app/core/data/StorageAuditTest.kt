package com.forgery.app.core.data

import android.content.Context
import android.content.ContextWrapper
import com.forgery.app.core.database.HistoryDao
import com.forgery.app.core.database.HistoryEntity
import com.forgery.app.core.database.HistoryPaths
import com.forgery.app.core.database.QueueJobEntity
import com.forgery.app.core.database.QueueJobsDao
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class AuditFakeHistoryDao(
    private var rows: List<HistoryEntity> = emptyList(),
) : HistoryDao {
    override fun pagingHistory(limit: Int, offset: Int): Flow<List<HistoryEntity>> =
        flowOf(rows.drop(offset).take(limit))

    override fun observeById(id: Long): Flow<List<HistoryEntity>> =
        flowOf(rows.filter { it.id == id })

    override fun observeIds(): Flow<List<Long>> = flowOf(rows.map { it.id })

    override fun count(): Flow<Int> = flowOf(rows.size)

    override suspend fun upsert(item: HistoryEntity): Long = 0L

    override suspend fun deleteByIds(ids: List<Long>) = Unit

    override suspend fun clear() = Unit

    override suspend fun getPathsByIds(ids: List<Long>): List<HistoryPaths> =
        rows.filter { it.id in ids }.map { HistoryPaths(it.imagePath, it.thumbPath) }

    override suspend fun getAllPaths(): List<HistoryPaths> =
        rows.map { HistoryPaths(it.imagePath, it.thumbPath) }
}

private class AuditFakeJobsDao(
    private var jobs: List<QueueJobEntity> = emptyList(),
) : QueueJobsDao {
    override fun observeOrdered(): Flow<List<QueueJobEntity>> = flowOf(jobs)

    override suspend fun getOrdered(): List<QueueJobEntity> = jobs

    override suspend fun getById(jobId: String): QueueJobEntity? =
        jobs.firstOrNull { it.jobId == jobId }

    override suspend fun firstPending(): QueueJobEntity? = null

    override suspend fun count(): Int = jobs.size

    override fun observeCount(): Flow<Int> = flowOf(jobs.size)

    override suspend fun maxOrder(): Int = jobs.size - 1

    override suspend fun upsertAll(jobs: List<QueueJobEntity>) = Unit

    override suspend fun deleteById(jobId: String) = Unit

    override suspend fun deleteByIds(ids: List<String>) = Unit

    override suspend fun clear() = Unit

    override suspend fun updateOrder(jobId: String, order: Int) = Unit
}

private class AuditStubContext : ContextWrapper(null) {
    lateinit var filesDirOverride: File
    override fun getFilesDir(): File = filesDirOverride
}

private fun auditContext(filesDir: File): Context {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe")
    field.isAccessible = true
    val unsafe = field.get(null)
    val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    val ctx = allocate.invoke(unsafe, AuditStubContext::class.java) as AuditStubContext
    ctx.filesDirOverride = filesDir
    return ctx
}

private fun jobEntity(id: String) = QueueJobEntity(
    jobId = id,
    sortOrder = 0,
    descr = "d",
    mode = "t2i",
    modelTitle = "m",
    payload = "{}",
)

class StorageAuditTest {

    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var queueDir: File
    private lateinit var inputsDir: File

    @Before
    fun setUp() {
        queueDir = File(temp.root, "native_queue").also { it.mkdirs() }
        inputsDir = File(temp.root, "queue_inputs").also { it.mkdirs() }
    }

    private fun audit(
        rows: List<HistoryEntity> = emptyList(),
        jobs: List<QueueJobEntity> = emptyList(),
    ) = StorageAudit(auditContext(temp.root), AuditFakeHistoryDao(rows), AuditFakeJobsDao(jobs))

    @Test
    fun `collect splits originals and thumbs and reports top files`() = runTest {
        File(queueDir, "a.png").writeBytes(ByteArray(1000))
        File(queueDir, "b.png").writeBytes(ByteArray(3000))
        File(queueDir, "thumb_a.jpg").writeBytes(ByteArray(200))

        val report = audit().collect()

        assertEquals(3, report.queueTotal.files)
        assertEquals(4200L, report.queueTotal.bytes)
        assertEquals(2, report.originals.files)
        assertEquals(4000L, report.originals.bytes)
        assertEquals(1, report.thumbs.files)
        assertEquals(200L, report.thumbs.bytes)
        assertEquals("b.png", report.topQueueFiles.first().name)
        // Read-only: everything still on disk.
        assertEquals(3, queueDir.listFiles()!!.size)
    }

    @Test
    fun `collect splits queue inputs into active and orphan jobs`() = runTest {
        val active = File(inputsDir, "job-active").also { it.mkdirs() }
        File(active, "init.png").writeBytes(ByteArray(5000))
        val orphan = File(inputsDir, "job-old").also { it.mkdirs() }
        File(orphan, "init.png").writeBytes(ByteArray(7000))
        File(orphan, "mask.png").writeBytes(ByteArray(1000))

        val report = audit(jobs = listOf(jobEntity("job-active"))).collect()

        assertEquals(2, report.inputs.jobDirs)
        assertEquals(3, report.inputs.totalFiles)
        assertEquals(13000L, report.inputs.totalBytes)
        assertEquals(1, report.inputs.activeJobs)
        assertEquals(5000L, report.inputs.activeBytes)
        assertEquals(1, report.inputs.orphanJobs)
        assertEquals(8000L, report.inputs.orphanBytes)
        // Read-only: orphan files untouched.
        assertTrue(File(orphan, "init.png").exists())
    }

    @Test
    fun `collect on empty dirs reports zeros`() = runTest {
        val report = audit().collect()

        assertEquals(0, report.queueTotal.files)
        assertEquals(0L, report.queueTotal.bytes)
        assertEquals(0, report.inputs.jobDirs)
        assertEquals(0, report.historyCount)
        assertTrue(report.topQueueFiles.isEmpty())
    }

    @Test
    fun `collect reports history rows and files top level`() = runTest {
        val img = File(queueDir, "a.png").also { it.writeBytes(ByteArray(10)) }
        val rows = listOf(
            HistoryEntity(id = 1, imagePath = img.absolutePath, thumbPath = null, paramsJson = "{}", date = "d"),
        )

        val report = audit(rows = rows).collect()

        assertEquals(1, report.historyCount)
        assertEquals(2, report.historyPaths)
        assertTrue(report.filesTopLevel.any { it.name == "native_queue" && it.bytes == 10L })
        assertTrue(report.filesTopLevel.any { it.name == "queue_inputs" })
    }
}
