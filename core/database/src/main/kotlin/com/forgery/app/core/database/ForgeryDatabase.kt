package com.forgery.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,
    val thumbPath: String?,
    val paramsJson: String,
    val date: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "comfy_templates")
data class ComfyTemplateEntity(
    @PrimaryKey val name: String,
    val workflowJson: String,
)

@Entity(tableName = "styles")
data class StyleEntity(
    @PrimaryKey val name: String,
    val prompt: String,
    val negativePrompt: String,
)

@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 1,
    val running: Boolean,
    val executingJobId: String?,
    val origin: String,
    val host: String,
    val jobProgress: Float = 0f,
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
)

@Entity(
    tableName = "queue_jobs",
    indices = [androidx.room.Index(value = ["sortOrder"])],
)
data class QueueJobEntity(
    @PrimaryKey val jobId: String,
    val sortOrder: Int,
    val descr: String,
    val mode: String,
    val modelTitle: String,
    val payload: String,
    val modulesJson: String = "[]",
    val initImagePath: String? = null,
    val maskPath: String? = null,
)

@Entity(tableName = "queue_results")
data class QueueResultEntity(
    @PrimaryKey val jobId: String,
    val descr: String,
    val filesJson: String = "[]",
    val error: String? = null,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "historyId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = HistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [androidx.room.Index(value = ["collectionId"]), androidx.room.Index(value = ["historyId"])],
)
data class CollectionItemEntity(
    val collectionId: Long,
    val historyId: Long,
    val sortOrder: Int,
)

/** Collection with aggregated count + cover paths (first item by sortOrder). */
data class CollectionWithCover(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val count: Int,
    val coverImagePath: String?,
    val coverThumbPath: String?,
)

