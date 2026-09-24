package com.forgery.app.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
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
        repo.setPrompt("a ca", "")
        assertEquals("a ca", repo.observeDraft().first().prompt)
        // Disk still empty — no echo in flight to race the cursor.
        assertEquals("", prefs.observePromptDraft().first().prompt)
    }

    @Test
    fun `flush persists latest typing`() = runTest {
        val (prefs, repo) = repo()
        repo.setPrompt("a", "")
        repo.setPrompt("ab", "")
        repo.setPrompt("abc", "")
        repo.flushNow()
        assertEquals("abc", prefs.observePromptDraft().first().prompt)
        assertEquals("abc", repo.observeDraft().first().prompt)
    }

    @Test
    fun `append composes onto unflushed typing`() = runTest {
        val (prefs, repo) = repo()
        repo.setPrompt("a cat", "blurry")
        repo.appendPrompt("<lora:x>", "trig")
        repo.flushNow()
        val disk = prefs.observePromptDraft().first()
        assertEquals("a cat <lora:x>", disk.prompt)
        assertEquals("blurry trig", disk.negativePrompt)
    }

    @Test
    fun `disk changes flow through when clean`() = runTest {
        val (_, repo) = repo()
        repo.observeDraft().test {
            assertEquals("", awaitItem().prompt)
            // External write straight to disk (no pending typing).
            repo.setPrompt("from styles", "")
            assertEquals("from styles", awaitItem().prompt)
        }
    }
}
