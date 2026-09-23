package com.forgery.app.feature.generate.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.AnalyzeHandoffRepository
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.HrSettingsRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.data.buildTxt2ImgPayload
import com.forgery.app.core.data.payloadToJsonString
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GenerateViewModel @Inject constructor(
    private val generationRepository: GenerationRepository,
    private val queueRepository: QueueRepository,
    private val promptDrafts: PromptDraftRepository,
    private val connectionRepository: ConnectionRepository,
    private val hrSettings: HrSettingsRepository,
    private val modulesSelection: ModulesSelectionRepository,
    private val handoff: AnalyzeHandoffRepository,
) : ViewModel() {

    /** Non-text, non-HR, non-modules params; prompt text + mode + HR + modules live in repositories. */
    private val rest = MutableStateFlow(defaultParamsFor(GenerationMode.SDXL))

    private data class ModeData(
        val mode: GenerationMode,
        val prompt: String,
        val negativePrompt: String,
        val hr: HrSettings,
        val modules: List<String>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val modeData = promptDrafts.observeActiveMode().flatMapLatest { mode ->
        combine(
            promptDrafts.observeDraft(mode),
            hrSettings.observeHr(mode),
            modulesSelection.observeModules(mode),
        ) { draft, hr, modules ->
            ModeData(mode, draft.prompt, draft.negativePrompt, hr, modules)
        }
    }

    private data class ModelsState(
        val models: List<String> = emptyList(),
        val modules: List<String> = emptyList(),
        val upscalers: List<String> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val modelsState = MutableStateFlow(ModelsState())
    private val samplers = MutableStateFlow<List<String>>(emptyList())

    private data class Misc(
        val status: String? = null,
        val snapshot: QueueSnapshot? = null,
        val engine: EngineState = EngineState.Uninitialized,
        val confirmUnload: Boolean = false,
    ) {
        val queueRunning: Boolean get() = snapshot?.running == true
    }

    private val statusMessage = MutableStateFlow<String?>(null)
    private val engine = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    private val confirmUnload = MutableStateFlow(false)
    private val misc = combine(
        statusMessage,
        queueRepository.observeSnapshot(),
        engine,
        confirmUnload
    ) { msg, snap, eng, unload ->
        Misc(msg, snap, eng, unload)
    }

    val uiState: StateFlow<GenerateUiState> = combine(
        rest,
        modeData,
        modelsState,
        samplers,
        misc
    ) { r, md, ms, s, mi ->
        GenerateUiState.Success(
            params = r.copy(
                prompt = md.prompt,
                negativePrompt = md.negativePrompt,
                mode = md.mode,
                enableHr = md.hr.enable,
                hrUpscaler = md.hr.upscaler,
                hrScale = md.hr.scale,
                hrSteps = md.hr.steps,
                hrDenoise = md.hr.denoise,
                hrCfg = md.hr.cfg,
                additionalModules = md.modules,
            ),
            models = ms.models,
            modules = ms.modules,
            upscalers = ms.upscalers,
            modelsLoading = ms.loading,
            modelsError = ms.error,
            samplers = s,
            queueRunning = mi.queueRunning,
            queueSnapshot = mi.snapshot,
            statusMessage = mi.status,
            engine = mi.engine,
            hr = md.hr,
            confirmUnload = mi.confirmUnload,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GenerateUiState.Loading,
    )

    init {
        // Legacy boot.js auto-connect: silent init only when configured.
        viewModelScope.launch {
            val config = connectionRepository.observe().first()
            if (config.isConfigured) initializeEngine(silent = true)
        }
        handoff.consume()?.let { r ->
            val cur = rest.value
            rest.value = cur.copy(
                steps = r.steps ?: cur.steps,
                sampler = r.sampler ?: cur.sampler,
                scheduler = r.scheduler ?: cur.scheduler,
                cfgScale = r.cfgScale ?: cur.cfgScale,
                distilledCfgScale = r.distilledCfgScale ?: cur.distilledCfgScale,
                seed = r.seed ?: cur.seed,
                width = r.width ?: cur.width,
                height = r.height ?: cur.height,
                modelTitle = r.modelTitle ?: cur.modelTitle,
            )
            r.additionalModules?.let { modules ->
                viewModelScope.launch {
                    // Handoff targets the active mode (Analyze restores into current mode).
                    val mode = promptDrafts.observeActiveMode().first()
                    modulesSelection.saveModules(mode, modules)
                }
            }
        }
    }

    fun onAction(action: GenerateAction) {
        val current = (uiState.value as? GenerateUiState.Success) ?: return
        val p = current.params
        when (action) {
            is GenerateAction.PromptChanged -> update { setPrompt(p.mode, action.value, p.negativePrompt) }
            is GenerateAction.NegChanged -> update { setPrompt(p.mode, p.prompt, action.value) }
            is GenerateAction.ModeChanged -> viewModelScope.launch {
                rest.value = defaultParamsFor(action.mode)
                promptDrafts.setActiveMode(action.mode)
            }
            is GenerateAction.ModelChanged -> rest.value = rest.value.copy(modelTitle = action.value)
            is GenerateAction.ModulesChanged -> viewModelScope.launch {
                modulesSelection.saveModules(p.mode, action.value)
            }
            is GenerateAction.SamplerChanged -> rest.value = rest.value.copy(sampler = action.value)
            is GenerateAction.SchedulerChanged -> rest.value = rest.value.copy(scheduler = action.value)
            is GenerateAction.StepsChanged -> rest.value = rest.value.copy(steps = action.value)
            is GenerateAction.CfgChanged -> rest.value = rest.value.copy(cfgScale = action.value)
            is GenerateAction.DistilledChanged -> rest.value =
                rest.value.copy(distilledCfgScale = action.value)
            is GenerateAction.SizeChanged -> rest.value =
                rest.value.copy(width = action.width, height = action.height)
            is GenerateAction.SeedChanged -> rest.value = rest.value.copy(seed = action.value)
            is GenerateAction.BatchSizeChanged -> rest.value = rest.value.copy(batchSize = action.value)
            is GenerateAction.BatchCountChanged -> rest.value = rest.value.copy(batchCount = action.value)
            GenerateAction.HrToggled -> saveHr(p.mode, current.hr.copy(enable = !current.hr.enable))
            is GenerateAction.HrUpscalerChanged -> saveHr(p.mode, current.hr.copy(upscaler = action.value))
            is GenerateAction.HrScaleChanged -> saveHr(p.mode, current.hr.copy(scale = action.value))
            is GenerateAction.HrStepsChanged -> saveHr(p.mode, current.hr.copy(steps = action.value))
            is GenerateAction.HrDenoiseChanged -> saveHr(p.mode, current.hr.copy(denoise = action.value))
            is GenerateAction.HrCfgChanged -> saveHr(p.mode, current.hr.copy(cfg = action.value))
            GenerateAction.RefreshModels -> initializeEngine(silent = true)
            GenerateAction.InitializeEngine -> initializeEngine(silent = false)
            GenerateAction.StageQueue -> stageQueue()
            GenerateAction.Generate -> generateNow()
            GenerateAction.DismissStatus -> statusMessage.value = null
            GenerateAction.RequestUnloadModel -> confirmUnload.value = true
            GenerateAction.ConfirmUnloadModel -> confirmUnloadModel()
            GenerateAction.DismissUnload -> confirmUnload.value = false
        }
    }

    private inline fun update(crossinline block: suspend PromptDraftRepository.() -> Unit) {
        viewModelScope.launch { promptDrafts.block() }
    }

    private fun saveHr(mode: GenerationMode, hr: HrSettings) {
        viewModelScope.launch { hrSettings.saveHr(mode, hr) }
    }

    /**
     * Ports legacy `connect(silent)` (network.js): probe `/sdapi/v1/sd-models`,
     * then load the catalog. Silent mode skips the INITIALIZING/FAILED visuals
     * (boot auto-connect, RETRY) but still lands on INITIALIZED on success.
     */
    private fun initializeEngine(silent: Boolean) {
        viewModelScope.launch {
            if (engine.value == EngineState.Initializing) return@launch
            if (!silent) engine.value = EngineState.Initializing
            modelsState.value = modelsState.value.copy(loading = true, error = null)
            val modelsResult = generationRepository.fetchSdModels()
            val modulesResult = generationRepository.fetchModules()
            val samplersResult = generationRepository.fetchSamplers()
            val upscalersResult = generationRepository.fetchUpscalers()
            when (modelsResult) {
                is Result.Success -> {
                    modelsState.value = modelsState.value.copy(models = modelsResult.data)
                    engine.value = EngineState.Initialized(modelsResult.data.size)
                }
                is Result.Error -> {
                    val msg = friendlyNetworkError(modelsResult.message)
                    modelsState.value = modelsState.value.copy(error = msg)
                    if (!silent) {
                        engine.value = EngineState.Failed(msg)
                        delay(2_000)
                        if (engine.value is EngineState.Failed) {
                            engine.value = EngineState.Uninitialized
                        }
                    }
                }
                is Result.Loading -> Unit
            }
            if (samplersResult is Result.Success) samplers.value = samplersResult.data
            if (modulesResult is Result.Success) {
                modelsState.value = modelsState.value.copy(modules = modulesResult.data)
                // Drop persisted selections the server no longer offers (renamed/deleted files).
                val known = modulesResult.data.toSet()
                val mode = promptDrafts.observeActiveMode().first()
                val current = modulesSelection.observeModules(mode).first()
                val pruned = current.filter { it in known }
                if (pruned.size != current.size) modulesSelection.saveModules(mode, pruned)
            }
            if (upscalersResult is Result.Success) {
                modelsState.value = modelsState.value.copy(upscalers = upscalersResult.data)
            }
            modelsState.value = modelsState.value.copy(loading = false)
        }
    }

    /** Legacy readable errors: timeout / unreachable (network.js connect catch). */
    private fun friendlyNetworkError(message: String): String = when {
        message.contains("timed out", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("AbortError") -> "Connection Timed Out"
        message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("Failed to fetch", ignoreCase = true) ||
            message.contains("No address associated", ignoreCase = true) -> "Host Unreachable"
        else -> message
    }

    private fun buildJob(p: GenerationParams): QueueJob {
        val payload = buildTxt2ImgPayload(p)
        return QueueJob(
            id = UUID.randomUUID().toString(),
            desc = p.prompt.take(80),
            mode = "txt",
            modelTitle = p.modelTitle,
            payloadJson = payloadToJsonString(payload),
            additionalModules = p.additionalModules,
        )
    }

    private suspend fun requirePrompt(): GenerationParams? {
        val current = (uiState.value as? GenerateUiState.Success) ?: return null
        val p = current.params
        if (p.prompt.isBlank()) {
            statusMessage.value = "Prompt is empty."
            return null
        }
        return p
    }

    /** UNLOAD MODEL button: free VRAM after explicit confirmation. */
    private fun confirmUnloadModel() {
        confirmUnload.value = false
        viewModelScope.launch {
            when (val result = generationRepository.unloadModel()) {
                is Result.Success -> statusMessage.value = "Model unloaded from VRAM."
                is Result.Error -> statusMessage.value = "Unload failed: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    /** QUEUE button (1/4): persist without starting execution. */
    private fun stageQueue() {
        viewModelScope.launch {
            val wasRunning =
                (uiState.value as? GenerateUiState.Success)?.queueRunning == true
            val p = requirePrompt() ?: return@launch
            try {
                queueRepository.stage(listOf(buildJob(p)), origin = "single")
                statusMessage.value = if (wasRunning) {
                    "Added to end of queue"
                } else {
                    "Staged: ${p.prompt.take(60)} — start from QUE"
                }
            } catch (e: Exception) {
                statusMessage.value = "Queue failed: ${e.message}"
            }
        }
    }

    /**
     * GENERATE button (3/4): execute immediately; when a batch is already
     * running the job is inserted right after the current one.
     */
    private fun generateNow() {
        viewModelScope.launch {
            val wasRunning =
                (uiState.value as? GenerateUiState.Success)?.queueRunning == true
            val p = requirePrompt() ?: return@launch
            try {
                queueRepository.enqueueImmediate(listOf(buildJob(p)), origin = "single")
                statusMessage.value = if (wasRunning) {
                    "Added behind current job"
                } else {
                    "Started: ${p.prompt.take(60)}"
                }
            } catch (e: Exception) {
                statusMessage.value = "Queue failed: ${e.message}"
            }
        }
    }
}

sealed interface GenerateAction {
    data class PromptChanged(val value: String) : GenerateAction
    data class NegChanged(val value: String) : GenerateAction
    data class ModeChanged(val mode: GenerationMode) : GenerateAction
    data class ModelChanged(val value: String) : GenerateAction
    data class ModulesChanged(val value: List<String>) : GenerateAction
    data class SamplerChanged(val value: String) : GenerateAction
    data class SchedulerChanged(val value: String) : GenerateAction
    data class StepsChanged(val value: Int) : GenerateAction
    data class CfgChanged(val value: Double) : GenerateAction
    data class DistilledChanged(val value: Double) : GenerateAction
    data class SizeChanged(val width: Int, val height: Int) : GenerateAction
    data class SeedChanged(val value: Long) : GenerateAction
    data class BatchSizeChanged(val value: Int) : GenerateAction
    data class BatchCountChanged(val value: Int) : GenerateAction
    data object HrToggled : GenerateAction
    data class HrUpscalerChanged(val value: String) : GenerateAction
    data class HrScaleChanged(val value: Double) : GenerateAction
    data class HrStepsChanged(val value: Int) : GenerateAction
    data class HrDenoiseChanged(val value: Double) : GenerateAction
    data class HrCfgChanged(val value: Double) : GenerateAction
    data object RefreshModels : GenerateAction
    data object InitializeEngine : GenerateAction
    data object StageQueue : GenerateAction
    data object Generate : GenerateAction
    data object DismissStatus : GenerateAction
    data object RequestUnloadModel : GenerateAction
    data object ConfirmUnloadModel : GenerateAction
    data object DismissUnload : GenerateAction
}
