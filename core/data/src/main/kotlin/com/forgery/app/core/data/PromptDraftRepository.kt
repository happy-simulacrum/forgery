package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.PromptDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared prompt text. GEN/INP edit it, LoRA browser and
 * Styles append/inject into it (legacy: direct writes to
 * localStorage keys + `updateGenTabs`).
 */
interface PromptDraftRepository {
    fun observeDraft(): Flow<PromptDraft>
    suspend fun setPrompt(prompt: String, negativePrompt: String)
    suspend fun appendPrompt(text: String, negativeText: String = "")
}

@Singleton
class DefaultPromptDraftRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : PromptDraftRepository {

    /**
     * Keystrokes land here synchronously (android_crm-style: in-memory first),
     * disk flush follows after [flushDelayMs] of quiet. While the user types
     * (e.g. hold-delete), no store echo races the IME — the cursor stays put.
     * All writers (GEN/INP typing, LoRA/Styles appends) funnel
     * through this pipe, so appends compose onto unflushed typing instead of
     * interleaving with it.
     */
    private val pending = MutableStateFlow<PromptDraft?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null

    // Visible for testing: same-module tests drive the flush deterministically.
    internal var flushDelayMs: Long = DRAFT_FLUSH_DELAY_MS

    override fun observeDraft(): Flow<PromptDraft> =
        combine(prefs.observePromptDraft(), pending) { disk, p ->
            p ?: disk
        }

    override suspend fun setPrompt(prompt: String, negativePrompt: String) {
        pending.update { PromptDraft(prompt, negativePrompt) }
        scheduleFlush()
    }

    override suspend fun appendPrompt(text: String, negativeText: String) {
        // Read synchronously via first() — callers are already in coroutines.
        // Pending-aware: appends compose onto unflushed typing.
        val base = pending.value ?: prefs.observePromptDraft().first()
        val prompt = (base.prompt + " " + text).trim()
        val neg = (base.negativePrompt + " " + negativeText).trim()
        pending.update { PromptDraft(prompt, neg) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(flushDelayMs)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = pending.value ?: return
        prefs.savePromptDraft(snapshot)
        // Drop only when unchanged since the snapshot; newer typing re-arms
        // the flush instead of being lost.
        pending.update { cur -> if (cur == snapshot) null else cur }
        if (pending.value != null) scheduleFlush()
    }

    // Visible for testing: same-module tests drive the flush deterministically.
    internal suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    companion object {
        const val DRAFT_FLUSH_DELAY_MS = 400L
    }
}
