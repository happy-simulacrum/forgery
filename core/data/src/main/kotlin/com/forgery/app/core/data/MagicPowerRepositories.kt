package com.forgery.app.core.data

import com.forgery.app.core.common.Result
import com.forgery.app.core.network.LlmApiFactory
import com.forgery.app.core.network.LlmService
import com.forgery.app.core.network.PowerGateway
import com.forgery.app.core.network.llmFirstContent
import com.forgery.app.core.network.llmModelIds
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Magic Prompt (local LLM expand) — ports `connectToLlmService` +
 * `generateLlmPrompt` in network.js. System prompts live in the
 * feature module (legacy globals.js llmSystem_*).
 */
interface MagicPromptRepository {
    suspend fun fetchModels(): Result<List<String>>
    suspend fun expand(systemPrompt: String, model: String, input: String): Result<String>
}

@Singleton
class DefaultMagicPromptRepository @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val llmApiFactory: LlmApiFactory,
) : MagicPromptRepository {

    /** Test seam, mirrors [DefaultGenerationRepository.serviceProvider]. */
    var serviceProvider: (suspend (String) -> LlmService)? = null

    private suspend fun service(): Pair<LlmService, String?> {
        val config = connectionRepository.observe().first()
        val baseUrl = config.llmBaseUrl()
        val auth = llmApiFactory.authHeader(config.llmKey)
        val api = serviceProvider?.invoke(baseUrl) ?: llmApiFactory.create(baseUrl)
        return api to auth
    }

    override suspend fun fetchModels(): Result<List<String>> = try {
        val (api, auth) = service()
        Result.Success(api.models(auth).llmModelIds())
    } catch (e: Exception) {
        Result.Error(e.message ?: e.toString(), e)
    }

    override suspend fun expand(systemPrompt: String, model: String, input: String): Result<String> =
        try {
            val (api, auth) = service()
            val body = kotlinx.serialization.json.buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", input)
                    }
                }
                put("temperature", 0.8)
                put("max_tokens", 300)
                put("top_p", 0.9)
                put("stream", false)
            }
            val content = api.chat(auth, body).llmFirstContent()
            if (content.isBlank()) Result.Error("Empty LLM response")
            else Result.Success(content.trim())
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString(), e)
        }
}

/** Remote PC power control — ports the PWK modal actions in ui.js/network.js. */
enum class PowerService { FORGE, COMFY, LM }

interface PowerRepository {
    suspend fun wakeAll(): Result<String>
    suspend fun powerOff(): Result<String>
    suspend fun setService(service: PowerService, on: Boolean): Result<String>
}

@Singleton
class DefaultPowerRepository @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val gateway: PowerGateway,
) : PowerRepository {

    private suspend fun base(): String =
        connectionRepository.observe().first().wakeBaseUrl()

    private suspend fun post(path: String, label: String): Result<String> =
        when (val r = gateway.post(base() + path)) {
            is Result.Success -> Result.Success("$label: HTTP ${r.data}")
            is Result.Error -> Result.Error("$label failed: ${r.message}", r.cause)
            is Result.Loading -> Result.Loading
        }

    override suspend fun wakeAll(): Result<String> = post("/power", "Wake")
    override suspend fun powerOff(): Result<String> = post("/power/off", "Power off")

    override suspend fun setService(service: PowerService, on: Boolean): Result<String> {
        val name = service.name.lowercase()
        val state = if (on) "on" else "off"
        return post("/power/$name/$state", "$name $state")
    }
}
