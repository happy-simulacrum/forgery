package com.forgery.app.core.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdoptExternalTest {

    @Test
    fun `same text returns current untouched`() {
        val cur = TextFieldValue("abc", TextRange(1))
        assertEquals(cur, adoptExternal(cur, "abc"))
    }

    @Test
    fun `out of order echo preserves cursor`() {
        // Locally deleted down to "a" (cursor 1), stale echo "ab" arrives.
        val cur = TextFieldValue("a", TextRange(1))
        val next = adoptExternal(cur, "ab")
        assertEquals("ab", next.text)
        assertEquals(TextRange(1), next.selection)
    }

    @Test
    fun `selection clamps to shorter text`() {
        val cur = TextFieldValue("hello world", TextRange(5, 11))
        val next = adoptExternal(cur, "hello")
        assertEquals("hello", next.text)
        assertEquals(TextRange(5, 5), next.selection)
    }

    @Test
    fun `cursor beyond end clamps`() {
        val cur = TextFieldValue("abcd", TextRange(4))
        val next = adoptExternal(cur, "ab")
        assertEquals(TextRange(2), next.selection)
    }

    @Test
    fun `external insert keeps cursor position`() {
        // LoRA appended while cursor mid-text: cursor stays, not reset.
        val cur = TextFieldValue("a cat", TextRange(2))
        val next = adoptExternal(cur, "a cat <lora:x>")
        assertEquals(TextRange(2), next.selection)
    }

    @Test
    fun `empty external resets selection to zero`() {
        val cur = TextFieldValue("abc", TextRange(3))
        val next = adoptExternal(cur, "")
        assertEquals("", next.text)
        assertEquals(TextRange.Zero, next.selection)
    }
}
