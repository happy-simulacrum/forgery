package com.forgery.app.feature.modules.impl

import androidx.compose.ui.text.input.TextFieldValue

sealed interface ModulesUiState {
    data object Loading : ModulesUiState

    data class Success(
        /** Raw VM-owned search text (local only, no repo). */
        val query: TextFieldValue = TextFieldValue(""),
        val items: List<String> = emptyList(),
        val totalCount: Int = 0,
        val selected: List<String> = emptyList(),
        val listLoading: Boolean = false,
        val listError: String? = null,
    ) : ModulesUiState
}