/** Lightweight row for gallery file cleanup — avoids loading full entities. */
data class HistoryPaths(
    val imagePath: String,
    val thumbPath: String?,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun pagingHistory(limit: Int, offset: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<List<HistoryEntity>>

    /** Ordered ids (newest first) for the detail pager — avoids loading full rows. */
    @Query("SELECT id FROM history ORDER BY createdAt DESC")
    fun observeIds(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM history")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT imagePath, thumbPath FROM history WHERE id IN (:ids)")
    suspend fun getPathsByIds(ids: List<Long>): List<HistoryPaths>

    @Query("SELECT imagePath, thumbPath FROM history")
    suspend fun getAllPaths(): List<HistoryPaths>
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY createdAt ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeCollectionById(id: Long): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getCollectionById(id: Long): CollectionEntity?

    @Query(
        "SELECT c.id AS id, c.name AS name, c.createdAt AS createdAt, " +
            "COUNT(ci.historyId) AS count, " +
            "(SELECT h.thumbPath FROM collection_items ci2 JOIN history h ON h.id = ci2.historyId " +
            "WHERE ci2.collectionId = c.id ORDER BY ci2.sortOrder ASC LIMIT 1) AS coverThumbPath, " +
            "(SELECT h.imagePath FROM collection_items ci3 JOIN history h ON h.id = ci3.historyId " +
            "WHERE ci3.collectionId = c.id ORDER BY ci3.sortOrder ASC LIMIT 1) AS coverImagePath " +
            "FROM collections c LEFT JOIN collection_items ci ON ci.collectionId = c.id " +
            "GROUP BY c.id ORDER BY c.createdAt ASC",
    )
    fun observeCollectionsWithCover(): Flow<List<CollectionWithCover>>

    @Query(
        "SELECT h.* FROM history h JOIN collection_items ci ON ci.historyId = h.id " +
            "WHERE ci.collectionId = :collectionId ORDER BY ci.sortOrder ASC LIMIT :limit OFFSET :offset",
    )
    fun pagingCollectionItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryEntity>>

    @Query(
        "SELECT h.id FROM history h JOIN collection_items ci ON ci.historyId = h.id " +
            "WHERE ci.collectionId = :collectionId ORDER BY ci.sortOrder ASC",
    )
    fun observeCollectionIds(collectionId: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM collection_items WHERE collectionId = :collectionId")
    fun countInCollection(collectionId: Long): Flow<Int>

    @Query(
        "SELECT h.* FROM history h LEFT JOIN collection_items ci ON ci.historyId = h.id " +
            "WHERE ci.historyId IS NULL ORDER BY h.createdAt DESC LIMIT :limit OFFSET :offset",
    )
    fun pagingUnsorted(limit: Int, offset: Int): Flow<List<HistoryEntity>>

    @Query(
        "SELECT h.id FROM history h LEFT JOIN collection_items ci ON ci.historyId = h.id " +
            "WHERE ci.historyId IS NULL ORDER BY h.createdAt DESC",
    )
    fun observeUnsortedIds(): Flow<List<Long>>

    @Query(
        "SELECT COUNT(*) FROM history h LEFT JOIN collection_items ci ON ci.historyId = h.id " +
            "WHERE ci.historyId IS NULL",
    )
    fun countUnsorted(): Flow<Int>

    @Query("SELECT c.* FROM collections c JOIN collection_items ci ON ci.collectionId = c.id WHERE ci.historyId = :historyId")
    fun observeCollectionsForImage(historyId: Long): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(item: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId")
    suspend fun clearCollection(collectionId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<CollectionItemEntity>): List<Long>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM collection_items WHERE collectionId = :collectionId")
    suspend fun maxOrder(collectionId: Long): Int

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND historyId IN (:historyIds)")
    suspend fun removeItems(collectionId: Long, historyIds: List<Long>)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND historyId = :historyId")
    suspend fun removeItem(collectionId: Long, historyId: Long)

    @Query("UPDATE collection_items SET sortOrder = :order WHERE collectionId = :collectionId AND historyId = :historyId")
    suspend fun updateOrder(collectionId: Long, historyId: Long, order: Int)

    @Query("DELETE FROM collections")
    suspend fun clearAll()
}

@Dao
interface ComfyTemplateDao {
    @Query("SELECT * FROM comfy_templates ORDER BY name")
    fun observeAll(): Flow<List<ComfyTemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ComfyTemplateEntity)

    @Query("DELETE FROM comfy_templates WHERE name = :name")
    suspend fun delete(name: String)
}

@Dao
interface StyleDao {
    @Query("SELECT * FROM styles ORDER BY name")
    fun observeAll(): Flow<List<StyleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StyleEntity)

    @Query("DELETE FROM styles WHERE name = :name")
    suspend fun delete(name: String)
}

@Dao
interface QueueStateDao {
    @Query("SELECT * FROM queue_state WHERE id = 1")
    fun observe(): Flow<QueueStateEntity?>

    @Query("SELECT * FROM queue_state WHERE id = 1")
    suspend fun get(): QueueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: QueueStateEntity)

    @Query("DELETE FROM queue_state")
    suspend fun clear()

    @Query("UPDATE queue_state SET jobProgress = :value WHERE id = 1 AND ABS(jobProgress - :value) > 0.01")
    suspend fun updateJobProgress(value: Float)
}

@Dao
interface QueueJobsDao {
    @Query("SELECT * FROM queue_jobs ORDER BY sortOrder")
    fun observeOrdered(): Flow<List<QueueJobEntity>>

    @Query("SELECT * FROM queue_jobs ORDER BY sortOrder")
    suspend fun getOrdered(): List<QueueJobEntity>

    @Query("SELECT * FROM queue_jobs WHERE jobId = :jobId")
    suspend fun getById(jobId: String): QueueJobEntity?

    @Query("SELECT * FROM queue_jobs WHERE jobId NOT IN (SELECT jobId FROM queue_results) ORDER BY sortOrder LIMIT 1")
    suspend fun firstPending(): QueueJobEntity?

    @Query("SELECT COUNT(*) FROM queue_jobs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM queue_jobs")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM queue_jobs")
    suspend fun maxOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(jobs: List<QueueJobEntity>)

    @Query("DELETE FROM queue_jobs WHERE jobId = :jobId")
    suspend fun deleteById(jobId: String)

