package com.forgery.app.core.data

import android.content.Context
import android.content.ContextWrapper
import com.forgery.app.core.database.HistoryDao
import com.forgery.app.core.database.HistoryEntity
import com.forgery.app.core.database.HistoryPaths
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakeHistoryDao : HistoryDao {
    private val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())
    private var nextId = 1L
    var deleteByIdsCalls = 0

    override fun pagingHistory(limit: Int, offset: Int): Flow<List<HistoryEntity>> =
        rows.map { it.drop(offset).take(limit) }

    override fun observeById(id: Long): Flow<List<HistoryEntity>> =
        rows.map { list -> list.filter { it.id == id } }

    override fun observeIds(): Flow<List<Long>> =
        rows.map { list -> list.map { it.id } }

    override fun count(): Flow<Int> = rows.map { it.size }

    override suspend fun upsert(item: HistoryEntity): Long {
        val id = if (item.id == 0L) nextId++ else item.id
        rows.value = rows.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        deleteByIdsCalls++
        rows.value = rows.value.filterNot { it.id in ids }
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }

    override suspend fun getPathsByIds(ids: List<Long>): List<HistoryPaths> =
        rows.value.filter { it.id in ids }.map { HistoryPaths(it.imagePath, it.thumbPath) }

    override suspend fun getAllPaths(): List<HistoryPaths> =
        rows.value.map { HistoryPaths(it.imagePath, it.thumbPath) }

    suspend fun seed(imagePath: String, thumbPath: String?): Long =
        upsert(HistoryEntity(imagePath = imagePath, thumbPath = thumbPath, paramsJson = "{}", date = "d"))

    fun ids(): List<Long> = rows.value.map { it.id }
}

private class FakeHistoryTx : QueueTx {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

private class StubHistoryContext : ContextWrapper(null) {
    lateinit var filesDirOverride: File
    override fun getFilesDir(): File = filesDirOverride
}

/** Allocates the Context stub without running framework constructors (stub android.jar). */
private fun stubHistoryContext(filesDir: File): Context {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe")
    field.isAccessible = true
    val unsafe = field.get(null)
    val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    val ctx = allocate.invoke(unsafe, StubHistoryContext::class.java) as StubHistoryContext
    ctx.filesDirOverride = filesDir
    return ctx
}

class OfflineFirstHistoryRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var queueDir: File
    private lateinit var dao: FakeHistoryDao
    private lateinit var repo: OfflineFirstHistoryRepository

    @Before
    fun setUp() {
        queueDir = File(temp.root, "native_queue").also { it.mkdirs() }
        dao = FakeHistoryDao()
        repo = OfflineFirstHistoryRepository(dao, stubHistoryContext(temp.root), FakeHistoryTx())
    }

    private fun queueFile(name: String): File =
        File(queueDir, name).also { it.writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `delete removes rows and their image and thumb files`() = runTest {
        val img = queueFile("a.png")
        val thumb = queueFile("thumb_a.jpg")
        val other = queueFile("b.png")
        val id = dao.seed(img.absolutePath, thumb.absolutePath)
        val otherId = dao.seed(other.absolutePath, null)

        repo.delete(listOf(id))

        assertEquals(listOf(otherId), dao.ids())
        assertFalse(img.exists())
        assertFalse(thumb.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `delete tolerates missing files and null thumb`() = runTest {
        val id = dao.seed(File(queueDir, "gone.png").absolutePath, null)

        repo.delete(listOf(id))

        assertTrue(dao.ids().isEmpty())
    }

    @Test
    fun `delete with empty list is a no-op`() = runTest {
        val img = queueFile("a.png")
        val id = dao.seed(img.absolutePath, null)

        repo.delete(emptyList())

        assertEquals(listOf(id), dao.ids())
        assertEquals(0, dao.deleteByIdsCalls)
        assertTrue(img.exists())
    }

    @Test
    fun `clear removes all rows and files`() = runTest {
        val img1 = queueFile("a.png")
        val thumb1 = queueFile("thumb_a.jpg")
        val img2 = queueFile("b.png")
        dao.seed(img1.absolutePath, thumb1.absolutePath)
        dao.seed(img2.absolutePath, null)

        repo.clear()

        assertTrue(dao.ids().isEmpty())
        assertFalse(img1.exists())
        assertFalse(thumb1.exists())
        assertFalse(img2.exists())
    }

    @Test
    fun `delete never escapes the queue dir`() = runTest {
        val outside = File(temp.root, "outside.png").also { it.writeBytes(byteArrayOf(9)) }
        val id = dao.seed(outside.absolutePath, null)

        repo.delete(listOf(id))

        assertTrue(dao.ids().isEmpty())
        assertTrue(outside.exists())
    }

    @Test
    fun `sweep deletes only old unreferenced files`() = runTest {
        val keep = queueFile("keep.png")
        dao.seed(keep.absolutePath, null)
        val oldOrphan = queueFile("old.png").also {
            it.setLastModified(System.currentTimeMillis() - 21L * 60 * 1000)
        }
        val freshOrphan = queueFile("fresh.png").also {
            it.setLastModified(System.currentTimeMillis())
        }

        repo.sweepOrphanFiles()

        assertFalse(oldOrphan.exists())
        assertTrue(freshOrphan.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `sweep keeps referenced thumbs and missing dir is a no-op`() = runTest {
        val thumb = queueFile("thumb_a.jpg")
        dao.seed(File(queueDir, "a.png").absolutePath, thumb.absolutePath)

        repo.sweepOrphanFiles()

        assertTrue(thumb.exists())
    }
}
