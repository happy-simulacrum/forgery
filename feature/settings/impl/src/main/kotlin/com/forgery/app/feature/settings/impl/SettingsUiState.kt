package com.forgery.app.feature.settings.impl

import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Success(
        /** Editable draft (null = pristine, shows stored values). */
        val draft: ConnectionConfig,
        val draftUi: UiPrefs,
        val isDirty: Boolean,
        val isSaving: Boolean = false,
        val check: CheckState = CheckState.Idle,
    ) : SettingsUiState

    data class Error(val message: String) : SettingsUiState
}

sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data class Ok(val models: Int) : CheckState
    data class Failed(val message: String) : CheckState
}
