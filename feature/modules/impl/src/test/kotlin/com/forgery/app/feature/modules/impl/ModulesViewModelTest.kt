package com.forgery.app.feature.modules.impl

import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModelParamsRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

private class FakeModelParamsRepository : ModelParamsRepository {
    private val entries = MutableStateFlow(mapOf<String, ModelLastUsed>())

    override fun observeForModel(modelTitle: String): Flow<ModelLastUsed?> =
        entries.map { it[modelTitle] }

    override suspend fun saveForModel(modelTitle: String, params: ModelLastUsed) {
        entries.value = entries.value + (modelTitle to params)
    }

    fun seed(title: String, params: ModelLastUsed) {
        entries.value = entries.value + (title to params)
    }

    fun current(title: String) = entries.value[title]
}

private class FakeModulesSelectionRepository : ModulesSelectionRepository {
    private val stored = MutableStateFlow(emptyList<String>())

    override fun observeModules(): Flow<List<String>> = stored.asStateFlow()

    override suspend fun saveModules(modules: List<String>) {
        stored.value = modules
    }

    fun current() = stored.value
}

class ModulesViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun viewModel(
        modelTitle: String = "m.safetensors",
        gen: FakeGenerationRepository = FakeGenerationRepository(),
        modelParams: FakeModelParamsRepository = FakeModelParamsRepository(),
        selection: FakeModulesSelectionRepository = FakeModulesSelectionRepository(),
    ) = ModulesViewModel(SavedStateHandle(mapOf("modelTitle" to modelTitle)), gen, selection, modelParams)

    private suspend fun loaded(
        modelTitle: String = "m.safetensors",
        gen: FakeGenerationRepository = FakeGenerationRepository(),
        modelParams: FakeModelParamsRepository = FakeModelParamsRepository(),
        selection: FakeModulesSelectionRepository = FakeModulesSelectionRepository(),
    ): Triple<ModulesViewModel, ModulesUiState.Success, FakeModelParamsRepository> {
        val vm = ModulesViewModel(SavedStateHandle(mapOf("modelTitle" to modelTitle)), gen, selection, modelParams)
        val state = vm.uiState.first {
            it is ModulesUiState.Success && it.totalCount == gen.modules.size
        } as ModulesUiState.Success
        return Triple(vm, state, modelParams)
    }

    @Test
    fun `loads catalog on init`() = runTest {
        val (_, state, _) = loaded()
        assertEquals(listOf("ae.safetensors", "clip_l.safetensors"), state.items)
        assertEquals(2, state.totalCount)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun `defaults to nothing selected without entry`() = runTest {
        val (_, state, modelParams) = loaded(modelTitle = "unknown.safetensors")
        assertTrue(state.selected.isEmpty())
        assertEquals(null, modelParams.current("unknown.safetensors"))
    }

    @Test
    fun `inits selection from model entry`() = runTest {
        val modelParams = FakeModelParamsRepository()
        modelParams.seed("m.safetensors", ModelLastUsed(additionalModules = listOf("ae.safetensors")))
        val (_, state, _) = loaded(modelParams = modelParams)
        assertEquals(listOf("ae.safetensors"), state.selected)
    }

    @Test
    fun `toggle merges into entry without clobbering other fields`() = runTest {
        val modelParams = FakeModelParamsRepository()
        modelParams.seed(
            "m.safetensors",
            ModelLastUsed(steps = 30, sampler = "Euler", additionalModules = listOf("clip_l.safetensors")),
        )
        val selection = FakeModulesSelectionRepository()
        val (vm, _, _) = loaded(modelParams = modelParams, selection = selection)
        vm.onAction(ModulesAction.Toggle("ae.safetensors"))
        val selected = vm.uiState.first {
            it is ModulesUiState.Success &&
                it.selected == listOf("ae.safetensors", "clip_l.safetensors")
        } as ModulesUiState.Success
        assertEquals(listOf("ae.safetensors", "clip_l.safetensors"), selected.selected)
        // Merge: steps/sampler survive, modules updated.
        assertEquals(
            ModelLastUsed(
                steps = 30,
                sampler = "Euler",
                additionalModules = listOf("ae.safetensors", "clip_l.safetensors"),
            ),
            modelParams.current("m.safetensors"),
        )
        // Mirrored into the global live selection.
        assertEquals(listOf("ae.safetensors", "clip_l.safetensors"), selection.current())
        // Deselect keeps the rest of the entry intact.
        vm.onAction(ModulesAction.Toggle("ae.safetensors"))
        val cleared = vm.uiState.first {
            it is ModulesUiState.Success && it.selected == listOf("clip_l.safetensors")
        } as ModulesUiState.Success
        assertEquals(listOf("clip_l.safetensors"), cleared.selected)
        assertEquals(30, modelParams.current("m.safetensors")?.steps)
    }

    @Test
    fun `clear empties entry modules and mirrors`() = runTest {
        val modelParams = FakeModelParamsRepository()
        modelParams.seed(
            "m.safetensors",
            ModelLastUsed(steps = 30, additionalModules = listOf("ae.safetensors", "clip_l.safetensors")),
        )
        val selection = FakeModulesSelectionRepository()
        selection.saveModules(listOf("ae.safetensors", "clip_l.safetensors"))
        val (vm, _, _) = loaded(modelParams = modelParams, selection = selection)
        vm.uiState.first {
            it is ModulesUiState.Success && it.selected.size == 2
        }
        vm.onAction(ModulesAction.Clear)
        val cleared = vm.uiState.first {
            it is ModulesUiState.Success && it.selected.isEmpty()
        } as ModulesUiState.Success
        assertTrue(cleared.selected.isEmpty())
        assertEquals(emptyList<String>(), modelParams.current("m.safetensors")?.additionalModules)
        assertEquals(30, modelParams.current("m.safetensors")?.steps)
        assertTrue(selection.current().isEmpty())
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
    fun `refresh prunes stale entry selection and mirror`() = runTest {
        val modelParams = FakeModelParamsRepository()
        modelParams.seed(
            "m.safetensors",
            ModelLastUsed(steps = 30, additionalModules = listOf("ae.safetensors", "gone.safetensors")),
        )
        val selection = FakeModulesSelectionRepository()
        selection.saveModules(listOf("gone.safetensors"))
        val (_, state, _) = loaded(modelParams = modelParams, selection = selection)
        assertEquals(listOf("ae.safetensors"), state.selected)
        assertEquals(listOf("ae.safetensors"), modelParams.current("m.safetensors")?.additionalModules)
        assertEquals(30, modelParams.current("m.safetensors")?.steps)
        // Mirror tracks the live selection, so it follows the pruned entry.
        assertEquals(listOf("ae.safetensors"), selection.current())
    }

    @Test
    fun `fetch error surfaces`() = runTest {
        val gen = FakeGenerationRepository(modulesError = "boom")
        val vm = viewModel(gen = gen)
        val state = vm.uiState.first {
            it is ModulesUiState.Success && it.listError != null
        } as ModulesUiState.Success
        assertEquals("boom", state.listError)
    }
}
