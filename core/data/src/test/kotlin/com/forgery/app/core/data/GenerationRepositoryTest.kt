package com.forgery.app.core.data

import com.forgery.app.core.common.Result
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.network.ForgeApiFactory
import com.forgery.app.core.network.ForgeHeadersInterceptor
import com.forgery.app.core.network.ForgeService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeConnectionRepo : ConnectionRepository {
    private val config = MutableStateFlow(ConnectionConfig(baseIp = "127.0.0.1"))
    private val ui = MutableStateFlow(UiPrefs())
    override fun observe(): Flow<ConnectionConfig> = config.asStateFlow()
    override suspend fun save(c: ConnectionConfig) {
        config.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> = ui.asStateFlow()
    override suspend fun saveUiPrefs(prefs: UiPrefs) {
        ui.value = prefs
    }
    override suspend fun reset() {
        config.value = ConnectionConfig()
    }
}

private class FakeForgeService(
    var checkpoint: String = "model.safetensors",
    var serverModules: List<String> = emptyList(),
    var images: List<String> = listOf("aGVsbG8="),
    var progress: Double = 0.0,
    /** Raw body for POST /options: Neo answers `null` (set_config returns None). */
    var optionsBody: String = "{}",
) : ForgeService {
    val postedOptions = mutableListOf<JsonObject>()
    var lastTxtBody: JsonObject? = null

    override suspend fun sdModels(): List<JsonObject> =
        listOf(buildJsonObject { put("model_name", "model.safetensors") })

    override suspend fun sdModules(): List<JsonObject> =
        listOf(
            buildJsonObject {
                put("model_name", "ae.safetensors")
                put("filename", "/models/VAE/ae.safetensors")
            },
            buildJsonObject {
                put("model_name", "clip_l.safetensors")
                put("filename", "/models/text_encoder/clip_l.safetensors")
            },
        )

    override suspend fun samplers(): List<JsonObject> =
        listOf(buildJsonObject { put("name", "Euler") })

    override suspend fun upscalers(): List<JsonObject> =
        listOf(buildJsonObject { put("name", "Latent") })
    override suspend fun loras(): List<JsonObject> =
        listOf(buildJsonObject {
            put("name", "detail.safetensors")
            put("path", "detail.safetensors")
            put("alias", "detail")
        })

    override suspend fun file(url: String): JsonObject =
        buildJsonObject {
            put("preferred weight", 0.8)
            put("activation text", "masterpiece")
        }

    override suspend fun promptStyles(): List<JsonObject> =
        listOf(buildJsonObject {
            put("name", "s1")
            put("prompt", "best quality")
            put("negative_prompt", "worst quality")
        })

    override suspend fun options(): JsonObject =
        buildJsonObject {
            put("sd_model_checkpoint", checkpoint)
            putJsonArray("forge_additional_modules") { serverModules.forEach { add(it) } }
        }

    override suspend fun setOptions(body: JsonObject): ResponseBody {
        postedOptions += body
        body["sd_model_checkpoint"]?.jsonPrimitive?.contentOrNull?.let { checkpoint = it }
        body["forge_additional_modules"]?.jsonArray?.let { arr ->
            serverModules = arr.map { it.jsonPrimitive.content }
        }
        return optionsBody.toResponseBody("application/json".toMediaType())
    }

    override suspend fun progress(): JsonObject =
        buildJsonObject { put("progress", progress) }

    override suspend fun txt2img(body: JsonObject): JsonObject {
        lastTxtBody = body
        return buildJsonObject {
            putJsonArray("images") { images.forEach { add(it) } }
        }
    }

    override suspend fun img2img(body: JsonObject): JsonObject =
        buildJsonObject {
            putJsonArray("images") { add("img") }
        }

    override suspend fun unloadCheckpoint(body: Map<String, String>): JsonObject =
        buildJsonObject {}
}

class GenerationRepositoryTest {

    private fun repo(fake: ForgeService): DefaultGenerationRepository {
        val r = DefaultGenerationRepository(
            connectionRepository = FakeConnectionRepo(),
            forgeApiFactory = ForgeApiFactory(ForgeHeadersInterceptor()),
        )
        r.serviceProvider = { fake }
        return r
    }

    @Test
    fun `fetchSdModels extracts names`() = runTest {
        val result = repo(FakeForgeService()).fetchSdModels()
        assertTrue(result is Result.Success)
        assertEquals(listOf("model.safetensors"), (result as Result.Success).data)
    }

    @Test
    fun `fetchModules extracts basenames`() = runTest {
        val result = repo(FakeForgeService()).fetchModules()
        assertTrue(result is Result.Success)
        assertEquals(
            listOf("ae.safetensors", "clip_l.safetensors"),
            (result as Result.Success).data,
        )
    }

    @Test
    fun `ensureAdditionalModules no-op when empty`() = runTest {
        val fake = FakeForgeService(serverModules = listOf("other.safetensors"))
        val result = repo(fake).ensureAdditionalModules(emptyList())
        assertTrue(result is Result.Success)
        assertTrue(fake.postedOptions.isEmpty())
    }

    @Test
    fun `ensureAdditionalModules no-op when already aligned`() = runTest {
        val fake = FakeForgeService(
            serverModules = listOf("/models/VAE/ae.safetensors"),
        )
        val result = repo(fake).ensureAdditionalModules(listOf("ae.safetensors"))
        assertTrue(result is Result.Success)
        assertTrue(fake.postedOptions.isEmpty())
    }

    @Test
    fun `ensureAdditionalModules loads modules when mismatched`() = runTest {
        val fake = FakeForgeService(serverModules = emptyList())
        val result = repo(fake).ensureAdditionalModules(
            listOf("clip_l.safetensors", "ae.safetensors"),
        )
        assertTrue(result is Result.Success)
        assertFalse(fake.postedOptions.isEmpty())
        // Posted as selected (basenames); fake server applies them, order-insensitive compare.
        assertEquals(
            listOf("clip_l.safetensors", "ae.safetensors"),
            fake.postedOptions.first()["forge_additional_modules"]?.jsonArray
                ?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `ensureAdditionalModules compares order-insensitively`() = runTest {
        val fake = FakeForgeService(
            serverModules = listOf("ae.safetensors", "clip_l.safetensors"),
        )
        val result = repo(fake).ensureAdditionalModules(
            listOf("clip_l.safetensors", "ae.safetensors"),
        )
        assertTrue(result is Result.Success)
        assertTrue(fake.postedOptions.isEmpty())
    }

    @Test
    fun `ensureModel tolerates null options body`() = runTest {
        // Real Neo/A1111 set_config returns None -> body `null`, not `{}`.
        val fake = FakeForgeService(checkpoint = "other.safetensors", optionsBody = "null")
        val result = repo(fake).ensureModel("model.safetensors", false)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `ensureAdditionalModules tolerates null options body`() = runTest {
        val fake = FakeForgeService(serverModules = emptyList(), optionsBody = "null")
        val result = repo(fake).ensureAdditionalModules(listOf("ae.safetensors"))
        assertTrue(result is Result.Success)
        assertFalse(fake.postedOptions.isEmpty())
    }

    @Test
    fun `fetchSamplers extracts names`() = runTest {
        val result = repo(FakeForgeService()).fetchSamplers()
        assertEquals(listOf("Euler"), (result as Result.Success).data)
    }

    @Test
    fun `fetchUpscalers extracts names`() = runTest {
        val result = repo(FakeForgeService()).fetchUpscalers()
        assertEquals(listOf("Latent"), (result as Result.Success).data)
    }

    @Test
    fun `ensureModel no-op when already aligned`() = runTest {
        val fake = FakeForgeService(checkpoint = "model.safetensors [abc]")
        val result = repo(fake).ensureModel("model.safetensors", false)
        assertTrue(result is Result.Success)
        assertTrue(fake.postedOptions.isEmpty())
    }

    @Test
    fun `ensureModel loads model when mismatched`() = runTest {
        val fake = FakeForgeService(checkpoint = "other.safetensors")
        val result = repo(fake).ensureModel("model.safetensors", true)
        assertTrue(result is Result.Success)
        assertFalse(fake.postedOptions.isEmpty())
        assertEquals(
            "model.safetensors",
            fake.postedOptions.first()["sd_model_checkpoint"]?.jsonPrimitive?.content,
        )
        assertEquals("None", fake.postedOptions.first()["sd_vae"]?.jsonPrimitive?.content)
    }

    @Test
    fun `txt2img sanitizes neo-crashing keys`() = runTest {
        val fake = FakeForgeService()
        val payload = mapOf<String, Any?>(
            "prompt" to "x",
            "override_settings" to mapOf(
                "sd_model_checkpoint" to "m",
                "forge_inference_memory" to 6144,
                "sd_vae" to "Automatic",
            ),
        )
        val result = repo(fake).txt2img(payload)
        assertEquals(listOf("aGVsbG8="), (result as Result.Success).data)
        val overrides = fake.lastTxtBody?.get("override_settings")?.jsonObject
        assertFalse(overrides?.containsKey("forge_inference_memory") == true)
        assertEquals("None", overrides?.get("sd_vae")?.jsonPrimitive?.content)
    }

    @Test
    fun `txt2img keeps forge_additional_modules`() = runTest {
        val fake = FakeForgeService()
        val payload = mapOf<String, Any?>(
            "prompt" to "x",
            "override_settings" to mapOf(
                "sd_model_checkpoint" to "m",
                "forge_additional_modules" to listOf("ae.safetensors"),
            ),
        )
        val result = repo(fake).txt2img(payload)
        assertTrue(result is Result.Success)
        val overrides = fake.lastTxtBody?.get("override_settings")?.jsonObject
        assertEquals(
            listOf("ae.safetensors"),
            overrides?.get("forge_additional_modules")?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `payload map converts to JsonObject preserving types`() {
        val obj = mapOf<String, Any?>(
            "prompt" to "x",
            "steps" to 20,
            "cfg_scale" to 7.0,
            "seed" to 42L,
            "save_images" to false,
            "override_settings" to mapOf("sd_model_checkpoint" to "m"),
            "hr_additional_modules" to listOf("Use same choices"),
        ).toJsonObject()
        assertEquals("x", obj["prompt"]?.jsonPrimitive?.content)
        assertEquals(20L, obj["steps"]?.jsonPrimitive?.long)
        assertEquals(7.0, obj["cfg_scale"]?.jsonPrimitive?.double ?: 0.0, 1e-9)
    }

    @Test
    fun `progress reads value`() = runTest {
        val result = repo(FakeForgeService(progress = 0.42)).progress()
        assertEquals(0.42, (result as Result.Success).data, 1e-9)
    }

    @Test
    fun `unloadModel success`() = runTest {
        val result = repo(FakeForgeService()).unloadModel()
        assertTrue(result is Result.Success)
        assertEquals(Unit, (result as Result.Success).data)
    }

    @Test
    fun `unloadModel error`() = runTest {
        val failing = object : ForgeService by FakeForgeService() {
            override suspend fun unloadCheckpoint(body: Map<String, String>): JsonObject =
                throw RuntimeException("boom")
        }
        val result = repo(failing).unloadModel()
        assertTrue(result is Result.Error)
        assertEquals("boom", (result as Result.Error).message)
    }

    @Test
    fun `fetchLoras maps items`() = runTest {
        val result = repo(FakeForgeService()).fetchLoras()
        val items = (result as Result.Success).data
        assertEquals(1, items.size)
        assertEquals("detail.safetensors", items.first().name)
        assertEquals("detail", items.first().alias)
    }

    @Test
    fun `fetchLoraSidecar reads weight and trigger`() = runTest {
        val result = repo(FakeForgeService()).fetchLoraSidecar("detail")
        val meta = (result as Result.Success).data
        assertEquals(0.8, meta.weight, 1e-9)
        assertEquals("masterpiece", meta.trigger)
    }

    @Test
    fun `fetchPromptStyles maps presets`() = runTest {
        val result = repo(FakeForgeService()).fetchPromptStyles()
        val presets = (result as Result.Success).data
        assertEquals(1, presets.size)
        assertEquals("s1", presets.first().name)
        assertEquals("best quality", presets.first().prompt)
    }
}
