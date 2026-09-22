package com.forgery.app.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local LLM (LM Studio / Ollama, OpenAI-compatible) — ports the Magic Prompt
 * calls in www/js/network.js (`GET /v1/models`, `POST /v1/chat/completions`).
 */
interface LlmService {
    @GET("v1/models")
    suspend fun models(@Header("Authorization") auth: String? = null): JsonObject

    @POST("v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") auth: String? = null,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): JsonObject
}

fun JsonObject.llmModelIds(): List<String> =
    this["data"]?.jsonArray?.mapNotNull {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
    }.orEmpty()

fun JsonObject.llmFirstContent(): String {
    val message = this["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
    val msg = message["message"]?.jsonObject
    return msg?.get("content")?.jsonPrimitive?.contentOrNull
        ?: message["text"]?.jsonPrimitive?.contentOrNull
        ?: message["response"]?.jsonPrimitive?.contentOrNull
        ?: ""
}

@Singleton
class LlmApiFactory @Inject constructor() {
    fun create(baseUrl: String): LlmService {
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(ForgeJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LlmService::class.java)
    }

    fun authHeader(apiKey: String): String? =
        apiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
}
