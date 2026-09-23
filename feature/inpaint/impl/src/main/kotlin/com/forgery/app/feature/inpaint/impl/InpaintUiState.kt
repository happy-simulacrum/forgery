package com.forgery.app.feature.inpaint.impl

import androidx.compose.ui.text.input.TextFieldValue

/** Normalized mask point (0..1 fractions of image width/height). */
data class MaskPoint(val x: Float, val y: Float)

data class MaskStroke(
    val points: List<MaskPoint> = emptyList(),
    /** Fraction of image width. */
    val brush: Float = 0.05f,
    val erase: Boolean = false,
)

data class AttachedImage(
    val uri: String,
    val base64: String,
    val width: Int,
    val height: Int,
)

sealed interface InpaintUiState {
    data object Loading : InpaintUiState

    data class Success(
        val source: AttachedImage? = null,
        /** Committed prompt; keystrokes land in [promptDraft] until [commit]. */
        val prompt: String = "",
        /** Raw VM-owned prompt text (verbatim keystrokes). */
        val promptDraft: TextFieldValue = TextFieldValue(""),
        /** Committed negative prompt; keystrokes land in [negativeDraft] until commit. */
        val negativePrompt: String = "",
        /** Raw VM-owned negative-prompt text. */
        val negativeDraft: TextFieldValue = TextFieldValue(""),
        /** Committed model title; keystrokes land in [modelDraft] until commit. */
        val modelTitle: String = "",
        /** Raw VM-owned model text (manual entry). */
        val modelDraft: TextFieldValue = TextFieldValue(""),
        val models: List<String> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
        /** Selected VAE / Text Encoder modules (shared persisted selection, SDXL). */
        val modules: List<String> = emptyList(),
        /** Committed steps; keystrokes land in [stepsDraft] until commit. */
        val steps: Int = 20,
        /** Raw VM-owned steps text. */
        val stepsDraft: TextFieldValue = TextFieldValue("20"),
        /** Committed CFG; keystrokes land in [cfgDraft] until commit. */
        val cfgScale: Double = 7.0,
        /** Raw VM-owned CFG text. */
        val cfgDraft: TextFieldValue = TextFieldValue("7.0"),
        /** Committed denoise; keystrokes land in [denoiseDraft] until commit. */
        val denoise: Double = 0.75,
        /** Raw VM-owned denoise text. */
        val denoiseDraft: TextFieldValue = TextFieldValue("0.75"),
        /** Committed mask blur; keystrokes land in [maskBlurDraft] until commit. */
        val maskBlur: Int = 4,
        /** Raw VM-owned mask-blur text. */
        val maskBlurDraft: TextFieldValue = TextFieldValue("4"),
        val sampler: String = "DPM++ 2M SDE",
        val scheduler: String = "Karras",
        val samplers: List<String> = emptyList(),
        val brush: Float = 0.05f,
        val eraseMode: Boolean = false,
        val strokes: List<MaskStroke> = emptyList(),
        val queueRunning: Boolean = false,
        val statusMessage: String? = null,
    ) : InpaintUiState

    data class Error(val message: String) : InpaintUiState
}
