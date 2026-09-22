package com.forgery.app.core.database

import androidx.room.Dao
import androidx.room.Entity
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
    val currentIndex: Int,
    val total: Int,
    val origin: String,
    val host: String,
    val jobsJson: String,
    val resultsJson: String,
    val jobProgress: Float = 0f,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun pagingHistory(limit: Int, offset: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    fun observeById(id: Long): Flow<List<HistoryEntity>>

    @Query("SELECT COUNT(*) FROM history")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM history")
    suspend fun clear()
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
}

@Database(
    entities = [HistoryEntity::class, ComfyTemplateEntity::class, StyleEntity::class, QueueStateEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ForgeryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun comfyTemplateDao(): ComfyTemplateDao
    abstract fun styleDao(): StyleDao
    abstract fun queueStateDao(): QueueStateDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE queue_state ADD COLUMN jobProgress REAL NOT NULL DEFAULT 0")
    }
}
