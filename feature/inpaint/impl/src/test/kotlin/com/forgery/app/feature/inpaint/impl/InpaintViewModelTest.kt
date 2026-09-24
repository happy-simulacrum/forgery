package com.forgery.app.feature.inpaint.impl

import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModelParamsRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.data.QueueInputs
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeImageReader(
    var triple: Triple<String, Int, Int>? = Triple("AAA", 512, 512),
) : ImageAttachmentReader {
    override suspend fun readDownscaled(uri: String, maxSide: Int): Triple<String, Int, Int>? = triple
}

private class FakeGenerationRepository(
    var models: List<String> = listOf("m.safetensors"),
) : GenerationRepository {
    override suspend fun fetchSdModels(): Result<List<String>> = Result.Success(models)
    override suspend fun fetchSamplers(): Result<List<String>> = Result.Success(listOf("Euler"))
    override suspend fun fetchSchedulers(): Result<List<String>> = Result.Success(listOf("Karras"))
    override suspend fun fetchUpscalers(): Result<List<String>> = Result.Success(listOf("Latent"))
    override suspend fun fetchModules(): Result<List<String>> = Result.Success(emptyList())
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
        Result.Success(listOf("img"))
    override suspend fun img2img(payload: Map<String, Any?>): Result<List<String>> =
        Result.Success(listOf("img"))
    override suspend fun progress(): Result<Double> = Result.Success(0.0)
    override suspend fun unloadModel(): Result<Unit> = Result.Success(Unit)
}

private class FakeQueueRepository : QueueRepository {
    val immediate = mutableListOf<Pair<List<QueueJob>, String>>()
    val snapshot = MutableStateFlow<QueueSnapshot?>(null)
    override fun observeSnapshot(): Flow<QueueSnapshot?> = snapshot.asStateFlow()
    override fun observeJobs(): Flow<List<QueueJob>> =
        MutableStateFlow<List<QueueJob>>(emptyList()).asStateFlow()
    override fun observeResults(): Flow<List<QueueResult>> =
        MutableStateFlow<List<QueueResult>>(emptyList()).asStateFlow()
    override suspend fun stage(jobs: List<QueueJob>, origin: String) = Unit
    override suspend fun start() = Unit
    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) {
        immediate += jobs to origin
    }
    override suspend fun cancel() = Unit
    override suspend fun clearCompleted() = Unit
    override suspend fun clearPending() = Unit
    override suspend fun removeJob(jobId: String): Boolean = true
    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean = true
}

private class FakeQueueInputs : QueueInputs {
    override fun saveBase64(jobId: String, initB64: String, maskB64: String?): Pair<String, String?> =
        "/inputs/$jobId/init.png" to maskB64?.let { "/inputs/$jobId/mask.png" }
    override fun loadBase64OrNull(path: String?): String? = path?.let { "b64" }
    override fun deleteJob(jobId: String) = Unit
    override fun sweepOrphans(activeIds: Set<String>) = Unit
}

private class FakeModulesSelectionRepository : ModulesSelectionRepository {
    private val stored = MutableStateFlow(emptyList<String>())

    override fun observeModules(): Flow<List<String>> = stored.asStateFlow()

    override suspend fun saveModules(modules: List<String>) {
        stored.value = modules
    }
}

private class FakeModelParamsRepository : ModelParamsRepository {
    private val entries = MutableStateFlow(mapOf<String, ModelLastUsed>())

    override fun observeForModel(modelTitle: String): Flow<ModelLastUsed?> =
        entries.map { it[modelTitle] }

    override suspend fun saveForModel(modelTitle: String, params: ModelLastUsed) {
        entries.value = entries.value + (modelTitle to params)
    }
}

class InpaintViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val queue = FakeQueueRepository()
    private fun viewModel(
        reader: ImageAttachmentReader = FakeImageReader(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        selection: ModulesSelectionRepository = FakeModulesSelectionRepository(),
        modelParams: ModelParamsRepository = FakeModelParamsRepository(),
        inputs: QueueInputs = FakeQueueInputs(),
        gen: FakeGenerationRepository = FakeGenerationRepository(),
    ) = InpaintViewModel(savedStateHandle, reader, gen, queue, selection, modelParams, inputs)

    private suspend fun StateFlow<InpaintUiState>.success(): InpaintUiState.Success =
        first { it is InpaintUiState.Success } as InpaintUiState.Success

    @Test
    fun `generate without image is rejected`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("x")))
        vm.onAction(InpaintAction.Generate)
        assertEquals("Pick an image first.", vm.uiState.success().statusMessage)
        assertTrue(queue.immediate.isEmpty())
    }

    @Test
    fun `pick attaches downscaled image and clears mask`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.source != null
        } as InpaintUiState.Success
        assertEquals(512, state.source?.width)
        assertEquals("AAA", state.source?.base64)
    }

    @Test
    fun `id route arg auto-attaches source without picker`() = runTest {
        val handle = SavedStateHandle(mapOf("id" to "file:///tmp/history.png"))
        val vm = viewModel(savedStateHandle = handle)
        vm.uiState.success()
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.source != null
        } as InpaintUiState.Success
        assertEquals("file:///tmp/history.png", state.source?.uri)
        assertEquals(512, state.source?.width)
        assertEquals("AAA", state.source?.base64)
    }

    @Test
    fun `stroke lifecycle add point undo clear`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        val a = MaskPoint(0.1f, 0.1f)
        val b = MaskPoint(0.2f, 0.2f)
        vm.onAction(InpaintAction.StrokeStarted(a))
        vm.onAction(InpaintAction.StrokePoint(b))
        vm.onAction(InpaintAction.StrokeEnded)
        assertEquals(1, vm.uiState.success().strokes.size)
        assertEquals(2, vm.uiState.success().strokes.first().points.size)
        vm.onAction(InpaintAction.UndoStroke)
        assertTrue(vm.uiState.success().strokes.isEmpty())
        vm.onAction(InpaintAction.StrokeStarted(a))
        vm.onAction(InpaintAction.StrokeEnded)
        vm.onAction(InpaintAction.ClearMask)
        assertTrue(vm.uiState.success().strokes.isEmpty())
    }

    @Test
    fun `single tap stroke is kept`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.StrokeStarted(MaskPoint(0.5f, 0.5f)))
        vm.onAction(InpaintAction.StrokeEnded)
        assertEquals(1, vm.uiState.success().strokes.size)
    }

    @Test
    fun `enqueue idle reports queued inpaint`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.onAction(InpaintAction.Generate)
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Queued inpaint."
        } as InpaintUiState.Success
        assertEquals(1, queue.immediate.size)
        assertEquals("single", queue.immediate.first().second)
        assertEquals(false, state.queueRunning)
    }

    @Test
    fun `generate commits uncommitted raw input before enqueue`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        // Raw keystrokes only — no explicit commit.
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.onAction(InpaintAction.StepsChanged(TextFieldValue("30")))
        vm.onAction(InpaintAction.CfgChanged(TextFieldValue("9.5")))
        vm.onAction(InpaintAction.Generate)
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Queued inpaint."
        } as InpaintUiState.Success
        assertEquals("a cat", state.prompt)
        assertEquals(30, state.steps)
        assertEquals(9.5, state.cfgScale, 0.0)
        assertEquals(1, queue.immediate.size)
    }

    @Test
    fun `invalid raw keeps previous domain on commit`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.StepsChanged(TextFieldValue("abc")))
        vm.onAction(InpaintAction.CfgChanged(TextFieldValue("..")))
        vm.onAction(InpaintAction.DenoiseChanged(TextFieldValue("")))
        vm.onAction(InpaintAction.MaskBlurChanged(TextFieldValue("xyz")))
        vm.onAction(InpaintAction.CommitInputs)
        val state = vm.uiState.success()
        assertEquals(20, state.steps)
        assertEquals(7.0, state.cfgScale, 0.0)
        assertEquals(0.75, state.denoise, 0.0)
        assertEquals(4, state.maskBlur)
        // Raw text survives so typing can continue.
        assertEquals("abc", state.stepsDraft.text)
    }

    @Test
    fun `enqueue while running reports behind current job`() = runTest {        queue.snapshot.value =
            QueueSnapshot(running = true, executingJobId = "1", total = 1, origin = "q", stopReason = null)
        val vm = viewModel()
        vm.uiState.first { it is InpaintUiState.Success && it.queueRunning }
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.onAction(InpaintAction.Generate)
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Added behind current job"
        } as InpaintUiState.Success
        assertEquals(1, queue.immediate.size)
        assertEquals("single", queue.immediate.first().second)
        assertTrue(state.queueRunning)
    }

    @Test
    fun `enqueue carries shared module selection`() = runTest {
        val selection = FakeModulesSelectionRepository()
        selection.saveModules(listOf("ae.safetensors"))
        val vm = viewModel(selection = selection)
        vm.uiState.first {
            it is InpaintUiState.Success && it.modules == listOf("ae.safetensors")
        }
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.onAction(InpaintAction.Generate)
        vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Queued inpaint."
        }
        assertEquals(1, queue.immediate.size)
        val job = queue.immediate.first().first.first()
        assertEquals(listOf("ae.safetensors"), job.additionalModules)
        assertTrue(job.payloadJson.contains("forge_additional_modules"))
    }

    @Test
    fun `enqueue reads modules from selected checkpoint record`() = runTest {
        val selection = FakeModulesSelectionRepository()
        selection.saveModules(listOf("global.safetensors"))
        val modelParams = FakeModelParamsRepository()
        modelParams.saveForModel(
            "chk.safetensors",
            ModelLastUsed(additionalModules = listOf("per-model.safetensors")),
        )
        val vm = viewModel(selection = selection, modelParams = modelParams)
        vm.uiState.success()
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.onAction(InpaintAction.ModelChanged(TextFieldValue("chk.safetensors")))
        vm.onAction(InpaintAction.Generate)
        vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Queued inpaint."
        }
        assertEquals(1, queue.immediate.size)
        val job = queue.immediate.first().first.first()
        assertEquals(listOf("per-model.safetensors"), job.additionalModules)
        assertTrue(job.payloadJson.contains("forge_additional_modules"))
    }

    @Test
    fun `enqueue without checkpoint record falls back to shared selection`() = runTest {
        val selection = FakeModulesSelectionRepository()
        selection.saveModules(listOf("global.safetensors"))
        val vm = viewModel(selection = selection, modelParams = FakeModelParamsRepository())
        vm.uiState.success()
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.onAction(InpaintAction.ModelChanged(TextFieldValue("unknown.safetensors")))
        vm.onAction(InpaintAction.Generate)
        vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Queued inpaint."
        }
        assertEquals(1, queue.immediate.size)
        val job = queue.immediate.first().first.first()
        assertEquals(listOf("global.safetensors"), job.additionalModules)
    }

    @Test
    fun `refresh commits first model to draft and title when blank`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.models.isNotEmpty() && it.modelTitle == "m.safetensors"
        } as InpaintUiState.Success
        assertEquals("m.safetensors", state.modelTitle)
        assertEquals("m.safetensors", state.modelDraft.text)
    }

    @Test
    fun `refresh preserves typed model`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.ModelChanged(TextFieldValue("typed.safetensors")))
        vm.onAction(InpaintAction.CommitInputs)
        vm.uiState.first {
            it is InpaintUiState.Success && it.modelTitle == "typed.safetensors"
        }
        vm.onAction(InpaintAction.RefreshModels)
        val state = vm.uiState.first {
            it is InpaintUiState.Success && it.models.isNotEmpty()
        } as InpaintUiState.Success
        assertEquals("typed.safetensors", state.modelTitle)
        assertEquals("typed.safetensors", state.modelDraft.text)
    }

    @Test
    fun `empty catalog keeps model blank`() = runTest {
        val vm = viewModel(gen = FakeGenerationRepository(models = emptyList()))
        val state = vm.uiState.success()
        assertTrue(state.models.isEmpty())
        assertEquals("", state.modelTitle)
        assertEquals("", state.modelDraft.text)
    }

    @Test
    fun `generate executes committed first model`() = runTest {
        val vm = viewModel()
        vm.uiState.success()
        vm.onAction(InpaintAction.PickResult("content://img/1"))
        vm.uiState.first { it is InpaintUiState.Success && it.source != null }
        vm.onAction(InpaintAction.PromptChanged(TextFieldValue("a cat")))
        vm.uiState.first {
            it is InpaintUiState.Success && it.modelTitle == "m.safetensors"
        }
        vm.onAction(InpaintAction.Generate)
        vm.uiState.first {
            it is InpaintUiState.Success && it.statusMessage == "Queued inpaint."
        }
        assertEquals(1, queue.immediate.size)
        val job = queue.immediate.first().first.first()
        assertEquals("m.safetensors", job.modelTitle)
        assertTrue(job.payloadJson.contains("sd_model_checkpoint"))
        assertTrue(job.payloadJson.contains("m.safetensors"))
    }
}
