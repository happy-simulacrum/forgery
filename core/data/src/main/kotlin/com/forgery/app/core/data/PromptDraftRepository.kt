package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.PromptDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared prompt text per generation mode. GEN/INP edit it, LoRA browser,
 * Styles and Magic Prompt append/inject into it (legacy: direct writes to
 * `{mode}_prompt` localStorage keys + `updateGenTabs`).
 */
interface PromptDraftRepository {
    fun observeDraft(mode: GenerationMode): Flow<PromptDraft>
    fun observeActiveMode(): Flow<GenerationMode>
    suspend fun setPrompt(mode: GenerationMode, prompt: String, negativePrompt: String)
    suspend fun appendPrompt(mode: GenerationMode, text: String, negativeText: String = "")
    suspend fun setActiveMode(mode: GenerationMode)
}

@Singleton
class DefaultPromptDraftRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : PromptDraftRepository {

    override fun observeDraft(mode: GenerationMode): Flow<PromptDraft> =
        prefs.observePromptDraft(mode)

    override fun observeActiveMode(): Flow<GenerationMode> = prefs.observeActiveMode()

    override suspend fun setPrompt(mode: GenerationMode, prompt: String, negativePrompt: String) {
        prefs.savePromptDraft(mode, PromptDraft(prompt, negativePrompt))
    }

    override suspend fun appendPrompt(mode: GenerationMode, text: String, negativeText: String) {
        // Read synchronously via first() — callers are already in coroutines.
        val draft = prefs.observePromptDraft(mode).first()
        val prompt = (draft.prompt + " " + text).trim()
        val neg = (draft.negativePrompt + " " + negativeText).trim()
        prefs.savePromptDraft(mode, PromptDraft(prompt, neg))
    }

    override suspend fun setActiveMode(mode: GenerationMode) {
        prefs.saveActiveMode(mode)
    }
}
