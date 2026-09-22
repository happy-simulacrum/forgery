package com.forgery.app.core.network

import com.forgery.app.core.common.sanitizeOverrideSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import javax.inject.Inject
import javax.inject.Singleton

val ForgeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Forge/A1111 sdapi — port of www/js/network.js + engine.js contracts.
 * Responses are untyped [JsonObject]: real payloads mix strings/numbers/
 * nested objects (e.g. `/options`, `info`), so rigid DTOs would crash.
 */
interface ForgeService {
    @GET("sdapi/v1/sd-models")
    suspend fun sdModels(): List<JsonObject>

    @GET("sdapi/v1/samplers")
    suspend fun samplers(): List<JsonObject>

    @GET("sdapi/v1/upscalers")
    suspend fun upscalers(): List<JsonObject>

    @GET("sdapi/v1/loras")
    suspend fun loras(): List<JsonObject>

    @GET("sdapi/v1/options")
    suspend fun options(): JsonObject

    @POST("sdapi/v1/options")
    suspend fun setOptions(@Body body: JsonObject): JsonObject

    @GET("sdapi/v1/progress")
    suspend fun progress(): JsonObject

    @POST("sdapi/v1/txt2img")
    suspend fun txt2img(@Body body: JsonObject): JsonObject

    @POST("sdapi/v1/img2img")
    suspend fun img2img(@Body body: JsonObject): JsonObject

    @POST("sdapi/v1/unload-checkpoint")
    suspend fun unloadCheckpoint(@Body body: Map<String, String>): JsonObject

    @GET("sdapi/v1/prompt-styles")
    suspend fun promptStyles(): List<JsonObject>

    /** LoRA sidecar JSON + preview files: legacy `GET /file={base}.json`. */
    @GET
    suspend fun file(@Url url: String): JsonObject
}

fun JsonObject.stringField(vararg names: String): String =
    names.firstNotNullOfOrNull { this[it]?.jsonPrimitive?.contentOrNull }.orEmpty()

fun JsonObject.images(): List<String> =
    this["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

fun JsonObject.progressValue(): Double =
    this["progress"]?.jsonPrimitive?.doubleOrNull ?: 0.0

/** Cloudflare Access + ngrok headers, mirrors network.js Cloudflare inject. */
class ForgeHeadersInterceptor @Inject constructor() : Interceptor {
    @Volatile var cfClientId: String = ""
    @Volatile var cfClientSecret: String = ""
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder()
            .addHeader("ngrok-skip-browser-warning", "true")
            .apply {
                if (cfClientId.isNotBlank()) addHeader("CF-Access-Client-Id", cfClientId)
                if (cfClientSecret.isNotBlank()) addHeader("CF-Access-Client-Secret", cfClientSecret)
            }.build(),
    )
}

@Singleton
class ForgeApiFactory @Inject constructor(
    private val headers: ForgeHeadersInterceptor,
) {
    fun create(baseUrl: String, cfClientId: String = "", cfClientSecret: String = ""): ForgeService {
        headers.cfClientId = cfClientId
        headers.cfClientSecret = cfClientSecret
        val url = baseUrl.trimEnd('/') + "/"
        val client = OkHttpClient.Builder()
            .addInterceptor(headers)
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(ForgeJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ForgeService::class.java)
    }

    /** Applies Neo sanitizer to a txt2img/img2img payload copy. */
    fun sanitized(payload: Map<String, Any?>): Map<String, Any?> {
        val copy = payload.toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val overrides = (copy["override_settings"] as? Map<String, Any?>)?.toMutableMap()
        if (overrides != null) {
            sanitizeOverrideSettings(overrides)
            copy["override_settings"] = overrides
        }
        return copy
    }
}
