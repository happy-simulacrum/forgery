package com.forgery.app.core.data

import com.forgery.app.core.database.CollectionDao
import com.forgery.app.core.database.CollectionEntity
import com.forgery.app.core.database.CollectionItemEntity
import com.forgery.app.core.database.CollectionWithCover
import com.forgery.app.core.database.HistoryEntity
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeCollectionDao : CollectionDao {
    private val collections = MutableStateFlow<Map<Long, CollectionEntity>>(emptyMap())
    private val items = MutableStateFlow<List<CollectionItemEntity>>(emptyList())
    private val history = mutableMapOf<Long, HistoryEntity>()
    private var nextId = 1L

    fun seedHistory(ids: List<Long>) {
        ids.forEach { id ->
            history[id] = HistoryEntity(id, "/img$id.png", null, "{}", "today", id)
        }
    }

    fun historyIds(): List<Long> = history.keys.sorted()

    override fun observeCollections(): Flow<List<CollectionEntity>> =
        collections.map { it.values.sortedBy { e -> e.createdAt } }

    override fun observeCollectionById(id: Long): Flow<List<CollectionEntity>> =
        collections.map { listOfNotNull(it[id]) }

    override suspend fun getCollectionById(id: Long): CollectionEntity? = collections.value[id]

    override fun observeCollectionsWithCover(): Flow<List<CollectionWithCover>> =
        collections.map { cols ->
            cols.values.sortedBy { it.createdAt }.map { c ->
                val memberIds = items.value.filter { it.collectionId == c.id }
                    .sortedBy { it.sortOrder }.map { it.historyId }
                val cover = memberIds.firstOrNull()?.let { history[it] }
                CollectionWithCover(c.id, c.name, c.createdAt, memberIds.size, cover?.imagePath, cover?.thumbPath)
            }
        }

    override fun pagingCollectionItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryEntity>> =
        items.map { rows ->
            rows.filter { it.collectionId == collectionId }
                .sortedBy { it.sortOrder }
                .drop(offset).take(limit)
                .mapNotNull { history[it.historyId] }
        }

    override fun observeCollectionIds(collectionId: Long): Flow<List<Long>> =
        items.map { rows ->
            rows.filter { it.collectionId == collectionId }
                .sortedBy { it.sortOrder }.map { it.historyId }
        }

    override fun countInCollection(collectionId: Long): Flow<Int> =
        items.map { rows -> rows.count { it.collectionId == collectionId } }

    override fun pagingUnsorted(limit: Int, offset: Int): Flow<List<HistoryEntity>> =
        items.map { rows ->
            val collected = rows.map { it.historyId }.toSet()
            history.values.filter { it.id !in collected }
                .sortedByDescending { it.createdAt }.drop(offset).take(limit)
        }

    override fun observeUnsortedIds(): Flow<List<Long>> =
        items.map { rows ->
            val collected = rows.map { it.historyId }.toSet()
            history.values.filter { it.id !in collected }
                .sortedByDescending { it.createdAt }.map { it.id }
        }

    override fun countUnsorted(): Flow<Int> =
        items.map { rows ->
            val collected = rows.map { it.historyId }.toSet()
            history.values.count { it.id !in collected }
        }

    override fun observeCollectionsForImage(historyId: Long): Flow<List<CollectionEntity>> =
        items.map { rows ->
            val ids = rows.filter { it.historyId == historyId }.map { it.collectionId }.toSet()
            collections.value.filterKeys { it in ids }.values.toList()
        }

    override suspend fun upsertCollection(item: CollectionEntity): Long {
        val id = if (item.id == 0L) nextId++ else item.id
        collections.value = collections.value + (id to item.copy(id = id))
        return id
    }

    override suspend fun renameCollection(id: Long, name: String) {
        collections.value = collections.value + (id to collections.value.getValue(id).copy(name = name))
    }

    override suspend fun deleteCollection(id: Long) {
        collections.value = collections.value - id
    }

    override suspend fun clearCollection(collectionId: Long) {
        items.value = items.value.filterNot { it.collectionId == collectionId }
    }

    override suspend fun addItems(items: List<CollectionItemEntity>): List<Long> {
        val current = this.items.value.toMutableList()
        val result = items.map { row ->
            if (current.any { it.collectionId == row.collectionId && it.historyId == row.historyId }) {
                -1L
            } else {
                current.add(row)
                1L
            }
        }
        this.items.value = current
        return result
    }

    override suspend fun maxOrder(collectionId: Long): Int =
        items.value.filter { it.collectionId == collectionId }.maxOfOrNull { it.sortOrder } ?: -1

    override suspend fun removeItems(collectionId: Long, historyIds: List<Long>) {
        items.value = items.value.filterNot { it.collectionId == collectionId && it.historyId in historyIds }
    }

    override suspend fun removeItem(collectionId: Long, historyId: Long) {
        items.value = items.value.filterNot { it.collectionId == collectionId && it.historyId == historyId }
    }

    override suspend fun updateOrder(collectionId: Long, historyId: Long, order: Int) {
        items.value = items.value.map {
            if (it.collectionId == collectionId && it.historyId == historyId) it.copy(sortOrder = order) else it
        }
    }

    override suspend fun clearAll() {
        collections.value = emptyMap()
        items.value = emptyList()
    }
}

