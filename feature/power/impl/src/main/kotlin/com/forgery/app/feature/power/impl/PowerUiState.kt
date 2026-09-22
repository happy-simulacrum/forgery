package com.forgery.app.feature.power.impl

sealed interface PowerUiState {
    data object Loading : PowerUiState

    data class Success(
        val busy: Boolean = false,
        val message: String? = null,
        val isError: Boolean = false,
    ) : PowerUiState

    data class Error(val message: String) : PowerUiState
}
