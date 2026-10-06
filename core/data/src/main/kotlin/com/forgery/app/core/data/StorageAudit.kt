package com.forgery.app.core.data

import android.content.Context
import android.util.Log
import com.forgery.app.core.database.HistoryDao
import com.forgery.app.core.database.QueueJobsDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val StorageAuditTag = "ForgeryStorage"

/** Read-only storage breakdown. Never deletes or writes anything. */
data class DirStat(
    val files: Int = 0,
    val bytes: Long = 0L,
)

data class FileStat(
    val name: String,
    val bytes: Long,
)

data class QueueInputsStat(
    val jobDirs: Int = 0,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0L,
    val activeJobs: Int = 0,
    val activeBytes: Long = 0L,
    val orphanJobs: Int = 0,
    val orphanBytes: Long = 0L,
)

data class StorageReport(
    val queueTotal: DirStat = DirStat(),
    val originals: DirStat = DirStat(),
    val thumbs: DirStat = DirStat(),
    val topQueueFiles: List<FileStat> = emptyList(),
    val inputs: QueueInputsStat = QueueInputsStat(),
    val historyCount: Int = -1,
    val historyPaths: Int = -1,
    val forgeryDbBytes: Long = 0L,
    val workDbBytes: Long = 0L,
    val prefsBytes: Long = 0L,
    val filesTopLevel: List<FileStat> = emptyList(),
    /** Every entry of the app data dir (databases, cache, code_cache, …), recursive sizes. */
    val dataDirTopLevel: List<FileStat> = emptyList(),
    /** Whole /data/user/0/<pkg> recursive (internal total, includes cache). */
    val dataDirTotalBytes: Long = 0L,
    /** Device-encrypted storage (user_de), recursive. */
    val deviceEncryptedBytes: Long = 0L,
    /** App-specific external storage (sdcard/Android/data/<pkg>), recursive. */
    val externalBytes: Long = 0L,
    /** Live installd numbers for own package (-1 = unavailable). */
    val statsAppBytes: Long = -1L,
    val statsDataBytes: Long = -1L,
    val statsCacheBytes: Long = -1L,
) {
    val totalBytes: Long =
        queueTotal.bytes + inputs.totalBytes + forgeryDbBytes + workDbBytes + prefsBytes
}

/**
 * In-app storage audit (read-only): measures filesDir/native_queue,
 * filesDir/queue_inputs (active vs orphan job dirs), databases and
 * top-level filesDir entries, then logs one section under [StorageAuditTag].
 * No deletions, no writes — diagnostics only.
 */
