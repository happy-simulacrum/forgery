package com.forgery.app.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.network.ForgeApiFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val forgeApiFactory: ForgeApiFactory,
) : ViewModel() {

    private data class Draft(val config: ConnectionConfig, val ui: UiPrefs)

    private val draft = MutableStateFlow<Draft?>(null)
    private val saving = MutableStateFlow(false)
    private val check = MutableStateFlow<CheckState>(CheckState.Idle)

    val uiState: StateFlow<SettingsUiState> = combine(
        connectionRepository.observe(),
        connectionRepository.observeUiPrefs(),
        draft,
        saving,
        check
    ) { stored, storedUi, draftValue, isSaving, checkState ->
        SettingsUiState.Success(
            draft = draftValue?.config ?: stored,
            draftUi = draftValue?.ui ?: storedUi,
            isDirty = draftValue != null,
            isSaving = isSaving,
            check = checkState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState.Loading,
    )

    fun onAction(action: SettingsAction) {
        val current = (uiState.value as? SettingsUiState.Success) ?: return
        when (action) {
            is SettingsAction.ConfigChanged -> {
                draft.value = Draft(action.config, current.draftUi)
                check.value = CheckState.Idle
            }
            is SettingsAction.UiPrefsChanged -> {
                draft.value = Draft(current.draft, action.prefs)
            }
            SettingsAction.Save -> save(current)
            SettingsAction.Reset -> reset()
            SettingsAction.CheckConnection -> checkConnection(current.draft)
            SettingsAction.DismissCheck -> check.value = CheckState.Idle
        }
    }

    private fun save(state: SettingsUiState.Success) {
        viewModelScope.launch {
            saving.value = true
            try {
                connectionRepository.save(state.draft.copy(isConfigured = true))
                connectionRepository.saveUiPrefs(state.draftUi)
                draft.value = null
            } finally {
                saving.value = false
            }
        }
    }

    private fun reset() {
        viewModelScope.launch {
            connectionRepository.reset()
            draft.value = null
            check.value = CheckState.Idle
        }
    }

    private fun checkConnection(config: ConnectionConfig) {
        viewModelScope.launch {
            check.value = CheckState.Checking
            check.value = try {
                val api = forgeApiFactory.create(config.webUiBaseUrl())
                val models = api.sdModels()
                CheckState.Ok(models.size)
            } catch (e: Exception) {
                CheckState.Failed(e.message ?: e.toString())
            }
        }
    }
}

sealed interface SettingsAction {
    data class ConfigChanged(val config: ConnectionConfig) : SettingsAction
    data class UiPrefsChanged(val prefs: UiPrefs) : SettingsAction
    data object Save : SettingsAction
    data object Reset : SettingsAction
    data object CheckConnection : SettingsAction
    data object DismissCheck : SettingsAction
}
