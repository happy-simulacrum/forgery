package com.forgery.app.core.data

import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.GenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadBuilderTest {

    private val base = GenerationParams(
        mode = GenerationMode.SDXL,
        prompt = "a cat",
        negativePrompt = "blurry",
        steps = 20,
        cfgScale = 7.0,
        width = 1024,
        height = 1024,
        sampler = "Euler",
        scheduler = "Normal",
        seed = 42L,
        batchSize = 1,
        batchCount = 2,
        modelTitle = "model.safetensors",
    )

    @Test
    fun `txt2img payload mirrors buildJobFromUI keys`() {
        val p = buildTxt2ImgPayload(base)
        assertEquals("a cat", p["prompt"])
        assertEquals("blurry", p["negative_prompt"])
        assertEquals(20, p["steps"])
        assertEquals(7.0, p["cfg_scale"])
        assertEquals(1024, p["width"])
        assertEquals("Euler", p["sampler_name"])
        assertEquals(42L, p["seed"])
        assertEquals(2, p["n_iter"])
        @Suppress("UNCHECKED_CAST")
        val overrides = p["override_settings"] as Map<String, Any?>
        assertEquals("model.safetensors", overrides["sd_model_checkpoint"])
    }

    @Test
    fun `hr fields only when enabled`() {
        assertFalse(buildTxt2ImgPayload(base).containsKey("enable_hr"))
        val hr = buildTxt2ImgPayload(base.copy(enableHr = true))
        assertEquals(true, hr["enable_hr"])
        assertEquals(1.5, hr["hr_scale"])
        assertEquals("Latent", hr["hr_upscaler"])
        assertEquals(6, hr["hr_second_pass_steps"])
        assertEquals(0.4, hr["denoising_strength"])
        assertEquals(1.0, hr["hr_cfg"])
        assertEquals(listOf("Use same choices"), hr["hr_additional_modules"])
    }

    @Test
    fun `img2img payload carries init image and mask`() {
        val p = buildImg2ImgPayload(base, listOf("AAA"), maskBase64 = "BBB")
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("AAA"), p["init_images"] as List<String>)
        assertEquals("BBB", p["mask"])
        assertEquals(0.75, p["denoising_strength"])
    }

    @Test
    fun `json round trip preserves values and nesting`() {
        val json = payloadToJsonString(buildTxt2ImgPayload(base))
        val back = jsonStringToPayload(json)
        assertEquals("a cat", back["prompt"])
        assertEquals(20L, back["steps"])
        assertEquals(7.0, back["cfg_scale"])
        @Suppress("UNCHECKED_CAST")
        val overrides = back["override_settings"] as Map<String, Any?>
        assertEquals("model.safetensors", overrides["sd_model_checkpoint"])
    }

    @Test
    fun `flux payload contains distilled_cfg_scale`() {
        val p = buildTxt2ImgPayload(base.copy(mode = GenerationMode.FLUX))
        assertTrue(p.containsKey("distilled_cfg_scale"))
        assertEquals(3.5, p["distilled_cfg_scale"])
    }

    @Test
    fun `sdxl payload omits distilled_cfg_scale`() {
        assertFalse(buildTxt2ImgPayload(base.copy(mode = GenerationMode.SDXL)).containsKey("distilled_cfg_scale"))
    }

    @Test
    fun `qwen payload omits distilled_cfg_scale`() {
        assertFalse(buildTxt2ImgPayload(base.copy(mode = GenerationMode.QWEN)).containsKey("distilled_cfg_scale"))
    }

    @Test
    fun `flux custom distilled value is passed through`() {
        val p = buildTxt2ImgPayload(base.copy(mode = GenerationMode.FLUX, distilledCfgScale = 5.0))
        assertEquals(5.0, p["distilled_cfg_scale"])
    }
}
