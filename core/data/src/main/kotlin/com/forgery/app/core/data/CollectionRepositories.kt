package com.forgery.app.core.data

import com.forgery.app.core.database.CollectionDao
import com.forgery.app.core.database.CollectionEntity
import com.forgery.app.core.database.CollectionItemEntity
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.model.GalleryCollection
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.model.UNSORTED_COLLECTION_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface CollectionRepository {
    fun observeCollections(): Flow<List<GalleryCollection>>
    fun observeCollection(collectionId: Long): Flow<GalleryCollection?>
    fun observeItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryItem>>
    fun observeIds(collectionId: Long): Flow<List<Long>>
    fun countInCollection(collectionId: Long): Flow<Int>
    fun observeUnsorted(limit: Int, offset: Int): Flow<List<HistoryItem>>
    fun observeUnsortedIds(): Flow<List<Long>>
    fun countUnsorted(): Flow<Int>
    fun observeCollectionsForImage(historyId: Long): Flow<List<GalleryCollection>>
    suspend fun createCollection(name: String, initialIds: List<Long> = emptyList()): Long
    suspend fun rename(collectionId: Long, name: String)
    /** Deletes grouping rows only — history rows and files stay untouched. */
    suspend fun deleteCollection(collectionId: Long)
    suspend fun addToCollection(collectionId: Long, historyIds: List<Long>)
    suspend fun removeFromCollection(collectionId: Long, historyIds: List<Long>)
    suspend fun reorder(collectionId: Long, orderedIds: List<Long>)
}

@Singleton
class OfflineFirstCollectionRepository @Inject constructor(
    private val dao: CollectionDao,
    private val tx: QueueTx,
) : CollectionRepository {
    override fun observeCollections(): Flow<List<GalleryCollection>> =
        dao.observeCollectionsWithCover().map { list ->
            list.map {
                val coverImagePath = it.coverImagePath
                GalleryCollection(
                    id = it.id,
                    name = it.name,
                    createdAt = it.createdAt,
                    count = it.count,
                    cover = if (coverImagePath != null) {
                        HistoryItem(0, coverImagePath, it.coverThumbPath, "", "")
                    } else {
                        null
                    },
                )
            }
        }

    override fun observeCollection(collectionId: Long): Flow<GalleryCollection?> =
        if (collectionId == UNSORTED_COLLECTION_ID) {
            countUnsorted().map { GalleryCollection(UNSORTED_COLLECTION_ID, "Unsorted", 0, it, null) }
        } else {
            combine(
                dao.observeCollectionById(collectionId),
                dao.countInCollection(collectionId),
                dao.pagingCollectionItems(collectionId, 1, 0),
            ) { rows, count, first ->
                val row = rows.firstOrNull() ?: return@combine null
                GalleryCollection(
                    id = row.id,
                    name = row.name,
                    createdAt = row.createdAt,
                    count = count,
                    cover = first.firstOrNull()?.toCollectionItem(),
                )
            }
        }

    override fun observeItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryItem>> =
        if (collectionId == UNSORTED_COLLECTION_ID) {
            observeUnsorted(limit, offset)
        } else {
            dao.pagingCollectionItems(collectionId, limit, offset).map { list ->
                list.map { it.toCollectionItem() }
            }
        }

    override fun observeIds(collectionId: Long): Flow<List<Long>> =
        if (collectionId == UNSORTED_COLLECTION_ID) dao.observeUnsortedIds()
        else dao.observeCollectionIds(collectionId)

    override fun countInCollection(collectionId: Long): Flow<Int> =
        if (collectionId == UNSORTED_COLLECTION_ID) dao.countUnsorted()
        else dao.countInCollection(collectionId)

    override fun observeUnsorted(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        dao.pagingUnsorted(limit, offset).map { list -> list.map { it.toCollectionItem() } }

    override fun observeUnsortedIds(): Flow<List<Long>> = dao.observeUnsortedIds()

    override fun countUnsorted(): Flow<Int> = dao.countUnsorted()

    override fun observeCollectionsForImage(historyId: Long): Flow<List<GalleryCollection>> =
        dao.observeCollectionsForImage(historyId).map { list ->
            list.map { GalleryCollection(it.id, it.name, it.createdAt) }
        }

    override suspend fun createCollection(name: String, initialIds: List<Long>): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Collection name must not be blank" }
        var id = 0L
        tx.run {
            id = dao.upsertCollection(CollectionEntity(name = trimmed))
            if (initialIds.isNotEmpty()) {
                appendItems(id, initialIds.distinct())
            }
        }
        return id
    }

    override suspend fun rename(collectionId: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Collection name must not be blank" }
        dao.renameCollection(collectionId, trimmed)
    }

    override suspend fun deleteCollection(collectionId: Long) {
        if (collectionId == UNSORTED_COLLECTION_ID) return
        tx.run {
            dao.clearCollection(collectionId)
            dao.deleteCollection(collectionId)
        }
    }

    override suspend fun addToCollection(collectionId: Long, historyIds: List<Long>) {
        if (collectionId == UNSORTED_COLLECTION_ID) return
        if (historyIds.isEmpty()) return
        tx.run { appendItems(collectionId, historyIds.distinct()) }
    }

    override suspend fun removeFromCollection(collectionId: Long, historyIds: List<Long>) {
        if (collectionId == UNSORTED_COLLECTION_ID) return
        if (historyIds.isEmpty()) return
        dao.removeItems(collectionId, historyIds.distinct())
    }

    override suspend fun reorder(collectionId: Long, orderedIds: List<Long>) {
        if (collectionId == UNSORTED_COLLECTION_ID) return
        tx.run {
            orderedIds.distinct().forEachIndexed { index, historyId ->
                dao.updateOrder(collectionId, historyId, index)
            }
        }
    }

    private suspend fun appendItems(collectionId: Long, historyIds: List<Long>) {
        var order = dao.maxOrder(collectionId) + 1
        val rows = historyIds.map { historyId ->
            CollectionItemEntity(collectionId, historyId, order++)
        }
        dao.addItems(rows)
    }
}

private fun com.forgery.app.core.database.HistoryEntity.toCollectionItem() =
    HistoryItem(id, imagePath, thumbPath, paramsJson, date)
