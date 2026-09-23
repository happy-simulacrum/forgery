package com.forgery.app.feature.generate.impl

import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.QueueSnapshot

sealed interface GenerateUiState {
    data object Loading : GenerateUiState

    data class Success(
        val params: GenerationParams = GenerationParams(),
        val models: List<String> = emptyList(),
        val modules: List<String> = emptyList(),
        val upscalers: List<String> = emptyList(),
        val modelsLoading: Boolean = false,
        val modelsError: String? = null,
        val samplers: List<String> = emptyList(),
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

/** Legacy defaults: SDXL classic, Flux guidance-like, Qwen/Z-Image (neo.js: steps 8, cfg 1.0). */
fun defaultParamsFor(mode: com.forgery.app.core.model.GenerationMode): GenerationParams =
    when (mode) {
        com.forgery.app.core.model.GenerationMode.SDXL -> GenerationParams(
            mode = mode, steps = 20, cfgScale = 7.0, width = 1024, height = 1024,
            sampler = "Euler a", scheduler = "Karras",
        )
        com.forgery.app.core.model.GenerationMode.FLUX -> GenerationParams(
            mode = mode, steps = 20, cfgScale = 3.5, distilledCfgScale = 3.5, width = 1024, height = 1024,
            sampler = "Euler", scheduler = "Normal",
        )
        com.forgery.app.core.model.GenerationMode.QWEN -> GenerationParams(
            mode = mode, steps = 8, cfgScale = 1.0, width = 1024, height = 1024,
            sampler = "Euler", scheduler = "Simple",
        )
    }
