package com.forgery.app.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Central debounce (android_crm-style): keystrokes are visible synchronously
 * via [PromptDraftRepository.observeDraft], disk flush follows later — the
 * store echo can't race the IME mid-gesture.
 */
class PromptDraftRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): Pair<ForgeryPreferencesDataSource, DefaultPromptDraftRepository> {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { tmp.newFile("prefs.preferences_pb") },
        )
        val prefs = ForgeryPreferencesDataSource(dataStore)
        val repo = DefaultPromptDraftRepository(prefs).also {
            // Never auto-flush in tests; drive deterministically via flushNow().
            it.flushDelayMs = Long.MAX_VALUE
        }
        return prefs to repo
    }

    @Test
    fun `typing is visible immediately but not on disk yet`() = runTest {
        val (prefs, repo) = repo()
        repo.setPrompt(GenerationMode.SDXL, "a ca", "")
        assertEquals("a ca", repo.observeDraft(GenerationMode.SDXL).first().prompt)
        // Disk still empty — no echo in flight to race the cursor.
        assertEquals("", prefs.observePromptDraft(GenerationMode.SDXL).first().prompt)
    }

    @Test
    fun `flush persists latest typing`() = runTest {
        val (prefs, repo) = repo()
        repo.setPrompt(GenerationMode.SDXL, "a", "")
        repo.setPrompt(GenerationMode.SDXL, "ab", "")
        repo.setPrompt(GenerationMode.SDXL, "abc", "")
        repo.flushNow()
        assertEquals("abc", prefs.observePromptDraft(GenerationMode.SDXL).first().prompt)
        assertEquals("abc", repo.observeDraft(GenerationMode.SDXL).first().prompt)
    }

    @Test
    fun `append composes onto unflushed typing`() = runTest {
        val (prefs, repo) = repo()
        repo.setPrompt(GenerationMode.SDXL, "a cat", "blurry")
        repo.appendPrompt(GenerationMode.SDXL, "<lora:x>", "trig")
        repo.flushNow()
        val disk = prefs.observePromptDraft(GenerationMode.SDXL).first()
        assertEquals("a cat <lora:x>", disk.prompt)
        assertEquals("blurry trig", disk.negativePrompt)
    }

    @Test
    fun `disk changes flow through when clean`() = runTest {
        val (_, repo) = repo()
        repo.observeDraft(GenerationMode.SDXL).test {
            assertEquals("", awaitItem().prompt)
            // External write straight to disk (no pending typing).
            repo.setPrompt(GenerationMode.SDXL, "from styles", "")
            assertEquals("from styles", awaitItem().prompt)
        }
    }

    @Test
    fun `modes are independent`() = runTest {
        val (_, repo) = repo()
        repo.setPrompt(GenerationMode.SDXL, "xl", "")
        repo.setPrompt(GenerationMode.FLUX, "flux", "")
        repo.flushNow()
        assertEquals("flux", repo.observeDraft(GenerationMode.FLUX).first().prompt)
        assertEquals("xl", repo.observeDraft(GenerationMode.SDXL).first().prompt)
    }
}
