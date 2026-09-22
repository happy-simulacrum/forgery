package com.forgery.app.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

data class ForgeryDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)

sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>
    data object Loading : Result<Nothing>
}

/** Port of legacy network.js normalize(): strip hash suffix and path. */
fun normalizeModelTitle(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val noHash = raw.substringBefore(" [").trim()
    return noHash.replace('\\', '/').substringAfterLast('/').lowercase()
}

/** Port of NativeQueueExecutor.overallProgress(). */
fun overallProgress(jobIndex: Int, total: Int, jobProgress: Float): Int {
    if (total <= 0) return 1
    val pct = (((jobIndex + jobProgress) / total) * 100f).toInt()
    return pct.coerceIn(1, 100)
}

/** Forge Neo sanitizer: mirrors JS NeoPatch + NativeQueueExecutor.sanitizePayload. */
fun sanitizeOverrideSettings(overrides: MutableMap<String, Any?>) {
    overrides.remove("forge_inference_memory")
    overrides.remove("forge_unet_storage_dtype")
    if (overrides["sd_vae"] == "Automatic") overrides["sd_vae"] = "None"
}

@Singleton
class DispatcherProvider @Inject constructor() {
    fun io(): CoroutineDispatcher = Dispatchers.IO
}
