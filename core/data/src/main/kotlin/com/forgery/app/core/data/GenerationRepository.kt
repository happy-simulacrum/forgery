package com.forgery.app.core.data

import com.forgery.app.core.common.Result
import com.forgery.app.core.common.normalizeModelTitle
import com.forgery.app.core.network.ForgeApiFactory
import com.forgery.app.core.network.ForgeService
import com.forgery.app.core.network.images
import com.forgery.app.core.network.progressValue
import com.forgery.app.core.network.stringField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forge/A1111 network operations. Ports `network.js` (fetchModels/Samplers,
 * connect) + `runJob` pre-flight/polling (engine.js) + `ensureModel`
 * (NativeQueueExecutor.java).
 */
interface GenerationRepository {
    suspend fun fetchSdModels(): Result<List<String>>
    suspend fun fetchSamplers(): Result<List<String>>
    suspend fun fetchUpscalers(): Result<List<String>>
    /** Forge Neo scheduler catalog (`GET /sdapi/v1/schedulers`, UI uses `label`). */
    suspend fun fetchSchedulers(): Result<List<String>>
    /** Forge Neo VAE / Text Encoder catalog (`GET /sdapi/v1/sd-modules`, basenames). */
    suspend fun fetchModules(): Result<List<String>>
    suspend fun fetchLoras(): Result<List<com.forgery.app.core.model.LoraItem>>
    suspend fun fetchLoraSidecar(basePath: String): Result<com.forgery.app.core.model.LoraMeta>
    suspend fun fetchPromptStyles(): Result<List<com.forgery.app.core.model.StylePreset>>
    suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit>
    /**
     * Aligns the server-global `forge_additional_modules` (full model reload).
     * An empty selection clears a stale server-global (VAE leak used to
     * produce dark images after the picker was cleared or the mode was
     * switched to FLUX/QWEN, whose payloads carry no modules key).
     * Basenames are resolved server-side via `modules_change`.
     */
    suspend fun ensureAdditionalModules(modules: List<String>): Result<Unit>
    suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>>
    suspend fun img2img(payload: Map<String, Any?>): Result<List<String>>
    suspend fun progress(): Result<Double>
    suspend fun unloadModel(): Result<Unit>

    companion object {
        const val MODEL_ALIGN_ATTEMPTS = 40
        const val MODEL_ALIGN_DELAY_MS = 1_500L
        /** Module sync budget: one POST + short poll (was 40 x 1.5s per job). */
        const val MODULE_SYNC_ATTEMPTS = 10
        const val MODULE_SYNC_DELAY_MS = 3_000L
        /** Generation POST retries on transport errors (resolver fetchWithRetry). */
        const val GENERATION_RETRIES = 5
        const val GENERATION_RETRY_BASE_MS = 1_000L
    }
}

