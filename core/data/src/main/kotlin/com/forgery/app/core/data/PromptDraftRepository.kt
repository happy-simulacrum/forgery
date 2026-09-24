package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.PromptDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import java.io.IOException
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
    // Disk is best-effort: an uncaught flush failure must never crash the process.
    private val uncaught = CoroutineExceptionHandler { _, _ -> Unit }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught)
    private var flushJob: Job? = null
    @Volatile private var flushRetries = 0

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
        // Disk is best-effort: on IOException fall back to pending (1 retry).
        val base = pending.value ?: readDiskWithRetry()
        val prompt = (base.prompt + " " + text).trim()
        val neg = (base.negativePrompt + " " + negativeText).trim()
        pending.update { PromptDraft(prompt, neg) }
        scheduleFlush()
    }

    /**
     * Disk read with a single retry after [APPEND_RETRY_DELAY_MS].
     * [CancellationException] is never swallowed — it is rethrown.
     * Final fallback is the current pending value (may have arrived meanwhile)
     * or an empty draft; memory stays the source of truth.
     */
    private suspend fun readDiskWithRetry(): PromptDraft {
        try {
            return prefs.observePromptDraft().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Fall through to the single retry below.
        }
        try {
            delay(APPEND_RETRY_DELAY_MS)
            return prefs.observePromptDraft().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return pending.value ?: PromptDraft()
        }
    }

    private fun scheduleFlush() {
        flushRetries = 0
        scheduleFlushInternal()
    }

    private fun scheduleRetry() {
        flushJob?.cancel()
        flushJob = scope.launch {
            try {
                delay(flushDelayMs)
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: keep pending for the next set/append.
            }
        }
    }

    private fun scheduleFlushInternal() {
        flushJob?.cancel()
        flushJob = scope.launch {
            try {
                delay(flushDelayMs)
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: keep pending for the next set/append.
            }
        }
    }

    private suspend fun flush() {
        val snapshot = pending.value ?: return
        try {
            prefs.savePromptDraft(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Best-effort: keep pending so typing is never lost; retry
            // bounded, then wait for new set/append to re-arm.
            if (flushRetries < FLUSH_MAX_RETRIES) {
                flushRetries++
                scheduleRetry()
            }
            return
        }
        // Drop only when unchanged since the snapshot; newer typing re-arms
        // the flush instead of being lost.
        flushRetries = 0
        pending.update { cur -> if (cur == snapshot) null else cur }
        if (pending.value != null) scheduleFlushInternal()
    }

    // Visible for testing: same-module tests drive the flush deterministically.
    internal suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    companion object {
        const val DRAFT_FLUSH_DELAY_MS = 400L
        const val APPEND_RETRY_DELAY_MS = 150L
        const val FLUSH_MAX_RETRIES = 2
    }
}
