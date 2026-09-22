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
import kotlinx.serialization.json.doubleOrNull
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
    suspend fun fetchLoras(): Result<List<com.forgery.app.core.model.LoraItem>>
    suspend fun fetchLoraSidecar(basePath: String): Result<com.forgery.app.core.model.LoraMeta>
    suspend fun fetchPromptStyles(): Result<List<com.forgery.app.core.model.StylePreset>>
    suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit>
    suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>>
    suspend fun img2img(payload: Map<String, Any?>): Result<List<String>>
    suspend fun progress(): Result<Double>
    suspend fun unloadModel(): Result<Unit>

    companion object {
        const val MODEL_ALIGN_ATTEMPTS = 40
        const val MODEL_ALIGN_DELAY_MS = 1_500L
    }
}

@Singleton
class DefaultGenerationRepository @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val forgeApiFactory: ForgeApiFactory,
) : GenerationRepository {

    /** Test seam: replaced with a fake [ForgeService] in unit tests. */
    var serviceProvider: (suspend (String) -> ForgeService)? = null

    private suspend fun service(): ForgeService {
        val config = connectionRepository.observe().first()
        val baseUrl = config.webUiBaseUrl()
        return serviceProvider?.invoke(baseUrl)
            ?: forgeApiFactory.create(baseUrl, config.cfClientId, config.cfClientSecret)
    }

    private suspend fun <T> call(block: suspend (ForgeService) -> T): Result<T> =
        try {
            Result.Success(block(service()))
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString(), e)
        }

    private suspend fun <T> callWithHost(
        block: suspend (ForgeService, String) -> T,
    ): Result<T> = try {
        val config = connectionRepository.observe().first()
        val baseUrl = config.webUiBaseUrl()
        val api = serviceProvider?.invoke(baseUrl)
            ?: forgeApiFactory.create(baseUrl, config.cfClientId, config.cfClientSecret)
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

    override suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit> {
        if (title.isBlank()) return Result.Success(Unit)
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
                    val posted = call { it.setOptions(load.toJsonObject()) }
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

    override suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>> =
        call { api -> api.txt2img(forgeApiFactory.sanitized(payload).toJsonObject()).images() }

    override suspend fun img2img(payload: Map<String, Any?>): Result<List<String>> =
        call { api -> api.img2img(forgeApiFactory.sanitized(payload).toJsonObject()).images() }

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
