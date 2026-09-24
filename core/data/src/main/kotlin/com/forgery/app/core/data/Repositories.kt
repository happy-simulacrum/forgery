package com.forgery.app.core.data

import android.content.Context
import android.util.Log
import com.forgery.app.core.database.HistoryDao
import com.forgery.app.core.database.HistoryEntity
import com.forgery.app.core.database.QueueTx
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.model.UiPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
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

private const val HistoryLogTag = "ForgeryHistory"

/** Orphans younger than this are kept: the worker writes the file before the history row. */
private const val OrphanAgeMs = 20L * 60 * 1000

@Singleton
class OfflineFirstHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
    @ApplicationContext private val context: Context,
    private val tx: QueueTx,
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

    override suspend fun delete(ids: List<Long>) {
        // Empty IN () is rejected by SQLite — no-op before touching the DAO.
        if (ids.isEmpty()) return
        // Files first: rows stay the source of truth for the trailing sweep.
        deleteFiles(dao.getPathsByIds(ids).flatMap { listOf(it.imagePath, it.thumbPath) })
        tx.run { dao.deleteByIds(ids) }
        sweepOrphanFiles()
    }

    override suspend fun clear() {
        deleteFiles(dao.getAllPaths().flatMap { listOf(it.imagePath, it.thumbPath) })
        tx.run { dao.clear() }
        sweepOrphanFiles()
    }

    /**
     * Best-effort orphan sweep over filesDir/native_queue: deletes files no
     * history row references, gated by age so in-flight worker output
     * (file written before its history row) survives. Never throws.
     */
    suspend fun sweepOrphanFiles() {
        try {
            val referenced = dao.getAllPaths()
                .flatMap { listOf(it.imagePath, it.thumbPath) }
                .mapNotNull { raw ->
                    if (raw.isNullOrBlank()) null
                    else try {
                        File(raw).name
                    } catch (_: Exception) {
                        null
                    }
                }.toSet()
            withContext(Dispatchers.IO) {
                val dir = queueDir()
                val files = try {
                    dir.listFiles()
                } catch (_: Exception) {
                    null
                } ?: return@withContext
                val now = System.currentTimeMillis()
                files.forEach { f ->
                    try {
                        if (!f.isFile) return@forEach
                        if (f.name in referenced) return@forEach
                        if (now - f.lastModified() < OrphanAgeMs) return@forEach
                        f.delete()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(HistoryLogTag, "history sweep failed", e)
        }
    }

    private fun queueDir(): File = File(context.filesDir, "native_queue")

    private suspend fun deleteFiles(paths: List<String?>) = withContext(Dispatchers.IO) {
        val dir = queueDir()
        val dirPath = try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absolutePath
        }
        paths.forEach { raw ->
            if (raw.isNullOrBlank()) return@forEach
            try {
                val f = File(raw)
                val canonical = f.canonicalPath
                // Prefix guard: never delete outside filesDir/native_queue.
                if (canonical != dirPath && !canonical.startsWith(dirPath + File.separatorChar)) return@forEach
                f.delete()
            } catch (_: Exception) {
            }
        }
    }
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
