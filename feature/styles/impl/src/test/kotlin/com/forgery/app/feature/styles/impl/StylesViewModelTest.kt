package com.forgery.app.feature.styles.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.data.StyleRepository
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

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

private class FakePromptDraftRepository(
    var failAppendWith: IOException? = null,
) : PromptDraftRepository {
    val appended = mutableListOf<Pair<String, String>>()
    private val draft = MutableStateFlow(PromptDraft())
    override fun observeDraft(): Flow<PromptDraft> = draft.asStateFlow()
    override suspend fun setPrompt(prompt: String, negativePrompt: String) {
        draft.value = PromptDraft(prompt, negativePrompt)
    }
    override suspend fun appendPrompt(text: String, negativeText: String) {
        failAppendWith?.let { throw it }
        appended += text to negativeText
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
    fun `apply appends prompt and neg to draft`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        val preset = StylePreset("photo", "photorealistic", "cartoon")
        vm.onAction(StylesAction.ApplyStyle(preset))
        vm.uiState.first {
            it is StylesUiState.Success && it.notice != null
        }
        assertEquals(1, drafts.appended.size)
        val (text, neg) = drafts.appended.first()
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

    @Test
    fun `apply with storage failure reports notice`() = runTest {
        val failing = FakePromptDraftRepository(failAppendWith = IOException("disk gone"))
        val vm = StylesViewModel(SavedStateHandle(), FakeStyleRepository(), failing)
        vm.uiState.success()
        val preset = StylePreset("photo", "photorealistic", "cartoon")
        vm.onAction(StylesAction.ApplyStyle(preset))
        val state = vm.uiState.first {
            it is StylesUiState.Success && it.notice != null
        } as StylesUiState.Success
        assertEquals("Apply failed: storage unavailable", state.notice)
        assertTrue(failing.appended.isEmpty())
    }
}
