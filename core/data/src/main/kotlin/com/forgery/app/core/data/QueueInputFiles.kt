package com.forgery.app.core.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C-1 file-back for inpaint inputs: source/mask PNG bytes live in
 * filesDir/queue_inputs/<jobId>/ instead of base64 inside payloadJson/DB.
 * PayloadJson carries params only (~1KB); the worker expands file paths to
 * full init_images/mask base64 right before POST. Interface + fake-friendly:
 * [saveBase64] takes base64 strings so callers never touch android.util.Base64
 * directly (JVM unit tests stay green).
 */
interface QueueInputs {
    /** Saves base64 PNGs, returns absolute (initPath, maskPath?) — mask null stays null. */
    fun saveBase64(jobId: String, initB64: String, maskB64: String?): Pair<String, String?>

    /** Reads a stored file back to base64, null on blank path / any error. */
    fun loadBase64OrNull(path: String?): String?

    fun deleteJob(jobId: String)

    /** Deletes input dirs without a live jobId (orphan sweep on start). */
    fun sweepOrphans(activeIds: Set<String>)
}

@Singleton
class FileQueueInputs @Inject constructor(
    @ApplicationContext private val context: Context,
) : QueueInputs {
    private fun inputsDir(): File =
        File(context.filesDir, "queue_inputs").also { it.mkdirs() }

    private fun jobDir(jobId: String): File =
        File(inputsDir(), sanitize(jobId)).also { it.mkdirs() }

    override fun saveBase64(jobId: String, initB64: String, maskB64: String?): Pair<String, String?> {
        val dir = jobDir(jobId)
        val initFile = File(dir, "init.png")
        initFile.writeBytes(Base64.decode(initB64, Base64.DEFAULT))
        val maskFile = maskB64?.let { b64 ->
            File(dir, "mask.png").also { f -> f.writeBytes(Base64.decode(b64, Base64.DEFAULT)) }
        }
        return initFile.absolutePath to maskFile?.absolutePath
    }

    override fun loadBase64OrNull(path: String?): String? =
        if (path.isNullOrBlank()) null else try {
            Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }

    override fun deleteJob(jobId: String) {
        try {
            File(inputsDir(), sanitize(jobId)).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    override fun sweepOrphans(activeIds: Set<String>) {
        try {
            val active = activeIds.map(::sanitize).toSet()
            inputsDir().listFiles()?.forEach { f ->
                if (f.isDirectory && f.name !in active) f.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    private fun sanitize(jobId: String): String =
        jobId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "job" }
}