@Singleton
class StorageAudit @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyDao: HistoryDao,
    private val jobsDao: QueueJobsDao,
) {
    suspend fun collect(): StorageReport = withContext(Dispatchers.IO) {
        val filesDir = runCatching { context.filesDir }.getOrNull()
        if (filesDir == null) return@withContext StorageReport()
        val appDataDir = try {
            filesDir.parentFile ?: filesDir
        } catch (_: Exception) {
            filesDir
        }

        // filesDir/native_queue: originals vs thumb_*.
        val queueDir = File(filesDir, "native_queue")
        var qFiles = 0
        var qBytes = 0L
        var oFiles = 0
        var oBytes = 0L
        var tFiles = 0
        var tBytes = 0L
        val all = mutableListOf<FileStat>()
        try {
            queueDir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val len = runCatching { f.length() }.getOrDefault(0L)
                qFiles++
                qBytes += len
                all.add(FileStat(f.name, len))
                if (f.name.startsWith("thumb_")) {
                    tFiles++
                    tBytes += len
                } else {
                    oFiles++
                    oBytes += len
                }
            }
        } catch (_: Exception) {
        }
        all.sortByDescending { it.bytes }

        // filesDir/queue_inputs/<jobId>/: active vs orphan.
        val activeIds = try {
            jobsDao.getOrdered().map { it.jobId }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        var jobDirs = 0
        var inFiles = 0
        var inBytes = 0L
        var actJobs = 0
        var actBytes = 0L
        var orphJobs = 0
        var orphBytes = 0L
        try {
            File(filesDir, "queue_inputs").listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                jobDirs++
                val bytes = dir.recursiveBytes()
                val files = dir.recursiveFileCount()
                inFiles += files
                inBytes += bytes
                // Real jobIds are UUIDs (sanitize() is a no-op for them),
                // so raw-name comparison is exact in practice.
                if (dir.name in activeIds) {
                    actJobs++
                    actBytes += bytes
                } else {
                    orphJobs++
                    orphBytes += bytes
                }
            }
        } catch (_: Exception) {
        }

        val historyCount = try {
            historyDao.count().firstOrNull() ?: -1
        } catch (_: Exception) {
            -1
        }
        val historyPaths = try {
            historyDao.getAllPaths().size * 2
        } catch (_: Exception) {
            -1
        }

        val dbDir = File(appDataDir, "databases")
        val forgeryDbBytes = try {
            dbDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("forgery.db") }
                ?.sumOf { runCatching { it.length() }.getOrDefault(0L) } ?: 0L
        } catch (_: Exception) {
            0L
        }
        val workDbBytes = try {
            File(appDataDir, "no_backup").listFiles()
                ?.filter { it.isFile && it.name.startsWith("androidx.work.workdb") }
                ?.sumOf { runCatching { it.length() }.getOrDefault(0L) } ?: 0L
        } catch (_: Exception) {
            0L
        }
        val prefsBytes = try {
            File(filesDir, "datastore").recursiveBytes() +
                File(appDataDir, "shared_prefs").recursiveBytes()
        } catch (_: Exception) {
            0L
        }

        val topLevel = mutableListOf<FileStat>()
        try {
            filesDir.listFiles()?.forEach { f ->
                val bytes = if (f.isFile) {
                    runCatching { f.length() }.getOrDefault(0L)
                } else {
                    f.recursiveBytes()
                }
                topLevel.add(FileStat(f.name, bytes))
            }
        } catch (_: Exception) {
        }
        topLevel.sortByDescending { it.bytes }

        // Whole app data dir (parent of filesDir): catches code_cache, app_*, etc.
        val dataTop = mutableListOf<FileStat>()
        var dataTotal = 0L
        try {
            appDataDir.listFiles()?.forEach { f ->
                val bytes = if (f.isFile) {
                    runCatching { f.length() }.getOrDefault(0L)
                } else {
                    f.recursiveBytes()
                }
                dataTop.add(FileStat(f.name, bytes))
            }
            dataTotal = appDataDir.recursiveBytes()
        } catch (_: Exception) {
        }
        dataTop.sortByDescending { it.bytes }

        val deBytes = try {
            context.createDeviceProtectedStorageContext().filesDir.recursiveBytes()
        } catch (_: Exception) {
            -1L
        }
        val extBytes = try {
            context.getExternalFilesDirs(null)
                .filterNotNull()
                .sumOf { it.recursiveBytes() }
        } catch (_: Exception) {
            -1L
        }

        // Live installd counters for our own package (no permission needed).
        var statsApp = -1L
        var statsData = -1L
        var statsCache = -1L
        try {
            val sm = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
            val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
            if (sm != null && storage != null) {
                val stats = sm.queryStatsForPackage(
                    storage.getUuidForPath(filesDir),
                    context.packageName,
                    android.os.Process.myUserHandle(),
                )
                statsApp = stats.appBytes
                statsData = stats.dataBytes
                statsCache = stats.cacheBytes
            }
        } catch (_: Exception) {
        }

        StorageReport(
            queueTotal = DirStat(qFiles, qBytes),
            originals = DirStat(oFiles, oBytes),
            thumbs = DirStat(tFiles, tBytes),
            topQueueFiles = all.take(5),
            inputs = QueueInputsStat(jobDirs, inFiles, inBytes, actJobs, actBytes, orphJobs, orphBytes),
            historyCount = historyCount,
            historyPaths = historyPaths,
            forgeryDbBytes = forgeryDbBytes,
            workDbBytes = workDbBytes,
            prefsBytes = prefsBytes,
            filesTopLevel = topLevel,
            dataDirTopLevel = dataTop,
            dataDirTotalBytes = dataTotal,
            deviceEncryptedBytes = deBytes,
            externalBytes = extBytes,
            statsAppBytes = statsApp,
            statsDataBytes = statsData,
            statsCacheBytes = statsCache,
        )
    }

    /** Logs the last collected report. Split into short lines (logcat ~4K limit). */
    suspend fun log() {
        val r = collect()
        Log.i(StorageAuditTag, "storage: historyRows=${r.historyCount} historyPaths=${r.historyPaths}")
        Log.i(
            StorageAuditTag,
            "storage: native_queue=${r.queueTotal.mb()} (${r.queueTotal.files} files) " +
                "originals=${r.originals.mb()} (${r.originals.files}) " +
                "thumbs=${r.thumbs.mb()} (${r.thumbs.files})",
        )
        r.topQueueFiles.forEach { f ->
            Log.i(StorageAuditTag, "storage: queue_top ${f.name} ${f.bytes.mb()}")
        }
        val i = r.inputs
        Log.i(
            StorageAuditTag,
            "storage: queue_inputs=${i.totalBytes.mb()} (${i.totalFiles} files, ${i.jobDirs} jobs) " +
                "active=${i.activeBytes.mb()} (${i.activeJobs} jobs) " +
                "orphan=${i.orphanBytes.mb()} (${i.orphanJobs} jobs)",
        )
        Log.i(
            StorageAuditTag,
            "storage: forgery.db=${r.forgeryDbBytes.mb()} workdb=${r.workDbBytes.mb()} " +
                "prefs=${r.prefsBytes.mb()}",
        )
        r.filesTopLevel.forEach { f ->
            Log.i(StorageAuditTag, "storage: filesTop ${f.name} ${f.bytes.mb()}")
        }
        r.dataDirTopLevel.forEach { f ->
            Log.i(StorageAuditTag, "storage: dataTop ${f.name} ${f.bytes.mb()}")
        }
        Log.i(
            StorageAuditTag,
            "storage: dataDirTotal=${r.dataDirTotalBytes.mb()} " +
                "deviceEncrypted=${r.deviceEncryptedBytes.mb()} external=${r.externalBytes.mb()}",
        )
        Log.i(
            StorageAuditTag,
            "storage: installd app=${r.statsAppBytes.mb()} data=${r.statsDataBytes.mb()} " +
                "cache=${r.statsCacheBytes.mb()}",
        )
        Log.i(StorageAuditTag, "storage: TOTAL~= ${r.totalBytes.mb()}")
    }

    private fun File.recursiveBytes(): Long = try {
        if (isFile) {
            runCatching { length() }.getOrDefault(0L)
        } else {
            listFiles()?.sumOf { it.recursiveBytes() } ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    private fun File.recursiveFileCount(): Int = try {
        if (isFile) {
            1
        } else {
            listFiles()?.sumOf { it.recursiveFileCount() } ?: 0
        }
    } catch (_: Exception) {
        0
    }

    private fun Long.mb(): String = "${"%.1f".format(this / 1048576.0)}MB"

    private fun DirStat.mb(): String = bytes.mb()
}