    @Query("DELETE FROM queue_jobs WHERE jobId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM queue_jobs")
    suspend fun clear()

    @Query("UPDATE queue_jobs SET sortOrder = :order WHERE jobId = :jobId")
    suspend fun updateOrder(jobId: String, order: Int)
}

@Dao
interface QueueResultsDao {
    @Query("SELECT * FROM queue_results")
    fun observeAll(): Flow<List<QueueResultEntity>>

    @Query("SELECT * FROM queue_results")
    suspend fun getAll(): List<QueueResultEntity>

    @Query("SELECT jobId FROM queue_results")
    suspend fun doneIds(): List<String>

    @Query("SELECT * FROM queue_results WHERE jobId = :jobId")
    suspend fun getById(jobId: String): QueueResultEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(result: QueueResultEntity): Long

    @Query("DELETE FROM queue_results WHERE jobId = :jobId")
    suspend fun deleteById(jobId: String)

    @Query("DELETE FROM queue_results WHERE jobId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM queue_results")
    suspend fun clear()
}

@Database(
    entities = [HistoryEntity::class, ComfyTemplateEntity::class, StyleEntity::class, QueueStateEntity::class, QueueJobEntity::class, QueueResultEntity::class, CollectionEntity::class, CollectionItemEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class ForgeryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun comfyTemplateDao(): ComfyTemplateDao
    abstract fun styleDao(): StyleDao
    abstract fun queueStateDao(): QueueStateDao
    abstract fun queueJobsDao(): QueueJobsDao
    abstract fun queueResultsDao(): QueueResultsDao
    abstract fun collectionDao(): CollectionDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE queue_state ADD COLUMN jobProgress REAL NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE queue_state ADD COLUMN batchTotal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE queue_state ADD COLUMN batchDone INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * C-1: normalize queue_state JSON blob into per-row tables.
 * Pure-SQL destructive migration (accepted): the old single-row JSON queue
 * cannot be parsed in SQL, so the in-flight ToDo is dropped on upgrade.
 * History/gallery rows are untouched.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // NOTE: no SQL DEFAULTs — Room validates exact TableInfo and the
        // entities declare no @ColumnInfo(defaultValue=...).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS queue_jobs (" +
                "jobId TEXT NOT NULL PRIMARY KEY, sortOrder INTEGER NOT NULL, " +
                "descr TEXT NOT NULL, mode TEXT NOT NULL, modelTitle TEXT NOT NULL, " +
                "payload TEXT NOT NULL, modulesJson TEXT NOT NULL, " +
                "initImagePath TEXT, maskPath TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_jobs_sortOrder ON queue_jobs(sortOrder)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS queue_results (" +
                "jobId TEXT NOT NULL PRIMARY KEY, descr TEXT NOT NULL, " +
                "filesJson TEXT NOT NULL, error TEXT)",
        )
        // Drop the old single-row JSON state; a fresh slim row is created on demand.
        db.execSQL("DROP TABLE IF EXISTS queue_state")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS queue_state (" +
                "id INTEGER NOT NULL PRIMARY KEY, running INTEGER NOT NULL, " +
                "executingJobId TEXT, origin TEXT NOT NULL, host TEXT NOT NULL, " +
                "jobProgress REAL NOT NULL, batchTotal INTEGER NOT NULL, " +
                "batchDone INTEGER NOT NULL)",
        )
    }
}

/**
 * Gallery collections (N:M grouping of history rows with explicit order).
 * Deleting a collection removes only grouping rows — history rows and files stay.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // NOTE: no SQL DEFAULTs — Room validates exact TableInfo.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS collections (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS collection_items (" +
                "collectionId INTEGER NOT NULL, historyId INTEGER NOT NULL, " +
                "sortOrder INTEGER NOT NULL, PRIMARY KEY(collectionId, historyId), " +
                "FOREIGN KEY(collectionId) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(historyId) REFERENCES history(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_items_collectionId ON collection_items(collectionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_items_historyId ON collection_items(historyId)")
    }
}
