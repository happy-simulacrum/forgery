package com.forgery.app.feature.magicprompt.impl

import com.forgery.app.core.model.GenerationMode

/**
 * System prompts per mode — adapted from legacy globals.js
 * (llmSystem_xl / llmSystem_flux / llmSystem_qwen).
 */
fun systemPromptFor(mode: GenerationMode): String = when (mode) {
    GenerationMode.SDXL -> "You are a Stable Diffusion XL prompt engineer. Expand the user's " +
        "idea into a rich, comma-separated image prompt with subject, details, lighting, " +
        "composition and quality tags. Reply with the prompt only, no explanations."
    GenerationMode.FLUX -> "You are a Flux prompt director. Expand the user's idea into a " +
        "dense natural-language scene description optimized for Flux, with lighting, " +
        "materials, camera and mood. Reply with the prompt only, no explanations."
    GenerationMode.QWEN -> "You are a narrative-to-image writer for a Qwen-Image class model. " +
        "Expand the user's idea into a flowing descriptive paragraph prompt. " +
        "Reply with the prompt only, no explanations."
}

sealed interface MagicpromptUiState {
    data object Loading : MagicpromptUiState

    data class Success(
        val mode: GenerationMode = GenerationMode.SDXL,
        val input: String = "",
        val output: String = "",
        val models: List<String> = emptyList(),
        val selectedModel: String = "",
        val apiKey: String = "",
        val generating: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
    ) : MagicpromptUiState

    data class Error(val message: String) : MagicpromptUiState
}
