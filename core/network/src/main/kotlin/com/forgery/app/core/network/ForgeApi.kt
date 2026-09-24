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
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Forge Neo combined VAE / Text Encoder catalog (models/VAE + models/text_encoder).
     * Items carry `model_name` (basename, e.g. `ae.safetensors`) + `filename` (full server path).
     */
    @GET("sdapi/v1/sd-modules")
    suspend fun sdModules(): List<JsonObject>

    @GET("sdapi/v1/samplers")
    suspend fun samplers(): List<JsonObject>

    @GET("sdapi/v1/upscalers")
    suspend fun upscalers(): List<JsonObject>

    @GET("sdapi/v1/schedulers")
    suspend fun schedulers(): List<JsonObject>

    @GET("sdapi/v1/loras")
    suspend fun loras(): List<JsonObject>

    @GET("sdapi/v1/options")
    suspend fun options(): JsonObject

    /**
     * Neo/A1111 `set_config` returns `None` (body `null`), not a JSON object —
     * hence raw [ResponseBody] (closed by callers) instead of [JsonObject].
     */
    @POST("sdapi/v1/options")
    suspend fun setOptions(@Body body: JsonObject): ResponseBody

    @GET("sdapi/v1/progress")
    suspend fun progress(): JsonObject

    @POST("sdapi/v1/txt2img")
    suspend fun txt2img(@Body body: JsonObject): JsonObject

    @POST("sdapi/v1/img2img")
    suspend fun img2img(@Body body: JsonObject): JsonObject

    @POST("sdapi/v1/unload-checkpoint")
    suspend fun unloadCheckpoint(@Body body: Map<String, String>): JsonObject

    /**
     * Interrupts the current server-side task (empty POST, no payload).
     * Neo answers with an empty body — hence raw [ResponseBody]
     * (closed by callers) instead of [JsonObject], like [setOptions].
     */
    @POST("sdapi/v1/interrupt")
    suspend fun interrupt(): ResponseBody

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

/**
 * Cloudflare Access + ngrok headers, mirrors network.js Cloudflare inject.
 *
 * Immutable per-client instance: CF credentials are constructor vals, so
 * concurrent clients for different hosts/credentials never overwrite each
 * other (was: shared @Volatile vars rewritten before every create() call).
 */
class ForgeHeadersInterceptor(
    private val cfClientId: String,
    private val cfClientSecret: String,
) : Interceptor {
    /** Hilt entry point: default (credential-less) instance. */
    @Inject constructor() : this("", "")

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
    /**
     * Retained for injection compatibility; services now build their own
     * per-client interceptors (see [serviceFor]) instead of sharing this.
     */
    @Suppress("unused") private val headers: ForgeHeadersInterceptor,
) {
    /**
     * Set from [com.forgery.app.app.ForgeryApp] based on the debuggable flag:
     * HTTP logging rides into release builds otherwise (this module has no
     * BuildConfig / debug source set). Defaults to true = historic behavior.
     */
    @Volatile var debugLogging: Boolean = true

    /**
     * Shared logging interceptor (stateless apart from its level, fixed at
     * first use from [debugLogging], which ForgeryApp sets before any
     * create() call).
     */
    private val logging: HttpLoggingInterceptor by lazy {
        HttpLoggingInterceptor().setLevel(
            if (debugLogging) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE,
        )
    }

    /**
     * Per-client service cache keyed by (baseUrl, CF creds, control flag).
     * Each entry owns its OkHttpClient + interceptor, so concurrent requests
     * against different hosts/credentials can't overwrite each other's
     * headers (was: one shared interceptor mutated before every create()
     * call, plus a fresh Retrofit per call without any cache).
     */
    private data class ClientKey(
        val baseUrl: String,
        val cfClientId: String,
        val cfClientSecret: String,
        val control: Boolean,
    )

    private val services = ConcurrentHashMap<ClientKey, ForgeService>()

    companion object {
        /** Bounds connection-pool/dispatcher growth; oldest-approximate entry evicted. */
        private const val MAX_CACHED_SERVICES = 16
    }

    /** Fresh builder per client: never shares an interceptor between services. */
    private fun baseBuilder(cfClientId: String, cfClientSecret: String) = OkHttpClient.Builder()
        .addInterceptor(ForgeHeadersInterceptor(cfClientId, cfClientSecret))
        .addInterceptor(logging)
        .retryOnConnectionFailure(true)

    /** Generation client: infinite read — txt2img/img2img may run for minutes. */
    private fun generationClient(cfClientId: String, cfClientSecret: String): OkHttpClient =
        baseBuilder(cfClientId, cfClientSecret)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    /**
     * Control-plane client: short timeouts for options/progress/catalog.
     * A blocked Neo server (mid-reload) must fail fast here instead of
     * burning the 15s connect budget on every poll iteration.
     */
    private fun controlClient(cfClientId: String, cfClientSecret: String): OkHttpClient =
        baseBuilder(cfClientId, cfClientSecret)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    private fun serviceFor(baseUrl: String, cfClientId: String, cfClientSecret: String, control: Boolean): ForgeService {
        val url = baseUrl.trimEnd('/') + "/"
        val key = ClientKey(url, cfClientId, cfClientSecret, control)
        services[key]?.let { return it }
        val client = if (control) controlClient(cfClientId, cfClientSecret)
        else generationClient(cfClientId, cfClientSecret)
        val service = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(ForgeJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ForgeService::class.java)
        if (services.size >= MAX_CACHED_SERVICES) {
            // ConcurrentHashMap has no insertion order — evict an arbitrary
            // (oldest-approximate) entry to bound pool/dispatcher growth.
            services.keys.firstOrNull()?.let { services.remove(it) }
        }
        return services.putIfAbsent(key, service) ?: service
    }

    fun create(baseUrl: String, cfClientId: String = "", cfClientSecret: String = ""): ForgeService =
        serviceFor(baseUrl, cfClientId, cfClientSecret, control = false)

    /** Short-timeout variant for control-plane calls (options/progress/catalog). */
    fun createControl(baseUrl: String, cfClientId: String = "", cfClientSecret: String = ""): ForgeService =
        serviceFor(baseUrl, cfClientId, cfClientSecret, control = true)

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
