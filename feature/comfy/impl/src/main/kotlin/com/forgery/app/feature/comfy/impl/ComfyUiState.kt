package com.forgery.app.feature.comfy.impl

sealed interface ComfyUiState {
    data object Loading : ComfyUiState
    
    data class Success(
        val data: List<String> = emptyList(),
    ) : ComfyUiState
    
    data class Error(
        val message: String,
    ) : ComfyUiState
}
