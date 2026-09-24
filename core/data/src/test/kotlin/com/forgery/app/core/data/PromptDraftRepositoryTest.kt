package com.forgery.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

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

    /**
     * Controllable DataStore: reads fail while [failReadsForever] or
     * [failReadsRemaining] > 0 (one failure per collection), writes fail
     * while [failWrites]. Failures are [IOException] so the prefs `.catch`
     * fallback and the repository retry paths both engage.
     */
    private class ControllableDataStore(
        var failReadsForever: Boolean = false,
        var failReadsRemaining: Int = 0,
        var failWrites: Boolean = false,
        private var stored: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            if (failReadsForever || failReadsRemaining > 0) {
                if (failReadsRemaining > 0) failReadsRemaining--
                throw IOException("broken read")
            }
            emit(stored)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("broken write")
            stored = transform(stored)
            return stored
        }
    }

    private fun repoWith(store: DataStore<Preferences>): Pair<ForgeryPreferencesDataSource, DefaultPromptDraftRepository> {
        val prefs = ForgeryPreferencesDataSource(store)
        val repo = DefaultPromptDraftRepository(prefs).also {
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

    @Test
    fun `broken datastore read falls back to empty draft`() = runTest {
        val store = ControllableDataStore(failReadsForever = true)
        val (_, repo) = repoWith(store)
        assertEquals(PromptDraft(), repo.observeDraft().first())
    }

    @Test
    fun `append with broken disk composes onto pending and flush writes after repair`() = runTest {
        val store = ControllableDataStore(failReadsForever = true)
        val (prefs, repo) = repoWith(store)
        repo.setPrompt("a cat", "blurry")
        // Disk reads are broken, but pending is the source of truth —
        // the append composes onto unflushed typing without touching disk.
        repo.appendPrompt("<lora:x>", "trig")
        assertEquals("a cat <lora:x>", repo.observeDraft().first().prompt)
        assertEquals("blurry trig", repo.observeDraft().first().negativePrompt)
        // Repair reads, then flush persists the composed value.
        store.failReadsForever = false
        repo.flushNow()
        val disk = prefs.observePromptDraft().first()
        assertEquals("a cat <lora:x>", disk.prompt)
        assertEquals("blurry trig", disk.negativePrompt)
    }

    @Test
    fun `flush failure keeps pending and retry after repair writes`() = runTest {
        val store = ControllableDataStore(failWrites = true)
        val (prefs, repo) = repoWith(store)
        repo.setPrompt("abc", "")
        repo.flushNow()
        // Write failed: disk still empty, memory still holds the typing.
        assertEquals("", prefs.observePromptDraft().first().prompt)
        assertEquals("abc", repo.observeDraft().first().prompt)
        // Repair writes, retry persists.
        store.failWrites = false
        repo.flushNow()
        assertEquals("abc", prefs.observePromptDraft().first().prompt)
        assertEquals("abc", repo.observeDraft().first().prompt)
    }
}
