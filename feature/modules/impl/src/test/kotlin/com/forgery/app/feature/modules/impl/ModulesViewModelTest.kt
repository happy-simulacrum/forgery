package com.forgery.app.feature.modules.impl

import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeGenerationRepository(
    var modules: List<String> = listOf("ae.safetensors", "clip_l.safetensors"),
    var modulesError: String? = null,
) : GenerationRepository {
    override suspend fun fetchSdModels(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchSamplers(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchSchedulers(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchUpscalers(): Result<List<String>> = Result.Success(emptyList())
    override suspend fun fetchModules(): Result<List<String>> =
        modulesError?.let { Result.Error(it) } ?: Result.Success(modules)
    override suspend fun fetchLoras(): Result<List<com.forgery.app.core.model.LoraItem>> =
        Result.Success(emptyList())
    override suspend fun fetchLoraSidecar(basePath: String): Result<com.forgery.app.core.model.LoraMeta> =
        Result.Success(com.forgery.app.core.model.LoraMeta())
    override suspend fun fetchPromptStyles(): Result<List<com.forgery.app.core.model.StylePreset>> =
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

private class FakeModulesSelectionRepository : ModulesSelectionRepository {
    private val stored = mutableMapOf<GenerationMode, MutableStateFlow<List<String>>>()

    private fun flowOf(mode: GenerationMode) =
        stored.getOrPut(mode) { MutableStateFlow(emptyList()) }

    override fun observeModules(mode: GenerationMode): Flow<List<String>> =
        flowOf(mode).asStateFlow()

    override suspend fun saveModules(mode: GenerationMode, modules: List<String>) {
        flowOf(mode).value = modules
    }

    fun current(mode: GenerationMode) = flowOf(mode).value
}

class ModulesViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun viewModel(
        gen: FakeGenerationRepository = FakeGenerationRepository(),
        selection: FakeModulesSelectionRepository = FakeModulesSelectionRepository(),
    ) = ModulesViewModel(SavedStateHandle(), gen, selection)

    private suspend fun loaded(
        gen: FakeGenerationRepository = FakeGenerationRepository(),
        selection: FakeModulesSelectionRepository = FakeModulesSelectionRepository(),
    ): Triple<ModulesViewModel, ModulesUiState.Success, FakeModulesSelectionRepository> {
        val vm = viewModel(gen, selection)
        val state = vm.uiState.first {
            it is ModulesUiState.Success && it.totalCount == gen.modules.size
        } as ModulesUiState.Success
        return Triple(vm, state, selection)
    }

    @Test
    fun `loads catalog on init`() = runTest {
        val (_, state, _) = loaded()
        assertEquals(listOf("ae.safetensors", "clip_l.safetensors"), state.items)
        assertEquals(2, state.totalCount)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun `toggle selects then deselects`() = runTest {
        val (vm, _, selection) = loaded()
        vm.onAction(ModulesAction.Toggle("ae.safetensors"))
        val selected = vm.uiState.first {
            it is ModulesUiState.Success && it.selected == listOf("ae.safetensors")
        } as ModulesUiState.Success
        assertEquals(listOf("ae.safetensors"), selected.selected)
        assertEquals(listOf("ae.safetensors"), selection.current(GenerationMode.SDXL))
        vm.onAction(ModulesAction.Toggle("ae.safetensors"))
        val cleared = vm.uiState.first {
            it is ModulesUiState.Success && it.selected.isEmpty()
        } as ModulesUiState.Success
        assertTrue(cleared.selected.isEmpty())
    }

    @Test
    fun `clear empties selection`() = runTest {
        val (vm, _, selection) = loaded()
        selection.saveModules(GenerationMode.SDXL, listOf("ae.safetensors", "clip_l.safetensors"))
        vm.uiState.first {
            it is ModulesUiState.Success && it.selected.size == 2
        }
        vm.onAction(ModulesAction.Clear)
        val cleared = vm.uiState.first {
            it is ModulesUiState.Success && it.selected.isEmpty()
        } as ModulesUiState.Success
        assertTrue(cleared.selected.isEmpty())
    }

    @Test
    fun `query filters items`() = runTest {
        val (vm, _, _) = loaded()
        vm.onAction(ModulesAction.QueryChanged(TextFieldValue("clip")))
        val filtered = vm.uiState.first {
            it is ModulesUiState.Success && it.items == listOf("clip_l.safetensors")
        } as ModulesUiState.Success
        assertEquals(listOf("clip_l.safetensors"), filtered.items)
        assertEquals(2, filtered.totalCount)
        assertEquals("clip", filtered.query.text)
    }

    @Test
    fun `refresh prunes stale persisted selection`() = runTest {
        val selection = FakeModulesSelectionRepository()
        selection.saveModules(GenerationMode.SDXL, listOf("ae.safetensors", "gone.safetensors"))
        val (_, state, _) = loaded(selection = selection)
        assertEquals(listOf("ae.safetensors"), state.selected)
        assertEquals(listOf("ae.safetensors"), selection.current(GenerationMode.SDXL))
    }

    @Test
    fun `fetch error surfaces`() = runTest {
        val gen = FakeGenerationRepository(modulesError = "boom")
        val vm = viewModel(gen)
        val state = vm.uiState.first {
            it is ModulesUiState.Success && it.listError != null
        } as ModulesUiState.Success
        assertEquals("boom", state.listError)
    }
}
