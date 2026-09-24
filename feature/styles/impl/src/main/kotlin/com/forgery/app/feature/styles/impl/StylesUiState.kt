package com.forgery.app.feature.styles.impl

import com.forgery.app.core.model.StylePreset

sealed interface StylesUiState {
    data object Loading : StylesUiState

    data class Success(
        val query: String = "",
        val styles: List<StylePreset> = emptyList(),
        val importing: Boolean = false,
        val notice: String? = null,
        val editor: StylePreset? = null,
    ) : StylesUiState {
        val visible: List<StylePreset>
            get() = if (query.isBlank()) {
                styles
            } else {
                styles.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        it.prompt.contains(query, ignoreCase = true)
                }
            }
    }

    data class Error(val message: String) : StylesUiState
}
