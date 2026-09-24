package com.forgery.app.feature.generate.impl

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.AnalyzeHandoffRepository
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.data.DefaultsRepository
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.HrSettingsRepository
import com.forgery.app.core.data.ModelParamsRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.data.buildTxt2ImgPayload
import com.forgery.app.core.data.payloadToJsonString
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueSnapshot
import com.forgery.app.core.model.RestoredParams
import com.forgery.app.core.ui.adoptExternal
import com.forgery.app.core.ui.parseSeedInput
import com.forgery.app.core.ui.parseSizeInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Complete integer or null (empty/garbage never commits). */
private fun parseIntComplete(raw: String): Int? = raw.trim().toIntOrNull()

/**
 * Complete decimal or null. Trailing-dot partials like "7." return null so
 * in-progress typing keeps the previous domain value.
 */
private fun parseDoubleComplete(raw: String): Double? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t.endsWith(".")) return null
    if (t == "." || t == "-" || t == "-." || t == "+.") return null
    return t.toDoubleOrNull()
}

private fun parseBoundedInt(raw: String, min: Int, max: Int): Int? {
    val v = raw.trim().toIntOrNull() ?: return null
    return v.coerceIn(min, max)
}

private fun parseBoundedDouble(raw: String, min: Double, max: Double): Double? {
    val v = parseDoubleComplete(raw) ?: return null
    return v.coerceIn(min, max)
}

private fun textField(text: String): TextFieldValue =
    TextFieldValue(text, TextRange(text.length))