private class FakeCollectionTx : QueueTx {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class CollectionRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun repo(dao: FakeCollectionDao = FakeCollectionDao()) =
        OfflineFirstCollectionRepository(dao, FakeCollectionTx())

    @Test
    fun `create with initial ids preserves order`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L, 3L))
        val id = repo(dao).createCollection("Trip", listOf(1L, 2L, 3L))

        assertEquals(listOf(1L, 2L, 3L), repo(dao).observeIds(id).first())
        assertEquals(3, repo(dao).countInCollection(id).first())
    }

    @Test
    fun `add appends after max and ignores duplicates`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L, 3L, 4L))
        val r = repo(dao)
        val id = r.createCollection("Trip", listOf(1L, 2L))

        r.addToCollection(id, listOf(2L, 3L, 4L))

        assertEquals(listOf(1L, 2L, 3L, 4L), r.observeIds(id).first())
    }

    @Test
    fun `image can live in several collections`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L))
        val r = repo(dao)
        val a = r.createCollection("A", listOf(1L))
        val b = r.createCollection("B", listOf(1L, 2L))

        assertEquals(listOf(1L), r.observeIds(a).first())
        assertEquals(listOf(1L, 2L), r.observeIds(b).first())
        assertEquals(2, r.observeCollectionsForImage(1L).first().size)
    }

    @Test
    fun `reorder persists new order`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L, 3L))
        val r = repo(dao)
        val id = r.createCollection("Trip", listOf(1L, 2L, 3L))

        r.reorder(id, listOf(3L, 1L, 2L))

        assertEquals(listOf(3L, 1L, 2L), r.observeIds(id).first())
    }

    @Test
    fun `remove keeps other members`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L))
        val r = repo(dao)
        val id = r.createCollection("Trip", listOf(1L, 2L))

        r.removeFromCollection(id, listOf(1L))

        assertEquals(listOf(2L), r.observeIds(id).first())
        assertEquals(listOf(1L, 2L), dao.historyIds())
    }

    @Test
    fun `delete collection keeps history rows`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L))
        val r = repo(dao)
        val id = r.createCollection("Trip", listOf(1L, 2L))

        r.deleteCollection(id)

        assertTrue(r.observeCollections().first().isEmpty())
        assertEquals(listOf(1L, 2L), dao.historyIds())
        assertEquals(2, r.countUnsorted().first())
    }

    @Test
    fun `unsorted excludes collected images`() = runTest {
        val dao = FakeCollectionDao()
        dao.seedHistory(listOf(1L, 2L, 3L))
        val r = repo(dao)
        r.createCollection("Trip", listOf(1L))

        assertEquals(2, r.countUnsorted().first())
        assertEquals(listOf(3L, 2L), r.observeUnsortedIds().first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank name is rejected`() = runTest {
        repo().createCollection("   ")
    }
}
