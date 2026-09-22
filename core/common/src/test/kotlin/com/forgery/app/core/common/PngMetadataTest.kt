package com.forgery.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

class PngMetadataTest {

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
        return out.toByteArray()
    }

    private fun pngBytes(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        // Minimal IHDR (1x1 RGB).
        val ihdr = ByteArrayOutputStream()
        val dos = DataOutputStream(ihdr)
        dos.writeInt(1)
        dos.writeInt(1)
        dos.writeByte(8)
        dos.writeByte(2)
        dos.writeByte(0)
        dos.writeByte(0)
        dos.writeByte(0)
        out.write(chunk("IHDR", ihdr.toByteArray()))
        chunks.forEach { out.write(it) }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test
    fun `reads tEXt parameters chunk`() {
        val text = "a cat\nNegative prompt: blurry\nSteps: 20, Sampler: Euler"
        val payload = "parameters".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        val meta = readPngMetadata(pngBytes(chunk("tEXt", payload)))
        assertEquals(text, meta?.parameters)
        assertEquals(text, meta?.entries?.get("parameters"))
    }

    @Test
    fun `non-png returns null`() {
        assertNull(readPngMetadata("hello".toByteArray()))
        assertNull(readPngMetadata(ByteArray(0)))
    }

    @Test
    fun `png without text returns empty metadata`() {
        val meta = readPngMetadata(pngBytes())
        assertTrue(meta?.entries?.isEmpty() == true)
        assertNull(meta?.parameters)
    }

    @Test
    fun `a1111 split separates neg and cuts settings tail`() {
        val raw = "a cat, detailed\nNegative prompt: blurry, lowres\nSteps: 20, Sampler: Euler, Seed: 1"
        val (prompt, neg) = parseA1111Parameters(raw)
        assertEquals("a cat, detailed", prompt)
        assertEquals("blurry, lowres", neg)
    }

    @Test
    fun `a1111 split without neg keeps prompt only`() {
        val raw = "a cat\nSteps: 20, Sampler: Euler"
        val (prompt, neg) = parseA1111Parameters(raw)
        assertEquals("a cat", prompt)
        assertEquals("", neg)
    }

    @Test
    fun `a1111 settings parses full tail with hr`() {
        val raw = "a cat\nNegative prompt: blurry\nSteps: 20, Sampler: Euler a, Schedule type: Automatic, " +
            "CFG scale: 7.0, Seed: 123, Size: 512x768, Model: v1-5-pruned, " +
            "Hires upscale: 2.0, Hires steps: 10, Hires upscaler: Latent, Denoising strength: 0.7"
        val s = parseA1111Settings(raw)
        assertEquals(20, s.steps)
        assertEquals("Euler a", s.sampler)
        assertEquals("Automatic", s.scheduler)
        assertEquals(7.0, s.cfg)
        assertEquals(123L, s.seed)
        assertEquals(512, s.width)
        assertEquals(768, s.height)
        assertEquals("v1-5-pruned", s.model)
        assertTrue(s.hrEnable)
        assertEquals("Latent", s.hrUpscaler)
        assertEquals(2.0, s.hrScale)
        assertEquals(10, s.hrSteps)
        assertEquals(0.7, s.hrDenoise)
    }

    @Test
    fun `a1111 settings parses tail without negative section`() {
        val raw = "a cat\nSteps: 30, Sampler: DPM++, CFG scale: 5.5, Seed: 42, Size: 768x512, Model: sd_xl"
        val s = parseA1111Settings(raw)
        assertEquals(30, s.steps)
        assertEquals("DPM++", s.sampler)
        assertEquals(5.5, s.cfg)
        assertEquals(42L, s.seed)
        assertEquals(768, s.width)
        assertEquals(512, s.height)
        assertEquals("sd_xl", s.model)
        assertEquals(false, s.hrEnable)
    }

    @Test
    fun `a1111 settings parses minimal tail`() {
        val s = parseA1111Settings("Steps: 20, Seed: 1")
        assertEquals(20, s.steps)
        assertEquals(1L, s.seed)
        assertNull(s.sampler)
        assertNull(s.model)
        assertEquals(false, s.hrEnable)
    }

    @Test
    fun `a1111 settings foreign text returns defaults`() {
        val s = parseA1111Settings("just some random text, no settings here!")
        assertEquals(A1111Settings(), s)
    }

    @Test
    fun `a1111 settings model hash does not overwrite model`() {
        val raw = "a cat\nSteps: 20, Model hash: abc123, Seed: 5"
        val s = parseA1111Settings(raw)
        assertNull(s.model)
        assertEquals(20, s.steps)
        assertEquals(5L, s.seed)
    }
}
