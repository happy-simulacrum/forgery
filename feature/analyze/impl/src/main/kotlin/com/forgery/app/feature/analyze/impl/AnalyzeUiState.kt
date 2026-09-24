package com.forgery.app.feature.analyze.impl

import com.forgery.app.core.common.FileInfo
import com.forgery.app.core.common.PngMetadata

sealed interface AnalyzeUiState {
    data object Loading : AnalyzeUiState

    data class Success(
        val uri: String? = null,
        val metadata: PngMetadata? = null,
        val prompt: String = "",
        val negativePrompt: String = "",
        val rawText: String? = null,
        val noMetadata: Boolean = false,
        val statusMessage: String? = null,
        val settingsSummary: String? = null,
        val fileInfo: FileInfo? = null,
    ) : AnalyzeUiState

    data class Error(val message: String) : AnalyzeUiState
}
