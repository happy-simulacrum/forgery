package com.forgery.app.feature.inpaint.impl

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
        val prompt: String = "",
        val negativePrompt: String = "",
        val modelTitle: String = "",
        val models: List<String> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
        val steps: Int = 20,
        val cfgScale: Double = 7.0,
        val denoise: Double = 0.75,
        val maskBlur: Int = 4,
        val brush: Float = 0.05f,
        val eraseMode: Boolean = false,
        val strokes: List<MaskStroke> = emptyList(),
        val queueRunning: Boolean = false,
        val statusMessage: String? = null,
    ) : InpaintUiState

    data class Error(val message: String) : InpaintUiState
}
