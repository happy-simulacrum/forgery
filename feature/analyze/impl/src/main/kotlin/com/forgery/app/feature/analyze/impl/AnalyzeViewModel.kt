package com.forgery.app.feature.analyze.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.common.A1111Settings
import com.forgery.app.core.common.FileInfo
import com.forgery.app.core.common.fileInfo
import com.forgery.app.core.common.parseA1111Parameters
import com.forgery.app.core.common.parseA1111Settings
import com.forgery.app.core.common.readPngMetadata
import com.forgery.app.core.data.AnalyzeHandoffRepository
import com.forgery.app.core.data.HrSettingsRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.RestoredParams
import com.forgery.app.feature.analyze.api.AnalyzeRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AnalyzeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val imageSource: AnalyzeImageSource,
    private val promptDrafts: PromptDraftRepository,
    private val hrSettings: HrSettingsRepository,
    private val handoff: AnalyzeHandoffRepository,
) : ViewModel() {

    // toRoute() needs the Android framework (Bundle) and throws on JVM unit tests;
    // the raw-key fallback reads the same back-stack-entry handle key.
    private val initialImagePath: String? = runCatching {
        savedStateHandle.toRoute<AnalyzeRoute>().imagePath
    }.getOrNull() ?: savedStateHandle.get<String>("imagePath")

    private val uri = MutableStateFlow<String?>(null)
    private val parsed = MutableStateFlow<Parsed?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)

    private data class Parsed(
        val prompt: String,
        val negativePrompt: String,
        val raw: String?,
        val entries: Int,
        val settings: A1111Settings?,
        val fileInfo: FileInfo?,
    )

    val uiState: StateFlow<AnalyzeUiState> = combine(uri, parsed, statusMessage) { u, p, msg ->
        AnalyzeUiState.Success(
            uri = u,
            metadata = null,
            prompt = p?.prompt.orEmpty(),
            negativePrompt = p?.negativePrompt.orEmpty(),
            rawText = p?.raw,
            noMetadata = u != null && p != null && p.raw == null,
            statusMessage = msg,
            settingsSummary = p?.settings?.let(::buildSettingsSummary),
            fileInfo = p?.fileInfo,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyzeUiState.Loading,
    )

    init {
        initialImagePath?.let { analyze(it) }
    }

    fun onAction(action: AnalyzeAction) {
        when (action) {
            is AnalyzeAction.PickResult -> analyze(action.uri)
            AnalyzeAction.Clear -> {
                uri.value = null
                parsed.value = null
                statusMessage.value = null
            }
            is AnalyzeAction.CopyToMode -> copyToMode(action.mode)
            AnalyzeAction.DismissStatus -> statusMessage.value = null
        }
    }

    private fun analyze(uriString: String) {
        viewModelScope.launch {
            uri.value = uriString
            parsed.value = null
            statusMessage.value = null
            val bytes = withContext(Dispatchers.IO) { imageSource.read(uriString) }
            if (bytes == null) {
                statusMessage.value = "Could not read image."
                return@launch
            }
            val meta = withContext(Dispatchers.Default) {
                readPngMetadata(bytes) to fileInfo(bytes)
            }
            val info = meta.second
            val raw = meta.first?.parameters
            if (raw.isNullOrBlank()) {
                parsed.value = Parsed("", "", null, meta.first?.entries?.size ?: 0, null, info)
                statusMessage.value = "No generation metadata found."
            } else {
                val (prompt, neg) = parseA1111Parameters(raw)
                val entries = meta.first!!.entries.size
                parsed.value = Parsed(prompt, neg, raw, entries, parseA1111Settings(raw), info)
            }
        }
    }

    private fun copyToMode(mode: GenerationMode) {
        val p = parsed.value
        if (p == null || p.raw == null) {
            statusMessage.value = "Nothing to copy."
            return
        }
        viewModelScope.launch {
            promptDrafts.setPrompt(mode, p.prompt, p.negativePrompt)
            promptDrafts.setActiveMode(mode)
            val s = p.settings
            // Persist HR only when the image carried parseable settings;
            // empty (all-null) or missing settings leave HR untouched.
            if (s != null && s != A1111Settings()) {
                hrSettings.saveHr(
                    mode,
                    HrSettings(
                        enable = s.hrEnable,
                        upscaler = s.hrUpscaler ?: "Latent",
                        scale = s.hrScale ?: 1.5,
                        steps = s.hrSteps ?: 6,
                        denoise = s.hrDenoise ?: 0.4,
                        cfg = 1.0,
                    ),
                )
            }
            handoff.set(
                RestoredParams(
                    steps = s?.steps,
                    sampler = s?.sampler,
                    scheduler = s?.scheduler,
                    cfgScale = s?.cfg,
                    seed = s?.seed,
                    width = s?.width,
                    height = s?.height,
                    modelTitle = s?.model,
                    hr = null,
                ),
            )
            statusMessage.value = "Copied to ${mode.name}"
        }
    }
}

private fun formatDouble(d: Double): String =
    if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** One-line summary of non-null settings parts; null when there is nothing to show. */
private fun buildSettingsSummary(s: A1111Settings): String? {
    val parts = buildList {
        s.steps?.let { add("Steps $it") }
        s.sampler?.let { add(it) }
        s.scheduler?.let { add(it) }
        s.cfg?.let { add("CFG ${formatDouble(it)}") }
        s.seed?.let { add("Seed $it") }
        if (s.width != null && s.height != null) add("${s.width}\u00D7${s.height}")
        s.model?.let { add(it) }
        if (s.hrEnable) add("HR on")
    }
    return parts.joinToString(" \u00B7 ").takeIf { parts.isNotEmpty() }
}

sealed interface AnalyzeAction {
    data class PickResult(val uri: String) : AnalyzeAction
    data object Clear : AnalyzeAction
    data class CopyToMode(val mode: GenerationMode) : AnalyzeAction
    data object DismissStatus : AnalyzeAction
}
