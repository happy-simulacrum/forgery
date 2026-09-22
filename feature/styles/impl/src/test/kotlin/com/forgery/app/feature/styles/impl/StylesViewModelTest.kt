package com.forgery.app.feature.styles.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.data.StyleRepository
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.StylePreset
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private class FakeStyleRepository(
    initial: List<StylePreset> = listOf(
        StylePreset("photo", "photorealistic", "cartoon"),
        StylePreset("anime", "anime style", ""),
    ),
) : StyleRepository {
    private val styles = MutableStateFlow(initial)
    var importCalls = 0
    val deleted = mutableListOf<String>()
    override fun observeAll(): Flow<List<StylePreset>> = styles.asStateFlow()
    override suspend fun upsert(preset: StylePreset) {
        styles.value = styles.value.filterNot { it.name == preset.name } + preset
    }
    override suspend fun delete(name: String) {
        deleted += name
        styles.value = styles.value.filterNot { it.name == name }
    }
    override suspend fun importFromServer(): Result<Int> {
        importCalls++
        return Result.Success(0)
    }
}

private class FakePromptDraftRepository : PromptDraftRepository {
    val appended = mutableListOf<Triple<GenerationMode, String, String>>()
    private val active = MutableStateFlow(GenerationMode.SDXL)
    private val drafts = mutableMapOf<GenerationMode, MutableStateFlow<PromptDraft>>()
    private fun flowOf(mode: GenerationMode) =
        drafts.getOrPut(mode) { MutableStateFlow(PromptDraft()) }
    override fun observeDraft(mode: GenerationMode): Flow<PromptDraft> = flowOf(mode).asStateFlow()
    override fun observeActiveMode(): Flow<GenerationMode> = active.asStateFlow()
    override suspend fun setPrompt(mode: GenerationMode, prompt: String, negativePrompt: String) {
        flowOf(mode).value = PromptDraft(prompt, negativePrompt)
    }
    override suspend fun appendPrompt(mode: GenerationMode, text: String, negativeText: String) {
        appended += Triple(mode, text, negativeText)
    }
    override suspend fun setActiveMode(mode: GenerationMode) {
        active.value = mode
    }
}

class StylesViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val drafts = FakePromptDraftRepository()
    private fun viewModel(repo: FakeStyleRepository = FakeStyleRepository()) =
        StylesViewModel(SavedStateHandle(), repo, drafts)

    private suspend fun StateFlow<StylesUiState>.success(): StylesUiState.Success =
        first { it is StylesUiState.Success } as StylesUiState.Success

    @Test
    fun `apply appends prompt and neg to mode draft`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        val preset = StylePreset("photo", "photorealistic", "cartoon")
        vm.onAction(StylesAction.ApplyStyle(preset))
        vm.uiState.first {
            it is StylesUiState.Success && it.notice != null
        }
        assertEquals(1, drafts.appended.size)
        val (mode, text, neg) = drafts.appended.first()
        assertEquals(GenerationMode.SDXL, mode)
        assertEquals("photorealistic", text)
        assertEquals("cartoon", neg)
    }

    @Test
    fun `search filters and delete delegates`() = runTest {
        val repo = FakeStyleRepository()
        val vm = viewModel(repo)
        vm.uiState.success()
        vm.onAction(StylesAction.QueryChanged("anime"))
        val filtered = vm.uiState.first {
            it is StylesUiState.Success && it.visible.size == 1
        } as StylesUiState.Success
        assertEquals("anime", filtered.visible.first().name)
        vm.onAction(StylesAction.DeleteStyle("anime"))
        assertEquals(listOf("anime"), repo.deleted)
    }

    @Test
    fun `blank name is rejected on save`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(StylesAction.SaveStyle(StylePreset("", "x", "")))
        val state = vm.uiState.first {
            it is StylesUiState.Success && it.notice != null
        } as StylesUiState.Success
        assertEquals("Name is required.", state.notice)
    }
}
