package com.forgery.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonTest {

    @Test
    fun `normalize strips hash suffix`() {
        assertEquals("waiillustrioussdxl_v170", normalizeModelTitle("waiIllustriousSDXL_v170 [abc123]"))
    }

    @Test
    fun `normalize strips path and lowercases`() {
        assertEquals("model.safetensors", normalizeModelTitle("C:\\models\\Stable-diffusion\\Model.safetensors"))
        assertEquals("", normalizeModelTitle(null))
        assertEquals("", normalizeModelTitle(""))
    }

    @Test
    fun `overallProgress blends job index and fraction`() {
        assertEquals(1, overallProgress(0, 2, 0f))
        assertEquals(25, overallProgress(0, 2, 0.5f))
        assertEquals(75, overallProgress(1, 2, 0.5f))
        assertEquals(100, overallProgress(1, 1, 1f))
        assertEquals(1, overallProgress(0, 0, 0f))
    }

    @Test
    fun `sanitizer removes neo-crashing keys`() {
        val overrides = mutableMapOf<String, Any?>(
            "sd_model_checkpoint" to "foo",
            "forge_inference_memory" to 6144,
            "forge_unet_storage_dtype" to "Automatic",
            "sd_vae" to "Automatic",
        )
        sanitizeOverrideSettings(overrides)
        assertEquals(
            mapOf("sd_model_checkpoint" to "foo", "sd_vae" to "None"),
            overrides,
        )
    }
}
