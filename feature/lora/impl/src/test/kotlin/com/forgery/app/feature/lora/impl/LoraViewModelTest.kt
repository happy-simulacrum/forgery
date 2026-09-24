package com.forgery.app.feature.lora.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.LoraRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.LoraItem
import com.forgery.app.core.model.LoraMeta
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

private class FakeGenerationRepository : GenerationRepository {
    var loras = listOf(
        LoraItem("detail.safetensors", "detail.safetensors", "detail"),
        LoraItem("other.safetensors", "sub/other.safetensors", ""),
    )
    var sidecar = LoraMeta(weight = 0.8, trigger = "masterpiece")
    override suspend fun fetchSdModels(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchSamplers(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchSchedulers(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchUpscalers(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchModules(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchLoras(): Result<List<LoraItem>> = Result.Success(loras)
    override suspend fun fetchLoraSidecar(basePath: String): Result<LoraMeta> =
        Result.Success(sidecar)
    override suspend fun fetchPromptStyles(): Result<List<StylePreset>> =
        Result.Success(emptyList())
    override suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit> =
        Result.Success(Unit)
    override suspend fun ensureAdditionalModules(modules: List<String>): Result<Unit> =
        Result.Success(Unit)
    override suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>> =
        Result.Success(emptyList())
    override suspend fun img2img(payload: Map<String, Any?>): Result<List<String>> =
        Result.Success(emptyList())
    override suspend fun progress(): Result<Double> = Result.Success(0.0)
    override suspend fun unloadModel(): Result<Unit> = Result.Success(Unit)
}

private class FakeLoraRepository : LoraRepository {
    private val favs = MutableStateFlow(setOf("detail.safetensors"))
    override fun observeFavorites(): Flow<Set<String>> = favs.asStateFlow()
    override suspend fun setFavorite(name: String, favorite: Boolean) {
        favs.value = if (favorite) favs.value + name else favs.value - name
    }
}

private class FakePromptDraftRepository : PromptDraftRepository {
    val appended = mutableListOf<Pair<String, String>>()
    private val draft = MutableStateFlow(PromptDraft())
    override fun observeDraft(): Flow<PromptDraft> = draft.asStateFlow()
    override suspend fun setPrompt(prompt: String, negativePrompt: String) {
        draft.value = PromptDraft(prompt, negativePrompt)
    }
    override suspend fun appendPrompt(text: String, negativeText: String) {
        appended += text to negativeText
        val cur = draft.value
        draft.value = PromptDraft(
            (cur.prompt + " " + text).trim(),
            (cur.negativePrompt + " " + negativeText).trim(),
        )
    }
}

class LoraViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val drafts = FakePromptDraftRepository()
    private fun viewModel() = LoraViewModel(
        savedStateHandle = SavedStateHandle(),
        generationRepository = FakeGenerationRepository(),
        loraRepository = FakeLoraRepository(),
        promptDrafts = drafts,
    )

    private suspend fun StateFlow<LoraUiState>.success(): LoraUiState.Success =
        first { it is LoraUiState.Success } as LoraUiState.Success

    @Test
    fun `list loads with favorites`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.success()
        // init refresh is async; wait for items
        val loaded = vm.uiState.first {
            it is LoraUiState.Success && it.items.isNotEmpty()
        } as LoraUiState.Success
        assertEquals(2, loaded.items.size)
        assertEquals(setOf("detail.safetensors"), loaded.favorites)
    }

    @Test
    fun `query filters by name and alias`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is LoraUiState.Success && it.items.isNotEmpty() }
        vm.onAction(LoraAction.QueryChanged("detail"))
        val state = vm.uiState.first {
            it is LoraUiState.Success && it.visible.size == 1
        } as LoraUiState.Success
        assertEquals("detail.safetensors", state.visible.first().name)
    }

    @Test
    fun `insert appends tag and trigger to draft`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is LoraUiState.Success && it.items.isNotEmpty() }
        val item = LoraItem("detail.safetensors", "detail.safetensors", "detail")
        vm.onAction(LoraAction.ItemClicked(item))
        vm.uiState.first { it is LoraUiState.Success && it.detail != null }
        vm.onAction(LoraAction.Insert)
        vm.uiState.first {
            it is LoraUiState.Success && it.detail == null && it.notice != null
        }
        assertEquals(1, drafts.appended.size)
        val (tag, trigger) = drafts.appended.first()
        assertEquals("<lora:detail:0.8> masterpiece", tag)
        assertEquals("", trigger)
    }

    @Test
    fun `favorite toggle delegates`() = runTest {
        val repo = FakeLoraRepository()
        val vm = LoraViewModel(
            SavedStateHandle(), FakeGenerationRepository(), repo, FakePromptDraftRepository(),
        )
        vm.uiState.first { it is LoraUiState.Success && it.items.isNotEmpty() }
        vm.onAction(LoraAction.ToggleFavorite("other.safetensors", true))
        val state = vm.uiState.first {
            it is LoraUiState.Success && "other.safetensors" in it.favorites
        } as LoraUiState.Success
        assertTrue("other.safetensors" in state.favorites)
    }
}
