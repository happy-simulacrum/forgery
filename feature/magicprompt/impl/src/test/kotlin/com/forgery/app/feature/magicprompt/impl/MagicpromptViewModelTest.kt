package com.forgery.app.feature.magicprompt.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.data.MagicPromptRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeMagicRepository(
    var models: List<String> = listOf("m.gguf"),
    var expanded: String = "expanded prompt",
) : MagicPromptRepository {
    var lastSystem: String? = null
    var lastInput: String? = null
    override suspend fun fetchModels(): Result<List<String>> = Result.Success(models)
    override suspend fun expand(systemPrompt: String, model: String, input: String): Result<String> {
        lastSystem = systemPrompt
        lastInput = input
        return Result.Success(expanded)
    }
}

private class FakeConnectionRepository(
    var config: ConnectionConfig = ConnectionConfig(),
) : ConnectionRepository {
    private val flow = MutableStateFlow(config)
    val saved = mutableListOf<ConnectionConfig>()
    override fun observe(): Flow<ConnectionConfig> = flow.asStateFlow()
    override suspend fun save(c: ConnectionConfig) {
        saved += c
        flow.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> =
        MutableStateFlow(UiPrefs()).asStateFlow()
    override suspend fun saveUiPrefs(prefs: UiPrefs) = Unit
    override suspend fun reset() = Unit
}

private class FakePromptDraftRepository : PromptDraftRepository {
    private val active = MutableStateFlow(GenerationMode.SDXL)
    private val drafts = mutableMapOf<GenerationMode, MutableStateFlow<PromptDraft>>()
    private fun flowOf(mode: GenerationMode) =
        drafts.getOrPut(mode) { MutableStateFlow(PromptDraft()) }
    override fun observeDraft(mode: GenerationMode): Flow<PromptDraft> = flowOf(mode).asStateFlow()
    override fun observeActiveMode(): Flow<GenerationMode> = active.asStateFlow()
    override suspend fun setPrompt(mode: GenerationMode, prompt: String, negativePrompt: String) {
        flowOf(mode).value = PromptDraft(prompt, negativePrompt)
    }
    override suspend fun appendPrompt(mode: GenerationMode, text: String, negativeText: String) = Unit
    override suspend fun setActiveMode(mode: GenerationMode) {
        active.value = mode
    }
    fun draftOf(mode: GenerationMode) = flowOf(mode).value
}

class MagicpromptViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val magic = FakeMagicRepository()
    private val connection = FakeConnectionRepository()
    private val drafts = FakePromptDraftRepository()
    private fun viewModel() = MagicpromptViewModel(
        SavedStateHandle(), magic, connection, drafts,
    )

    private suspend fun StateFlow<MagicpromptUiState>.success(): MagicpromptUiState.Success =
        first { it is MagicpromptUiState.Success } as MagicpromptUiState.Success

    @Test
    fun `models load on init`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.first {
            it is MagicpromptUiState.Success && it.models.isNotEmpty()
        } as MagicpromptUiState.Success
        assertEquals(listOf("m.gguf"), state.models)
        assertEquals("m.gguf", state.selectedModel)
    }

    @Test
    fun `empty input is rejected`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(MagicpromptAction.Generate)
        assertEquals("Type an idea first.", vm.uiState.success().error)
    }

    @Test
    fun `generate expands with mode system prompt`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(MagicpromptAction.InputChanged("a cat"))
        vm.onAction(MagicpromptAction.Generate)
        val state = vm.uiState.first {
            it is MagicpromptUiState.Success && it.output.isNotBlank()
        } as MagicpromptUiState.Success
        assertEquals("expanded prompt", state.output)
        assertEquals("a cat", magic.lastInput)
        assertTrue(magic.lastSystem!!.contains("Stable Diffusion XL"))
    }

    @Test
    fun `use as prompt replaces mode draft`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(MagicpromptAction.InputChanged("a cat"))
        vm.onAction(MagicpromptAction.Generate)
        vm.uiState.first { it is MagicpromptUiState.Success && it.output.isNotBlank() }
        vm.onAction(MagicpromptAction.UseAsPrompt)
        vm.uiState.first { it is MagicpromptUiState.Success && it.notice != null }
        assertEquals("expanded prompt", drafts.draftOf(GenerationMode.SDXL).prompt)
    }
}
