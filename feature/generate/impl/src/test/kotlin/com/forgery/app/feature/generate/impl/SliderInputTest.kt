package com.forgery.app.feature.generate.impl

import com.forgery.app.core.model.GenerationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderInputTest {

    @Test
    fun `steps valid value passes through`() {
        assertEquals(20, parseStepsInput("20", 1f..50f))
    }

    @Test
    fun `steps clamps to range`() {
        assertEquals(1, parseStepsInput("0", 1f..50f))
        assertEquals(50, parseStepsInput("999", 1f..50f))
        assertEquals(1, parseStepsInput("-5", 1f..50f))
    }

    @Test
    fun `steps rejects garbage`() {
        assertNull(parseStepsInput("", 1f..50f))
        assertNull(parseStepsInput("   ", 1f..50f))
        assertNull(parseStepsInput("abc", 1f..50f))
        assertNull(parseStepsInput("7.5", 1f..50f))
    }

    @Test
    fun `steps trims whitespace`() {
        assertEquals(30, parseStepsInput("  30 ", 1f..50f))
    }

    @Test
    fun `cfg valid value passes through`() {
        assertEquals(7.0, parseCfgInput("7", 0f..15f))
        assertEquals(7.3, parseCfgInput("7.3", 0f..15f))
    }

    @Test
    fun `cfg keeps exact typed value without snapping`() {
        assertEquals(7.3, parseCfgInput("7.3", 0f..15f)!!, 0.0)
    }

    @Test
    fun `cfg clamps to range`() {
        assertEquals(0.0, parseCfgInput("-1", 0f..15f))
        assertEquals(15.0, parseCfgInput("99.9", 0f..15f))
    }

    @Test
    fun `cfg rejects garbage`() {
        assertNull(parseCfgInput("", 0f..15f))
        assertNull(parseCfgInput("abc", 0f..15f))
        assertNull(parseCfgInput("7,5", 0f..15f))
    }

    @Test
    fun `server schedulers win when available`() {
        val server = listOf("Automatic", "Karras", "Beta")
        assertEquals(server, schedulerOptions(server, GenerationMode.SDXL, "Karras"))
    }

    @Test
    fun `empty server falls back to curated per-mode list`() {
        assertEquals(
            listOf("Karras", "Normal", "Simple", "Exponential"),
            schedulerOptions(emptyList(), GenerationMode.SDXL, "Karras"),
        )
    }

    @Test
    fun `current value is kept when missing from options`() {
        assertEquals(
            listOf("Karras", "Normal", "Beta"),
            schedulerOptions(listOf("Karras", "Normal"), GenerationMode.SDXL, "Beta"),
        )
    }

    @Test
    fun `blank current is not appended`() {
        assertEquals(
            listOf("Karras", "Normal"),
            schedulerOptions(listOf("Karras", "Normal"), GenerationMode.SDXL, ""),
        )
    }
}
