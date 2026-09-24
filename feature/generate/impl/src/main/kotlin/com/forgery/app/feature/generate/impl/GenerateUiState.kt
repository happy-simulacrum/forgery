package com.forgery.app.feature.generate.impl

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.QueueSnapshot

/**
 * Raw in-memory text state for every text/numeric input on the Generate screen.
 *
 * The ViewModel owns these [TextFieldValue]s (single source of truth for what the
 * user sees and edits); domain commit (parsing) happens synchronously in the VM on
 * Done / focus loss / navigation / Generate, never per keystroke.
 */
data class GenerateInputs(
    val prompt: TextFieldValue = TextFieldValue(""),
    val negativePrompt: TextFieldValue = TextFieldValue(""),
    val width: TextFieldValue = TextFieldValue("1024", TextRange(4)),
    val height: TextFieldValue = TextFieldValue("1024", TextRange(4)),
    val seed: TextFieldValue = TextFieldValue(""),
    val batchSize: TextFieldValue = TextFieldValue("1", TextRange(1)),
    val batchCount: TextFieldValue = TextFieldValue("1", TextRange(1)),
    val steps: TextFieldValue = TextFieldValue("20", TextRange(2)),
    val cfg: TextFieldValue = TextFieldValue("7.0", TextRange(3)),
    val hrUpscaler: TextFieldValue = TextFieldValue("Latent", TextRange(6)),
    val hrScale: TextFieldValue = TextFieldValue("1.5", TextRange(3)),
    val hrSteps: TextFieldValue = TextFieldValue("6", TextRange(1)),
    val hrDenoise: TextFieldValue = TextFieldValue("0.4", TextRange(3)),
    val hrCfg: TextFieldValue = TextFieldValue("1.0", TextRange(3)),
)

sealed interface GenerateUiState {
    data object Loading : GenerateUiState

    data class Success(
        val params: GenerationParams = GenerationParams(),
        val inputs: GenerateInputs = GenerateInputs(),
        val models: List<String> = emptyList(),
        val modules: List<String> = emptyList(),
        val upscalers: List<String> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
        val samplers: List<String> = emptyList(),
        val serverSchedulers: List<String> = emptyList(),
        val queueRunning: Boolean = false,
        val queueSnapshot: QueueSnapshot? = null,
        val statusMessage: String? = null,
        val engine: EngineState = EngineState.Uninitialized,
        val hr: HrSettings = HrSettings(),
        val confirmUnload: Boolean = false,
    ) : GenerateUiState

    data class Error(val message: String) : GenerateUiState
}

/** Mirrors legacy initEngineBtn states (network.js connect()). */
sealed interface EngineState {
    data object Uninitialized : EngineState
    data object Initializing : EngineState
    data class Initialized(val models: Int) : EngineState
    data class Failed(val message: String) : EngineState
}

/** Legacy defaults: SDXL classic. */
fun defaultParams(): GenerationParams =
    GenerationParams(
        steps = 20, cfgScale = 7.0, width = 1024, height = 1024,
        sampler = "Euler a", scheduler = "Karras",
    )
