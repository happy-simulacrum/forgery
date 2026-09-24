package com.forgery.app.core.data

import com.forgery.app.core.database.HistoryDao
import com.forgery.app.core.database.HistoryEntity
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.model.UiPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface HistoryRepository {
    fun observePage(limit: Int, offset: Int): Flow<List<HistoryItem>>
    fun observeDetail(id: Long): Flow<HistoryItem?> = flowOf(null)
    /** Ordered ids (newest first) backing the detail pager. */
    fun observeIds(): Flow<List<Long>> = flowOf(emptyList())
    fun count(): Flow<Int>
    suspend fun add(imagePath: String, thumbPath: String?, paramsJson: String, date: String): Long
    suspend fun delete(ids: List<Long>)
    suspend fun clear()
}

@Singleton
class OfflineFirstHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
) : HistoryRepository {
    override fun observePage(limit: Int, offset: Int) =
        dao.pagingHistory(limit, offset).map { list ->
            list.map { it.toItem() }
        }
    override fun observeDetail(id: Long): Flow<HistoryItem?> =
        dao.observeById(id).map { it.firstOrNull()?.toItem() }
    override fun observeIds(): Flow<List<Long>> = dao.observeIds()
    override fun count() = dao.count()
    override suspend fun add(imagePath: String, thumbPath: String?, paramsJson: String, date: String) =
        dao.upsert(HistoryEntity(imagePath = imagePath, thumbPath = thumbPath, paramsJson = paramsJson, date = date))
    override suspend fun delete(ids: List<Long>) = dao.deleteByIds(ids)
    override suspend fun clear() = dao.clear()
}

private fun HistoryEntity.toItem() = HistoryItem(id, imagePath, thumbPath, paramsJson, date)

interface ConnectionRepository {
    fun observe(): Flow<ConnectionConfig>
    suspend fun save(config: ConnectionConfig)
    fun observeUiPrefs(): Flow<UiPrefs>
    suspend fun saveUiPrefs(prefs: UiPrefs)
    suspend fun reset()
}

@Singleton
class DefaultConnectionRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : ConnectionRepository {
    override fun observe() = prefs.connectionConfig
    override suspend fun save(config: ConnectionConfig) = prefs.saveConnection(config)
    override fun observeUiPrefs() = prefs.uiPrefs
    override suspend fun saveUiPrefs(uiPrefs: UiPrefs) = prefs.saveUiPrefs(uiPrefs)
    override suspend fun reset() = prefs.resetConnection()
}
