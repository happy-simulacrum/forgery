package com.forgery.app.core.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TextFieldFilterTest {

    @Test
    fun `digits pass through with cursor intact`() {
        val v = TextFieldValue("102", TextRange(3))
        assertEquals(v, filterDigits(v))
    }

    @Test
    fun `non-digits are dropped and cursor pulled back`() {
        val v = TextFieldValue("1a0b2", TextRange(5))
        val out = filterDigits(v)
        assertEquals("102", out.text)
        assertEquals(TextRange(3), out.selection)
    }

    @Test
    fun `cursor before dropped char stays`() {
        val v = TextFieldValue("12a34", TextRange(2))
        val out = filterDigits(v)
        assertEquals("1234", out.text)
        assertEquals(TextRange(2), out.selection)
    }

    @Test
    fun `overflow past maxDigits is dropped`() {
        val v = TextFieldValue("12345", TextRange(5))
        val out = filterDigits(v, maxDigits = 4)
        assertEquals("1234", out.text)
        assertEquals(TextRange(4), out.selection)
    }

    @Test
    fun `empty stays empty`() {
        val out = filterDigits(TextFieldValue("", TextRange(0)))
        assertEquals("", out.text)
        assertEquals(TextRange(0), out.selection)
    }
}