@HiltViewModel
class GenerateViewModel @Inject constructor(
    private val generationRepository: GenerationRepository,
    private val queueRepository: QueueRepository,
    private val promptDrafts: PromptDraftRepository,
    private val connectionRepository: ConnectionRepository,
    private val hrSettings: HrSettingsRepository,
    private val modulesSelection: ModulesSelectionRepository,
    private val defaults: DefaultsRepository,
    private val handoff: AnalyzeHandoffRepository,
    private val modelParams: ModelParamsRepository,
) : ViewModel() {

    /** Non-text, non-HR, non-modules params; prompt text + HR + modules live in repositories. */
    private val rest = MutableStateFlow(defaultParams())

    /** Raw in-memory text state (VM is source of truth for what the user sees). */
    private val inputs = MutableStateFlow(GenerateInputs())

    /** Last prompt/neg text written to the draft repo (echo suppression + external-merge). */
    private var lastWrittenPrompt: String? = null
    private var lastWrittenNeg: String? = null

    /** Last HR value seen/saved (echo suppression for HR repo emissions). */
    private var lastHrSaved: HrSettings? = null

    private data class DraftData(
        val prompt: String,
        val negativePrompt: String,
        val hr: HrSettings,
        val modules: List<String>,
    )

    private val draftData = combine(
        promptDrafts.observeDraft(),
        hrSettings.observeHr(),
        modulesSelection.observeModules(),
    ) { draft, hr, modules ->
        DraftData(draft.prompt, draft.negativePrompt, hr, modules)
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
    private val serverSchedulers = MutableStateFlow<List<String>>(emptyList())

    private data class CatalogLists(
        val samplers: List<String> = emptyList(),
        val schedulers: List<String> = emptyList(),
    )

    private val catalog = combine(samplers, serverSchedulers, ::CatalogLists)

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

    private data class Base(
        val rest: GenerationParams,
        val draft: DraftData,
        val models: ModelsState,
        val catalog: CatalogLists,
        val misc: Misc,
    )

    private val base = combine(
        rest,
        draftData,
        modelsState,
        catalog,
        misc,
    ) { r, dd, ms, c, mi ->
        Base(r, dd, ms, c, mi)
    }

    val uiState: StateFlow<GenerateUiState> = combine(
        base,
        inputs,
    ) { b, inp ->
        val r = b.rest
        val md = b.draft
        val ms = b.models
        val c = b.catalog
        val mi = b.misc
        GenerateUiState.Success(
            params = r.copy(
                prompt = inp.prompt.text,
                negativePrompt = inp.negativePrompt.text,
                enableHr = md.hr.enable,
                hrUpscaler = md.hr.upscaler,
                hrScale = md.hr.scale,
                hrSteps = md.hr.steps,
                hrDenoise = md.hr.denoise,
                hrCfg = md.hr.cfg,
                additionalModules = md.modules,
            ),
            inputs = inp,
            models = ms.models,
            modules = ms.modules,
            upscalers = ms.upscalers,
            modelsLoading = ms.loading,
            modelsError = ms.error,
            samplers = c.samplers,
            serverSchedulers = c.schedulers,
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
        viewModelScope.launch {
            draftData.collect { dd -> mergeExternal(dd) }
        }
        viewModelScope.launch {
            // Saved prompt defaults first, per-model snapshot over the
            // hardcoded defaults, Analyze handoff wins when present.
            // Prompts are owned by drafts + GenDefaults and never touched
            // by model entries.
            applyDefaults()
            applyModelEntry(rest.value.modelTitle)
            handoff.observe().collect { r ->
                if (r != null) {
                    applyHandoff(r)
                    handoff.consume()
                    seedNumericInputsFromRest()
                }
            }
        }
    }

    /** Overlays Analyze handoff fields over current params (cold start + warm reuse). */
    private suspend fun applyHandoff(r: RestoredParams) {
        val cur = rest.value
        rest.value = cur.copy(
            steps = r.steps ?: cur.steps,
            sampler = r.sampler ?: cur.sampler,
            scheduler = r.scheduler ?: cur.scheduler,
            cfgScale = r.cfgScale ?: cur.cfgScale,
            seed = r.seed ?: cur.seed,
            width = r.width ?: cur.width,
            height = r.height ?: cur.height,
            modelTitle = r.modelTitle ?: cur.modelTitle,
        )
        r.additionalModules?.let { modules ->
            modulesSelection.saveModules(modules)
        }
    }

    /**
     * Saved prompt defaults over blank drafts: non-blank stored prompts fill
     * blank drafts. Model/sampler/scheduler/upscaler persistence moved to
     * per-model snapshots ([ModelParamsRepository]); [GenDefaults] carries
     * only prompt/neg here.
     */
    private suspend fun applyDefaults() {
        val d = defaults.observeDefaults().first()
        val draft = promptDrafts.observeDraft().first()
        if ((draft.prompt.isBlank() && d.prompt.isNotBlank()) ||
            (draft.negativePrompt.isBlank() && d.negativePrompt.isNotBlank())
        ) {
            promptDrafts.setPrompt(
                draft.prompt.ifBlank { d.prompt },
                draft.negativePrompt.ifBlank { d.negativePrompt },
            )
        }
    }

    /**
     * Per-model snapshot over the hardcoded [defaultParams]: the stored entry
     * for [modelTitle] wins when present (domain params + raw input texts +
     * HR repo + modules repo); otherwise domain params reset to defaults with
     * empty modules and stock HR. Prompts live their own life (drafts +
     * GenDefaults) and are never touched here.
     */
    private suspend fun applyModelEntry(modelTitle: String) {
        val entry = modelParams.observeForModel(modelTitle).first()
        if (entry != null) {
            val cur = rest.value
            rest.value = cur.copy(
                steps = entry.steps,
                cfgScale = entry.cfgScale,
                width = entry.width,
                height = entry.height,
                sampler = entry.sampler,
                scheduler = entry.scheduler,
                batchSize = entry.batchSize,
                batchCount = entry.batchCount,
                modelTitle = modelTitle,
                enableHr = entry.enableHr,
                hrUpscaler = entry.hrUpscaler,
                hrScale = entry.hrScale,
                hrSteps = entry.hrSteps,
                hrDenoise = entry.hrDenoise,
                hrCfg = entry.hrCfg,
                additionalModules = entry.additionalModules,
            )
            val hr = HrSettings(
                enable = entry.enableHr,
                upscaler = entry.hrUpscaler,
                scale = entry.hrScale,
                steps = entry.hrSteps,
                denoise = entry.hrDenoise,
                cfg = entry.hrCfg,
            )
            lastHrSaved = hr
            hrSettings.saveHr(hr)
            modulesSelection.saveModules(entry.additionalModules)
            seedInputsFromRest(hr)
        } else {
            val d = defaultParams()
            rest.value = d.copy(modelTitle = modelTitle)
            val hr = HrSettings()
            lastHrSaved = hr
            hrSettings.saveHr(hr)
            modulesSelection.saveModules(emptyList())
            seedInputsFromRest(hr)
        }
    }

    /**
     * Merges an external draft/HR emission (LoRA/Styles appends, defaults fill,
     * handoff) into raw inputs, preserving
     * in-progress typing and cursor via [adoptExternal].
     *
     * Own echoes (emissions equal to [lastWrittenPrompt]/[lastWrittenNeg]/
     * [lastHrSaved]) are ignored so the cursor never jumps while typing.
     */
    private fun mergeExternal(md: DraftData) {
        val cur = inputs.value
        var next = cur
        var changed = false
        if (md.prompt != lastWrittenPrompt) {
            next = next.copy(prompt = adoptExternal(next.prompt, md.prompt))
            lastWrittenPrompt = md.prompt
            changed = true
        }
        if (md.negativePrompt != lastWrittenNeg) {
            next = next.copy(negativePrompt = adoptExternal(next.negativePrompt, md.negativePrompt))
            lastWrittenNeg = md.negativePrompt
            changed = true
        }
        if (lastHrSaved == null || md.hr != lastHrSaved) {
            next = next.copy(
                hrUpscaler = adoptExternal(next.hrUpscaler, md.hr.upscaler),
                hrScale = textField(md.hr.scale.toString()),
                hrSteps = textField(md.hr.steps.toString()),
                hrDenoise = textField(md.hr.denoise.toString()),
                hrCfg = textField(md.hr.cfg.toString()),
            )
            lastHrSaved = md.hr
            changed = true
        }
        if (changed) inputs.value = next
    }

    /** Re-seeds numeric + HR raw texts from committed [rest] + [hr] (model presets). */
    private fun seedInputsFromRest(hr: HrSettings) {
        seedNumericInputsFromRest()
        inputs.value = inputs.value.copy(
            hrUpscaler = textField(hr.upscaler),
            hrScale = textField(hr.scale.toString()),
            hrSteps = textField(hr.steps.toString()),
            hrDenoise = textField(hr.denoise.toString()),
            hrCfg = textField(hr.cfg.toString()),
        )
    }

    /** Re-seeds numeric raws from committed [rest] (handoff). */
    private fun seedNumericInputsFromRest() {
        val r = rest.value
        inputs.value = inputs.value.copy(
            width = textField(r.width.toString()),
            height = textField(r.height.toString()),
            seed = if (r.seed == -1L) TextFieldValue("") else textField(r.seed.toString()),
            batchSize = textField(r.batchSize.toString()),
            batchCount = textField(r.batchCount.toString()),
            steps = textField(r.steps.toString()),
            cfg = textField(r.cfgScale.toString()),
        )
    }

    /**
     * Parses raw text inputs into domain state synchronously.
     *
     * Invalid/empty raws keep the previous domain value (raw text survives);
     * seed empty means random (-1). Prompt/neg are flushed to
     * [PromptDraftRepository] (debounced downstream) and HR is saved
     * immediately — commit points are infrequent (Done/focus-loss/navigation/
     * Generate). Safe to call from any action before reading domain state.
     */
    fun commitInputs() {
        val cur = inputs.value
        var r = rest.value
        parseSizeInput(cur.width.text)?.let { r = r.copy(width = it) }
        parseSizeInput(cur.height.text)?.let { r = r.copy(height = it) }
        parseSeedInput(cur.seed.text)?.let { r = r.copy(seed = it) }
        parseBoundedInt(cur.batchSize.text, 1, 4)?.let { r = r.copy(batchSize = it) }
        parseBoundedInt(cur.batchCount.text, 1, 8)?.let { r = r.copy(batchCount = it) }
        parseBoundedInt(cur.steps.text, 1, 50)?.let { r = r.copy(steps = it) }
        parseBoundedDouble(cur.cfg.text, 0.0, 15.0)?.let { r = r.copy(cfgScale = snapCfg(it)) }
        if (r != rest.value) rest.value = r

        val success = uiState.value as? GenerateUiState.Success
        if (cur.prompt.text != lastWrittenPrompt || cur.negativePrompt.text != lastWrittenNeg) {
            lastWrittenPrompt = cur.prompt.text
            lastWrittenNeg = cur.negativePrompt.text
            val p = cur.prompt.text
            val n = cur.negativePrompt.text
            viewModelScope.launch { promptDrafts.setPrompt(p, n) }
        }

        val currentHr = success?.hr ?: lastHrSaved ?: HrSettings()
        val parsedHr = currentHr.copy(
            upscaler = cur.hrUpscaler.text.ifBlank { currentHr.upscaler },
            scale = parseDoubleComplete(cur.hrScale.text) ?: currentHr.scale,
            steps = parseIntComplete(cur.hrSteps.text) ?: currentHr.steps,
            denoise = parseDoubleComplete(cur.hrDenoise.text) ?: currentHr.denoise,
            cfg = parseDoubleComplete(cur.hrCfg.text) ?: currentHr.cfg,
        )
        if (parsedHr != currentHr) {
            lastHrSaved = parsedHr
            viewModelScope.launch { hrSettings.saveHr(parsedHr) }
        }
    }

    /** Builds the payload params from committed rest + raw prompt + parsed HR raws. */
    private fun committedParams(): GenerationParams? {
        val success = uiState.value as? GenerateUiState.Success ?: return null
        val cur = inputs.value
        val r = rest.value
        val baseHr = success.hr
        val hr = baseHr.copy(
            upscaler = cur.hrUpscaler.text.ifBlank { baseHr.upscaler },
            scale = parseDoubleComplete(cur.hrScale.text) ?: baseHr.scale,
            steps = parseIntComplete(cur.hrSteps.text) ?: baseHr.steps,
            denoise = parseDoubleComplete(cur.hrDenoise.text) ?: baseHr.denoise,
            cfg = parseDoubleComplete(cur.hrCfg.text) ?: baseHr.cfg,
        )
        return r.copy(
            prompt = cur.prompt.text,
            negativePrompt = cur.negativePrompt.text,
            additionalModules = success.params.additionalModules,
            enableHr = hr.enable,
            hrUpscaler = hr.upscaler,
            hrScale = hr.scale,
            hrSteps = hr.steps,
            hrDenoise = hr.denoise,
            hrCfg = hr.cfg,
        )
    }

    fun onAction(action: GenerateAction) {
        val current = (uiState.value as? GenerateUiState.Success) ?: return
        when (action) {
            is GenerateAction.PromptChanged -> {
                inputs.value = inputs.value.copy(prompt = action.value)
                lastWrittenPrompt = action.value.text
                lastWrittenNeg = inputs.value.negativePrompt.text
                val prompt = action.value.text
                val neg = inputs.value.negativePrompt.text
                viewModelScope.launch { promptDrafts.setPrompt(prompt, neg) }
            }
            is GenerateAction.NegChanged -> {
                inputs.value = inputs.value.copy(negativePrompt = action.value)
                lastWrittenNeg = action.value.text
                lastWrittenPrompt = inputs.value.prompt.text
                val prompt = inputs.value.prompt.text
                val neg = action.value.text
                viewModelScope.launch { promptDrafts.setPrompt(prompt, neg) }
            }
            GenerateAction.ClearPrompt -> viewModelScope.launch {
                val neg = inputs.value.negativePrompt.text
                inputs.value = inputs.value.copy(prompt = TextFieldValue(""))
                lastWrittenPrompt = ""
                lastWrittenNeg = neg
                promptDrafts.setPrompt("", neg)
                statusMessage.value = "Prompt cleared."
            }
            GenerateAction.ClearNegative -> viewModelScope.launch {
                val prompt = inputs.value.prompt.text
                inputs.value = inputs.value.copy(negativePrompt = TextFieldValue(""))
                lastWrittenNeg = ""
                lastWrittenPrompt = prompt
                promptDrafts.setPrompt(prompt, "")
                statusMessage.value = "Negative prompt cleared."
            }
            is GenerateAction.SaveDefault -> viewModelScope.launch {
                // Only prompt defaults survive; model/sampler/scheduler/
                // upscaler persistence moved to per-model snapshots.
                val value = when (action.field) {
                    DefaultField.PROMPT -> inputs.value.prompt.text
                    DefaultField.NEGATIVE -> inputs.value.negativePrompt.text
                    else -> return@launch
                }
                defaults.saveDefault(action.field, value)
                statusMessage.value = "Saved as default."
            }
            is GenerateAction.ModelChanged -> viewModelScope.launch {
                rest.value = rest.value.copy(modelTitle = action.value)
                applyModelEntry(action.value)
            }
            is GenerateAction.ModulesChanged -> viewModelScope.launch {
                modulesSelection.saveModules(action.value)
            }
            is GenerateAction.SamplerChanged -> rest.value = rest.value.copy(sampler = action.value)
            is GenerateAction.SchedulerChanged -> rest.value = rest.value.copy(scheduler = action.value)
            is GenerateAction.StepsChanged -> {
                rest.value = rest.value.copy(steps = action.value)
                inputs.value = inputs.value.copy(steps = textField(action.value.toString()))
            }
            is GenerateAction.CfgChanged -> {
                rest.value = rest.value.copy(cfgScale = action.value)
                inputs.value = inputs.value.copy(cfg = textField(action.value.toString()))
            }
            is GenerateAction.WidthChanged -> {
                inputs.value = inputs.value.copy(width = action.value)
            }
            is GenerateAction.HeightChanged -> {
                inputs.value = inputs.value.copy(height = action.value)
            }
            is GenerateAction.SizeChanged -> {
                rest.value = rest.value.copy(width = action.width, height = action.height)
                inputs.value = inputs.value.copy(
                    width = textField(action.width.toString()),
                    height = textField(action.height.toString()),
                )
            }
            GenerateAction.SizeSwap -> {
                val cur = inputs.value
                inputs.value = cur.copy(width = cur.height, height = cur.width)
            }
            is GenerateAction.SeedChanged -> {
                inputs.value = inputs.value.copy(seed = action.value)
            }
            GenerateAction.SeedRandomized -> {
                rest.value = rest.value.copy(seed = -1L)
                inputs.value = inputs.value.copy(seed = TextFieldValue(""))
            }
            is GenerateAction.BatchSizeChanged -> {
                inputs.value = inputs.value.copy(batchSize = action.value)
            }
            is GenerateAction.BatchCountChanged -> {
                inputs.value = inputs.value.copy(batchCount = action.value)
            }
            GenerateAction.HrToggled -> {
                val newHr = current.hr.copy(enable = !current.hr.enable)
                lastHrSaved = newHr
                viewModelScope.launch { hrSettings.saveHr(newHr) }
            }
            is GenerateAction.HrUpscalerChanged -> {
                inputs.value = inputs.value.copy(hrUpscaler = action.value)
            }
            is GenerateAction.HrScaleChanged -> {
                inputs.value = inputs.value.copy(hrScale = action.value)
            }
            is GenerateAction.HrStepsChanged -> {
                inputs.value = inputs.value.copy(hrSteps = action.value)
            }
            is GenerateAction.HrDenoiseChanged -> {
                inputs.value = inputs.value.copy(hrDenoise = action.value)
            }
            is GenerateAction.HrCfgChanged -> {
                inputs.value = inputs.value.copy(hrCfg = action.value)
            }
            GenerateAction.CommitInputs -> commitInputs()
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
            val schedulersResult = generationRepository.fetchSchedulers()
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
            if (schedulersResult is Result.Success) serverSchedulers.value = schedulersResult.data
            if (modulesResult is Result.Success) {
                modelsState.value = modelsState.value.copy(modules = modulesResult.data)
                // Drop persisted selections the server no longer offers (renamed/deleted files).
                val known = modulesResult.data.toSet()
                val current = modulesSelection.observeModules().first()
                val pruned = current.filter { it in known }
                if (pruned.size != current.size) modulesSelection.saveModules(pruned)
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

    /** Persists the committed params as this model's last-used snapshot (no seed). */
    private suspend fun saveModelSnapshot(p: GenerationParams) {
        modelParams.saveForModel(
            p.modelTitle,
            ModelLastUsed(
                steps = p.steps,
                cfgScale = p.cfgScale,
                width = p.width,
                height = p.height,
                sampler = p.sampler,
                scheduler = p.scheduler,
                batchSize = p.batchSize,
                batchCount = p.batchCount,
                enableHr = p.enableHr,
                hrUpscaler = p.hrUpscaler,
                hrScale = p.hrScale,
                hrSteps = p.hrSteps,
                hrDenoise = p.hrDenoise,
                hrCfg = p.hrCfg,
                additionalModules = p.additionalModules,
            ),
        )
    }

    /** QUEUE button (1/4): persist without starting execution. */
    private fun stageQueue() {
        viewModelScope.launch {
            commitInputs()
            val wasRunning =
                (uiState.value as? GenerateUiState.Success)?.queueRunning == true
            val p = committedParams() ?: return@launch
            if (p.prompt.isBlank()) {
                statusMessage.value = "Prompt is empty."
                return@launch
            }
            saveModelSnapshot(p)
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
            commitInputs()
            val wasRunning =
                (uiState.value as? GenerateUiState.Success)?.queueRunning == true
            val p = committedParams() ?: return@launch
            if (p.prompt.isBlank()) {
                statusMessage.value = "Prompt is empty."
                return@launch
            }
            saveModelSnapshot(p)
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
}

sealed interface GenerateAction {
    data class PromptChanged(val value: TextFieldValue) : GenerateAction
    data class NegChanged(val value: TextFieldValue) : GenerateAction
    data object ClearPrompt : GenerateAction
    data object ClearNegative : GenerateAction
    data class SaveDefault(val field: DefaultField) : GenerateAction
    data class ModelChanged(val value: String) : GenerateAction
    data class ModulesChanged(val value: List<String>) : GenerateAction
    data class SamplerChanged(val value: String) : GenerateAction
    data class SchedulerChanged(val value: String) : GenerateAction
    data class StepsChanged(val value: Int) : GenerateAction
    data class CfgChanged(val value: Double) : GenerateAction
    data class WidthChanged(val value: TextFieldValue) : GenerateAction
    data class HeightChanged(val value: TextFieldValue) : GenerateAction
    data class SizeChanged(val width: Int, val height: Int) : GenerateAction
    data object SizeSwap : GenerateAction
    data class SeedChanged(val value: TextFieldValue) : GenerateAction
    data object SeedRandomized : GenerateAction
    data class BatchSizeChanged(val value: TextFieldValue) : GenerateAction
    data class BatchCountChanged(val value: TextFieldValue) : GenerateAction
    data object HrToggled : GenerateAction
    data class HrUpscalerChanged(val value: TextFieldValue) : GenerateAction
    data class HrScaleChanged(val value: TextFieldValue) : GenerateAction
    data class HrStepsChanged(val value: TextFieldValue) : GenerateAction
    data class HrDenoiseChanged(val value: TextFieldValue) : GenerateAction
    data class HrCfgChanged(val value: TextFieldValue) : GenerateAction
    data object CommitInputs : GenerateAction
    data object RefreshModels : GenerateAction
    data object InitializeEngine : GenerateAction
    data object StageQueue : GenerateAction
    data object Generate : GenerateAction
    data object DismissStatus : GenerateAction
    data object RequestUnloadModel : GenerateAction
    data object ConfirmUnloadModel : GenerateAction
    data object DismissUnload : GenerateAction
}
