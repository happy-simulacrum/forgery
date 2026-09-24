package com.forgery.app.feature.settings.impl

import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs

/**
 * Raw VM-owned text for the connection form. Keystrokes update these only;
 * domain commit (port parsing) happens in the VM on focus loss / save.
 */
data class SettingsTextDrafts(
    val baseIp: TextFieldValue = TextFieldValue(""),
    val portWebUi: TextFieldValue = TextFieldValue(""),
    val extForgeUrl: TextFieldValue = TextFieldValue(""),
    val cfClientId: TextFieldValue = TextFieldValue(""),
    val cfClientSecret: TextFieldValue = TextFieldValue(""),
) {
    companion object {
        /** Raw mirror of committed [config] for pristine display. */
        fun from(config: ConnectionConfig) = SettingsTextDrafts(
            baseIp = TextFieldValue(config.baseIp),
            portWebUi = TextFieldValue(config.portWebUi.toString()),
            extForgeUrl = TextFieldValue(config.extForgeUrl),
            cfClientId = TextFieldValue(config.cfClientId),
            cfClientSecret = TextFieldValue(config.cfClientSecret),
        )
    }
}

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Success(
        /** Editable draft (null = pristine, shows stored values). */
        val draft: ConnectionConfig,
        val draftUi: UiPrefs,
        /** Raw VM-owned form text (verbatim keystrokes, committed on focus loss / save). */
        val texts: SettingsTextDrafts = SettingsTextDrafts(),
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
