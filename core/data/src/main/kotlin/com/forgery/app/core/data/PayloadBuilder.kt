package com.forgery.app.core.data

import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.GenerationParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val JsonLenient = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * Ports legacy `buildJobFromUI` (engine.js) + `neo.buildJob` (neo.js).
 * Produces Forge/A1111 `/txt2img` + `/img2img` payloads. Neo-crashing keys are
 * NOT stripped here — [com.forgery.app.core.network.ForgeApiFactory.sanitized]
 * applies the sanitizer right before POST.
 * Mirrors resolver engine.js: `distilled_cfg_scale` is written for FLUX only
 * (SDXL/QWEN must not carry the key). Neo `forge_additional_modules`
 * (VAE / Text Encoder) is written for SDXL always — even when empty, so the
 * per-request override masks a stale server-global selection (VAE leak used
 * to produce dark/wrong images after the user cleared the picker or switched
 * to FLUX/QWEN). `sd_model_checkpoint` is omitted when blank so we never
 * reset the server checkpoint with an empty override.
 */
fun buildTxt2ImgPayload(params: GenerationParams): Map<String, Any?> {
    val payload = mutableMapOf<String, Any?>(
        "prompt" to params.prompt,
        "negative_prompt" to params.negativePrompt,
        "steps" to params.steps,
        "cfg_scale" to params.cfgScale,
        "width" to params.width,
        "height" to params.height,
        "sampler_name" to params.sampler,
        "scheduler" to params.scheduler,
        "seed" to params.seed,
        "batch_size" to params.batchSize,
        "n_iter" to params.batchCount,
        "save_images" to false,
        "send_images" to true,
    )
    if (params.mode == GenerationMode.FLUX) {
        payload["distilled_cfg_scale"] = params.distilledCfgScale
    }
    if (params.enableHr) {
        payload["enable_hr"] = true
        payload["hr_scale"] = params.hrScale
        payload["hr_upscaler"] = params.hrUpscaler
        payload["hr_second_pass_steps"] = params.hrSteps
        payload["denoising_strength"] = params.hrDenoise
        payload["hr_cfg"] = params.hrCfg
        payload["hr_additional_modules"] = listOf("Use same choices")
    }
    val overrides = buildMap<String, Any?> {
        if (params.modelTitle.isNotBlank()) {
            put("sd_model_checkpoint", params.modelTitle)
        }
        if (params.mode == GenerationMode.SDXL) {
            put("forge_additional_modules", params.additionalModules)
        }
    }
    if (overrides.isNotEmpty()) {
        payload["override_settings"] = overrides
    }
    return payload
}

fun buildImg2ImgPayload(
    params: GenerationParams,
    initImages: List<String>,
    maskBase64: String? = null,
    denoisingStrength: Double = 0.75,
    maskBlur: Int = 4,
): Map<String, Any?> {
    val payload = buildTxt2ImgPayload(params).toMutableMap()
    payload["init_images"] = initImages
    payload["denoising_strength"] = denoisingStrength
    payload["mask_blur"] = maskBlur
    if (maskBase64 != null) payload["mask"] = maskBase64
    return payload
}

/** Map -> compact JSON string (stored in [com.forgery.app.core.model.QueueJob.payloadJson]). */
fun payloadToJsonString(payload: Map<String, Any?>): String =
    JsonLenient.encodeToString(JsonElement.serializer(), payload.toJsonElement())

/** Map -> [JsonObject] for Retrofit @Body (kotlinx converter cannot handle `Any?`). */
fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(entries.associate { (k, v) -> k to v.toJsonElement() })

/** JSON string -> Map (Worker side, before Retrofit POST). */
fun jsonStringToPayload(json: String): Map<String, Any?> =
    JsonLenient.parseToJsonElement(json).jsonObject.toAnyMap()

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(
        entries.associate { (k, v) -> k.toString() to v.toJsonElement() },
    )
    is Collection<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun JsonObject.toAnyMap(): Map<String, Any?> =
    entries.associate { (k, v) -> k to v.toAny() }

private fun JsonElement.toAny(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> {
            val l = longOrNull!!
            val d = doubleOrNull!!
            if (l.toDouble() == d) l else d
        }
        else -> doubleOrNull ?: content
    }
    is JsonObject -> toAnyMap()
    is JsonArray -> map { it.toAny() }
}
