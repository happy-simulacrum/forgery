package com.forgery.app.feature.generate.impl

import com.forgery.app.core.common.Result
import com.forgery.app.core.data.AnalyzeHandoffRepository
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.HrSettingsRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import com.forgery.app.core.model.RestoredParams
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeConnectionRepository(
    configured: Boolean = false,
) : ConnectionRepository {
    private val config = MutableStateFlow(ConnectionConfig(isConfigured = configured))
    override fun observe(): Flow<ConnectionConfig> = config.asStateFlow()
    override suspend fun save(c: ConnectionConfig) {
        config.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> =
        MutableStateFlow(UiPrefs()).asStateFlow()
    override suspend fun saveUiPrefs(prefs: UiPrefs) = Unit
    override suspend fun reset() {
        config.value = ConnectionConfig()
    }
}

private class FakeGenerationRepository : GenerationRepository {
    var models = listOf("a.safetensors", "b.safetensors")
    var samplers = listOf("Euler", "DPM++ 2M")
    var modelsError: String? = null
    var modelsGate: CompletableDeferred<Unit>? = null
    override suspend fun fetchSdModels(): Result<List<String>> {
        modelsGate?.await()
        return modelsError?.let { Result.Error(it) } ?: Result.Success(models)
    }
    override suspend fun fetchSamplers(): Result<List<String>> = Result.Success(samplers)
    override suspend fun fetchUpscalers(): Result<List<String>> = Result.Success(listOf("Latent"))
    override suspend fun fetchLoras(): Result<List<com.forgery.app.core.model.LoraItem>> =
        Result.Success(emptyList())
    override suspend fun fetchLoraSidecar(basePath: String): Result<com.forgery.app.core.model.LoraMeta> =
        Result.Success(com.forgery.app.core.model.LoraMeta())
    override suspend fun fetchPromptStyles(): Result<List<com.forgery.app.core.model.StylePreset>> =
        Result.Success(emptyList())
    override suspend fun ensureModel(title: String, resetVaeForInpaint: Boolean): Result<Unit> =
        Result.Success(Unit)
    override suspend fun txt2img(payload: Map<String, Any?>): Result<List<String>> =
        Result.Success(listOf("img"))
    override suspend fun img2img(payload: Map<String, Any?>): Result<List<String>> =
        Result.Success(listOf("img"))
    override suspend fun progress(): Result<Double> = Result.Success(0.0)
    var unloads = 0
    var unloadError: String? = null
    override suspend fun unloadModel(): Result<Unit> {
        unloads++
        return unloadError?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}

private class FakeQueueRepository : QueueRepository {
    private val snapshot = MutableStateFlow<QueueSnapshot?>(null)
    val staged = mutableListOf<Pair<List<QueueJob>, String>>()
    val immediate = mutableListOf<Pair<List<QueueJob>, String>>()
    var starts = 0
    var clearsCompleted = 0
    var clearsPending = 0
    override fun observeSnapshot(): Flow<QueueSnapshot?> = snapshot.asStateFlow()
    fun emitSnapshot(value: QueueSnapshot?) {
        snapshot.value = value
    }
    override fun observeJobs(): Flow<List<QueueJob>> =
        MutableStateFlow<List<QueueJob>>(emptyList()).asStateFlow()
    override fun observeResults(): Flow<List<QueueResult>> =
        MutableStateFlow<List<QueueResult>>(emptyList()).asStateFlow()
    override suspend fun stage(jobs: List<QueueJob>, origin: String) {
        staged += jobs to origin
    }
    override suspend fun start() {
        starts++
        snapshot.value = QueueSnapshot(running = true, currentIndex = 0, total = 1, origin = "q", stopReason = null)
    }
    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) {
        immediate += jobs to origin
        snapshot.value = QueueSnapshot(running = true, currentIndex = 0, total = jobs.size, origin = origin, stopReason = null)
    }
    override suspend fun cancel() {
        snapshot.value = snapshot.value?.copy(running = false)
    }
    override suspend fun clearCompleted() {
        clearsCompleted++
    }
    override suspend fun clearPending() {
        clearsPending++
    }
    override suspend fun removeJob(jobId: String): Boolean = true
    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean = true
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
    override suspend fun appendPrompt(mode: GenerationMode, text: String, negativeText: String) {
        val cur = flowOf(mode).value
        flowOf(mode).value = PromptDraft(
            (cur.prompt + " " + text).trim(),
            (cur.negativePrompt + " " + negativeText).trim(),
        )
    }
    override suspend fun setActiveMode(mode: GenerationMode) {
        active.value = mode
    }
}

private class FakeHrSettingsRepository : HrSettingsRepository {
    private val stored = mutableMapOf<GenerationMode, MutableStateFlow<HrSettings>>()

    private fun flowOf(mode: GenerationMode) =
        stored.getOrPut(mode) { MutableStateFlow(HrSettings()) }

    override fun observeHr(mode: GenerationMode): Flow<HrSettings> = flowOf(mode).asStateFlow()

    override suspend fun saveHr(mode: GenerationMode, hr: HrSettings) {
        flowOf(mode).value = hr
    }

    fun current(mode: GenerationMode) = flowOf(mode).value
}

/** In-memory handoff fake: real repo doubles as fake (set/consume one-shot semantics). */
private typealias FakeAnalyzeHandoff = AnalyzeHandoffRepository

class GenerateViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val queue = FakeQueueRepository()
    private val drafts = FakePromptDraftRepository()
    private val hrRepo = FakeHrSettingsRepository()
    private fun viewModel(
        gen: FakeGenerationRepository = FakeGenerationRepository(),
        configured: Boolean = false,
        handoff: AnalyzeHandoffRepository = FakeAnalyzeHandoff(),
    ) = GenerateViewModel(gen, queue, drafts, FakeConnectionRepository(configured), hrRepo, handoff)

    @Test
    fun `starts uninitialized without catalog when not configured`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.first {
            it is GenerateUiState.Success
        } as GenerateUiState.Success
        assertEquals(EngineState.Uninitialized, state.engine)
        assertTrue(state.models.isEmpty())
    }

    @Test
    fun `auto-initializes silently when configured`() = runTest {
        val vm = viewModel(configured = true)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.engine is EngineState.Initialized
        } as GenerateUiState.Success
        assertEquals(listOf("a.safetensors", "b.safetensors"), state.models)
        assertEquals(listOf("Euler", "DPM++ 2M"), state.samplers)
        assertEquals(EngineState.Initialized(2), state.engine)
    }

    @Test
    fun `tap transitions initializing to initialized`() = runTest {
        val gen = FakeGenerationRepository()
        gen.modelsGate = CompletableDeferred()
        val vm = viewModel(gen)
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.InitializeEngine)
        vm.uiState.first {
            it is GenerateUiState.Success && it.engine == EngineState.Initializing
        }
        gen.modelsGate?.complete(Unit)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.engine is EngineState.Initialized
        } as GenerateUiState.Success
        assertEquals(EngineState.Initialized(2), state.engine)
        assertEquals(listOf("a.safetensors", "b.safetensors"), state.models)
    }

    @Test
    fun `failure shows failed then reverts to uninitialized`() = runTest {
        val gen = FakeGenerationRepository()
        gen.modelsError = "Unable to resolve host \"x\": No address associated with hostname"
        val vm = viewModel(gen)
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.InitializeEngine)
        val failed = vm.uiState.first {
            it is GenerateUiState.Success && it.engine is EngineState.Failed
        } as GenerateUiState.Success
        assertEquals("Host Unreachable", (failed.engine as EngineState.Failed).message)
        val reverted = vm.uiState.first {
            it is GenerateUiState.Success && it.engine == EngineState.Uninitialized
        } as GenerateUiState.Success
        assertEquals(EngineState.Uninitialized, reverted.engine)
    }

    @Test
    fun `models load on init when configured`() = runTest {
        val vm = viewModel(configured = true)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.models.isNotEmpty()
        } as GenerateUiState.Success
        assertEquals(listOf("a.safetensors", "b.safetensors"), state.models)
        assertEquals(listOf("Euler", "DPM++ 2M"), state.samplers)
    }

    @Test
    fun `mode switch applies presets and keeps per-mode drafts`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.PromptChanged("cat"))
        vm.uiState.first {
            it is GenerateUiState.Success && it.params.prompt == "cat"
        }
        vm.onAction(GenerateAction.ModeChanged(GenerationMode.QWEN))
        val qwen = vm.uiState.first {
            it is GenerateUiState.Success && it.params.mode == GenerationMode.QWEN
        } as GenerateUiState.Success
        assertEquals("", qwen.params.prompt)
        assertEquals(8, qwen.params.steps)
        assertEquals(1.0, qwen.params.cfgScale, 1e-9)
        vm.onAction(GenerateAction.ModeChanged(GenerationMode.SDXL))
        val sdxl = vm.uiState.first {
            it is GenerateUiState.Success &&
                it.params.mode == GenerationMode.SDXL && it.params.prompt == "cat"
        } as GenerateUiState.Success
        assertEquals("cat", sdxl.params.prompt)
    }

    @Test
    fun `generate executes immediately with payload`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.PromptChanged("a cat"))
        vm.onAction(GenerateAction.ModelChanged("a.safetensors"))
        vm.onAction(GenerateAction.Generate)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage?.startsWith("Started") == true
        } as GenerateUiState.Success
        assertEquals(1, queue.immediate.size)
        val (jobs, origin) = queue.immediate.first()
        assertEquals("single", origin)
        assertEquals(1, jobs.size)
        assertTrue(jobs.first().payloadJson.contains("a cat"))
        assertEquals("a.safetensors", jobs.first().modelTitle)
        assertTrue(state.queueRunning)
        assertTrue(queue.staged.isEmpty())
    }

    @Test
    fun `queue stages without starting`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.PromptChanged("a cat"))
        vm.onAction(GenerateAction.StageQueue)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage?.startsWith("Staged") == true
        } as GenerateUiState.Success
        assertEquals(1, queue.staged.size)
        assertEquals("single", queue.staged.first().second)
        assertTrue(queue.immediate.isEmpty())
        assertEquals(0, queue.starts)
        assertEquals(false, state.queueRunning)
    }

    @Test
    fun `generate while running enqueues behind current job`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.PromptChanged("first cat"))
        vm.uiState.first {
            it is GenerateUiState.Success && it.params.prompt == "first cat"
        }
        vm.onAction(GenerateAction.Generate)
        vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage?.startsWith("Started") == true
        }
        vm.uiState.first {
            it is GenerateUiState.Success && it.queueRunning
        }
        vm.onAction(GenerateAction.PromptChanged("second cat"))
        vm.uiState.first {
            it is GenerateUiState.Success && it.params.prompt == "second cat"
        }
        vm.onAction(GenerateAction.Generate)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage == "Added behind current job"
        } as GenerateUiState.Success
        assertEquals(2, queue.immediate.size)
        assertTrue(queue.staged.isEmpty())
        assertTrue(state.queueRunning)
    }

    @Test
    fun `stage while running appends to end of queue`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.PromptChanged("first cat"))
        vm.uiState.first {
            it is GenerateUiState.Success && it.params.prompt == "first cat"
        }
        vm.onAction(GenerateAction.Generate)
        vm.uiState.first {
            it is GenerateUiState.Success && it.queueRunning
        }
        vm.onAction(GenerateAction.PromptChanged("second cat"))
        vm.uiState.first {
            it is GenerateUiState.Success && it.params.prompt == "second cat"
        }
        vm.onAction(GenerateAction.StageQueue)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage == "Added to end of queue"
        } as GenerateUiState.Success
        assertEquals(1, queue.immediate.size)
        assertEquals(1, queue.staged.size)
        assertEquals("single", queue.staged.first().second)
        assertTrue(state.queueRunning)
    }

    @Test
    fun `empty prompt is rejected`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.Generate)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage != null
        } as GenerateUiState.Success
        assertEquals("Prompt is empty.", state.statusMessage)
        assertTrue(queue.immediate.isEmpty())
        assertTrue(queue.staged.isEmpty())
    }

    @Test
    fun `queue snapshot progress maps to uiState`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        queue.emitSnapshot(
            QueueSnapshot(
                running = true,
                currentIndex = 1,
                total = 4,
                origin = "q",
                stopReason = null,
                jobProgress = 0.42f,
            )
        )
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.queueSnapshot?.jobProgress == 0.42f
        } as GenerateUiState.Success
        assertTrue(state.queueRunning)
        val snap = state.queueSnapshot!!
        assertEquals(0.42f, snap.jobProgress, 1e-6f)
        assertEquals(1, snap.currentIndex)
        assertEquals(4, snap.total)
        // Bar math: 42% label, job 2/4.
        assertEquals(42, ((snap.jobProgress * 100).toInt()))
    }

    @Test
    fun `hr toggle persists per mode`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.HrToggled)
        val enabled = vm.uiState.first {
            it is GenerateUiState.Success && it.hr.enable
        } as GenerateUiState.Success
        assertEquals(true, enabled.hr.enable)
        assertEquals(true, hrRepo.current(GenerationMode.SDXL).enable)
        vm.onAction(GenerateAction.ModeChanged(GenerationMode.FLUX))
        val flux = vm.uiState.first {
            it is GenerateUiState.Success && it.params.mode == GenerationMode.FLUX
        } as GenerateUiState.Success
        assertEquals(false, flux.hr.enable)
        vm.onAction(GenerateAction.ModeChanged(GenerationMode.SDXL))
        val back = vm.uiState.first {
            it is GenerateUiState.Success &&
                it.params.mode == GenerationMode.SDXL && it.hr.enable
        } as GenerateUiState.Success
        assertEquals(true, back.hr.enable)
    }

    @Test
    fun `hr fields flow into enqueue payload`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.PromptChanged("a cat"))
        vm.onAction(GenerateAction.HrToggled)
        vm.onAction(GenerateAction.HrScaleChanged(2.0))
        vm.onAction(GenerateAction.HrCfgChanged(2.5))
        vm.uiState.first {
            it is GenerateUiState.Success && it.hr.enable && it.hr.scale == 2.0
        }
        vm.onAction(GenerateAction.Generate)
        vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage?.startsWith("Started") == true
        }
        val payload = queue.immediate.first().first.first().payloadJson
        assertTrue(payload.contains("\"enable_hr\":true"))
        assertTrue(payload.contains("\"hr_scale\":2.0"))
        assertTrue(payload.contains("\"hr_cfg\":2.5"))
        assertTrue(payload.contains("\"hr_second_pass_steps\":6"))
        assertTrue(payload.contains("\"denoising_strength\":0.4"))
    }

    @Test
    fun `handoff consume overlays restored fields`() = runTest {
        val handoff = FakeAnalyzeHandoff()
        handoff.set(
            RestoredParams(
                steps = 30,
                sampler = "DPM++ 2M",
                scheduler = "Karras",
                cfgScale = 8.5,
                seed = 123L,
                width = 768,
                height = 512,
                modelTitle = "a.safetensors",
            )
        )
        val vm = viewModel(handoff = handoff)
        val state = vm.uiState.first {
            it is GenerateUiState.Success
        } as GenerateUiState.Success
        assertEquals(30, state.params.steps)
        assertEquals("DPM++ 2M", state.params.sampler)
        assertEquals("Karras", state.params.scheduler)
        assertEquals(8.5, state.params.cfgScale, 1e-9)
        assertEquals(123L, state.params.seed)
        assertEquals(768, state.params.width)
        assertEquals(512, state.params.height)
        assertEquals("a.safetensors", state.params.modelTitle)
        assertEquals(1, state.params.batchSize)
        assertEquals(1, state.params.batchCount)
    }

    @Test
    fun `handoff null fields do not overwrite`() = runTest {
        val handoff = FakeAnalyzeHandoff()
        handoff.set(RestoredParams(steps = 30))
        val vm = viewModel(handoff = handoff)
        val state = vm.uiState.first {
            it is GenerateUiState.Success
        } as GenerateUiState.Success
        assertEquals(30, state.params.steps)
        assertEquals(-1L, state.params.seed)
        assertEquals("Euler a", state.params.sampler)
        assertEquals("Karras", state.params.scheduler)
        assertEquals(7.0, state.params.cfgScale, 1e-9)
        assertEquals(1024, state.params.width)
        assertEquals(1024, state.params.height)
        assertEquals("", state.params.modelTitle)
    }

    @Test
    fun `handoff second consume is no-op`() = runTest {
        val handoff = FakeAnalyzeHandoff()
        handoff.set(RestoredParams(steps = 30, seed = 123L))
        val first = viewModel(handoff = handoff)
        val firstState = first.uiState.first {
            it is GenerateUiState.Success
        } as GenerateUiState.Success
        assertEquals(30, firstState.params.steps)
        assertEquals(123L, firstState.params.seed)
        val second = viewModel(handoff = handoff)
        val secondState = second.uiState.first {
            it is GenerateUiState.Success
        } as GenerateUiState.Success
        assertEquals(20, secondState.params.steps)
        assertEquals(-1L, secondState.params.seed)
    }

    @Test
    fun `distilled action updates params`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.DistilledChanged(5.0))
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.params.distilledCfgScale == 5.0
        } as GenerateUiState.Success
        assertEquals(5.0, state.params.distilledCfgScale, 1e-9)
    }

    @Test
    fun `size keeps non-64 grid values as is`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.SizeChanged(1254, 836))
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.params.width == 1254 && it.params.height == 836
        } as GenerateUiState.Success
        assertEquals(1254, state.params.width)
        assertEquals(836, state.params.height)
    }

    @Test
    fun `unload request shows confirmation`() = runTest {
        val vm = viewModel()
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.RequestUnloadModel)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.confirmUnload
        } as GenerateUiState.Success
        assertTrue(state.confirmUnload)
    }

    @Test
    fun `unload confirm delegates and reports status`() = runTest {
        val gen = FakeGenerationRepository()
        val vm = viewModel(gen)
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.RequestUnloadModel)
        vm.uiState.first { it is GenerateUiState.Success && it.confirmUnload }
        vm.onAction(GenerateAction.ConfirmUnloadModel)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage == "Model unloaded from VRAM."
        } as GenerateUiState.Success
        assertEquals(1, gen.unloads)
        assertFalse(state.confirmUnload)
    }

    @Test
    fun `unload error reports failure`() = runTest {
        val gen = FakeGenerationRepository()
        gen.unloadError = "boom"
        val vm = viewModel(gen)
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.ConfirmUnloadModel)
        val state = vm.uiState.first {
            it is GenerateUiState.Success && it.statusMessage == "Unload failed: boom"
        } as GenerateUiState.Success
        assertEquals(1, gen.unloads)
        assertFalse(state.confirmUnload)
    }

    @Test
    fun `unload dismiss closes without calling`() = runTest {
        val gen = FakeGenerationRepository()
        val vm = viewModel(gen)
        vm.uiState.first { it is GenerateUiState.Success }
        vm.onAction(GenerateAction.RequestUnloadModel)
        vm.uiState.first { it is GenerateUiState.Success && it.confirmUnload }
        vm.onAction(GenerateAction.DismissUnload)
        val state = vm.uiState.value as GenerateUiState.Success
        assertFalse(state.confirmUnload)
        assertEquals(0, gen.unloads)
    }
}