@Singleton
class DefaultGenerationRepository @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val forgeApiFactory: ForgeApiFactory,
) : GenerationRepository {

    /** Test seam: replaced with a fake [ForgeService] in unit tests. */
    var serviceProvider: (suspend (String) -> ForgeService)? = null

    /**
     * Last server-global module selection synced via POST /options, per host.
     * A Neo `forge_additional_modules` POST is a full model reload (tens of
     * seconds on CPU/MPS servers, blocking /progress for every client), so it
     * must happen at most once per distinct selection — not on every job.
     * Per-request `override_settings` (PayloadBuilder) masks the global
     * between syncs; an emptied picker still clears the stale global once.
     */
    @Volatile private var lastModulesHost: String? = null
    @Volatile private var lastSyncedModules: List<String>? = null

    /** Generation client (infinite read): txt2img/img2img only. */
    private suspend fun service(): ForgeService {
        val config = connectionRepository.observe().first()
        val baseUrl = config.webUiBaseUrl()
        return serviceProvider?.invoke(baseUrl)
            ?: forgeApiFactory.create(baseUrl, config.cfClientId, config.cfClientSecret)
    }

    /** Control-plane client (short timeouts): options/progress/catalog. */
    private suspend fun controlService(): ForgeService {
        val config = connectionRepository.observe().first()
        val baseUrl = config.webUiBaseUrl()
        return serviceProvider?.invoke(baseUrl)
            ?: forgeApiFactory.createControl(baseUrl, config.cfClientId, config.cfClientSecret)
    }

    private suspend fun <T> call(block: suspend (ForgeService) -> T): Result<T> =
        try {
            Result.Success(block(controlService()))
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString(), e)
        }

    /**
     * Generation POST with transport-error retries (ports resolver
     * `fetchWithRetry`: 5 attempts, 1s x 1.5 backoff). Retries only
     * [java.io.IOException] (connect/timeout — the request never reached the
     * server); HTTP/serialization errors fail immediately.
     */
    private suspend fun <T> callGeneration(block: suspend (ForgeService) -> T): Result<T> {
        var attempt = 0
        var backoff = GenerationRepository.GENERATION_RETRY_BASE_MS
        while (true) {
            try {
                return Result.Success(block(service()))
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt > GenerationRepository.GENERATION_RETRIES) {
                    return Result.Error(e.message ?: e.toString(), e)
                }
                delay(backoff)
                backoff = (backoff * 1.5).toLong()
            } catch (e: Exception) {
                return Result.Error(e.message ?: e.toString(), e)
            }
        }
    }

    private suspend fun <T> callWithHost(
        block: suspend (ForgeService, String) -> T,
    ): Result<T> = try {
        val config = connectionRepository.observe().first()
        val baseUrl = config.webUiBaseUrl()
        val api = serviceProvider?.invoke(baseUrl)
            ?: forgeApiFactory.createControl(baseUrl, config.cfClientId, config.cfClientSecret)
        Result.Success(block(api, baseUrl))
    } catch (e: Exception) {
        Result.Error(e.message ?: e.toString(), e)
    }

    override suspend fun fetchSdModels(): Result<List<String>> = call { api ->
        api.sdModels().map { it.stringField("model_name", "title") }.filter { it.isNotBlank() }
    }

    override suspend fun fetchSamplers(): Result<List<String>> = call { api ->
        api.samplers().map { it.stringField("name") }.filter { it.isNotBlank() }
    }

    override suspend fun fetchUpscalers(): Result<List<String>> = call { api ->
        api.upscalers().map { it.stringField("name") }.filter { it.isNotBlank() }
    }

    override suspend fun fetchSchedulers(): Result<List<String>> = call { api ->
        api.schedulers().map { it.stringField("label", "name") }.filter { it.isNotBlank() }
    }

    override suspend fun fetchModules(): Result<List<String>> = call { api ->
        api.sdModules().map { it.stringField("model_name", "title") }.filter { it.isNotBlank() }
    }

    override suspend fun fetchLoras(): Result<List<com.forgery.app.core.model.LoraItem>> =
        call { api ->
            api.loras().map { o ->
                com.forgery.app.core.model.LoraItem(
                    name = o.stringField("name"),
                    path = o.stringField("path"),
                    alias = o.stringField("alias"),
                )
            }.filter { it.name.isNotBlank() }
        }

    override suspend fun fetchLoraSidecar(
        basePath: String,
    ): Result<com.forgery.app.core.model.LoraMeta> = callWithHost { api, baseUrl ->
        val meta = api.file("$baseUrl/file=$basePath.json")
        val weight = meta["preferred weight"]?.jsonPrimitive?.doubleOrNull
            ?: meta["weight"]?.jsonPrimitive?.doubleOrNull
            ?: 1.0
        val trigger = meta.stringField("activation text", "trigger", "activation_text")
        com.forgery.app.core.model.LoraMeta(weight = weight, trigger = trigger)
    }

    override suspend fun fetchPromptStyles(): Result<List<com.forgery.app.core.model.StylePreset>> =
        call { api ->
            api.promptStyles().map { o ->
                com.forgery.app.core.model.StylePreset(
                    name = o.stringField("name"),
                    prompt = o.stringField("prompt", "value"),
                    negativePrompt = o.stringField("negative_prompt", "negative"),
                )
            }.filter { it.name.isNotBlank() }
        }

    override suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit> {        if (title.isBlank()) return Result.Success(Unit)
        return try {
            var attempts = 0
            while (attempts < GenerationRepository.MODEL_ALIGN_ATTEMPTS) {
                val current = when (val opts = call { it.options() }) {
                    is Result.Success -> opts.data.stringField("sd_model_checkpoint")
                    is Result.Error -> return Result.Error(opts.message, opts.cause)
                    is Result.Loading -> ""
                }
                if (normalizeModelTitle(current) == normalizeModelTitle(title)) {
                    return Result.Success(Unit)
                }
                if (attempts % 5 == 0) {
                    val load = mutableMapOf<String, Any?>("sd_model_checkpoint" to title)
                    if (resetVaeForInpaint) {
                        load["forge_additional_modules"] = emptyList<String>()
                        load["sd_vae"] = "None"
                    }
                    val posted = call { it.setOptions(load.toJsonObject()).close() }
                    if (posted is Result.Error) return Result.Error(posted.message, posted.cause)
                }
                attempts++
                delay(GenerationRepository.MODEL_ALIGN_DELAY_MS)
            }
            Result.Error("Timeout: server failed to load model.")
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString(), e)
        }
    }

    override suspend fun ensureAdditionalModules(modules: List<String>): Result<Unit> {
        // No early-return on empty: an empty selection must clear a stale
        // server-global instead of inheriting it (covers the cleared picker
        // and FLUX/QWEN jobs following an SDXL+VAE batch).
        // Server stores full paths in options; compare normalized basenames, order-insensitive.
        // Syncs at most once per distinct selection per host: every sync is a
        // full server-side model reload, and per-job reloads wedge the Neo
        // server (/progress stops responding for all clients until restart).
        val want = modules.map { normalizeModelTitle(it) }.sorted()
        return try {
            val host = connectionRepository.observe().first().webUiBaseUrl()
            if (lastModulesHost == host && lastSyncedModules == want) {
                return Result.Success(Unit)
            }
            val current = when (val opts = call { it.options() }) {
                is Result.Success -> opts.data.stringList("forge_additional_modules")
                    .map { normalizeModelTitle(it) }.sorted()
                is Result.Error -> return Result.Error(opts.message, opts.cause)
                is Result.Loading -> emptyList()
            }
            if (current == want) {
                lastModulesHost = host
                lastSyncedModules = want
                return Result.Success(Unit)
            }
            // Single POST (was: re-POST every 5th poll iteration), then bounded poll.
            // Neo resolves basenames server-side (modules_change); post as selected.
            val posted = call {
                it.setOptions(mapOf("forge_additional_modules" to modules).toJsonObject()).close()
            }
            if (posted is Result.Error) return Result.Error(posted.message, posted.cause)
            var attempts = 1
            while (attempts < GenerationRepository.MODULE_SYNC_ATTEMPTS) {
                delay(GenerationRepository.MODULE_SYNC_DELAY_MS)
                val cur = when (val opts = call { it.options() }) {
                    is Result.Success -> opts.data.stringList("forge_additional_modules")
                        .map { normalizeModelTitle(it) }.sorted()
                    is Result.Error -> return Result.Error(opts.message, opts.cause)
                    is Result.Loading -> emptyList()
                }
                if (cur == want) {
                    lastModulesHost = host
                    lastSyncedModules = want
                    return Result.Success(Unit)
                }
                attempts++
            }
            Result.Error("Timeout: server failed to load modules.")
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString(), e)
        }
    }

    override suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>> =
        callGeneration { api -> api.txt2img(forgeApiFactory.sanitized(payload).toJsonObject()).images() }
    override suspend fun img2img(payload: Map<String, Any?>): Result<List<String>> =
        callGeneration { api -> api.img2img(forgeApiFactory.sanitized(payload).toJsonObject()).images() }

    override suspend fun progress(): Result<Double> = call { api ->
        api.progress().progressValue()
    }

    override suspend fun unloadModel(): Result<Unit> =
        when (val r = call { api -> api.unloadCheckpoint(emptyMap()) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(r.message, r.cause)
            is Result.Loading -> Result.Loading
        }
}

/** Reads a JSON string-array option (e.g. Neo `forge_additional_modules`); missing -> empty. */
private fun JsonObject.stringList(key: String): List<String> =
    this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
