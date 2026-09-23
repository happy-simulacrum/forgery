package com.forgery.app.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.input.TextFieldValue
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

    private data class Draft(
        val config: ConnectionConfig,
        val ui: UiPrefs,
        val texts: SettingsTextDrafts,
    )

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
            texts = draftValue?.texts ?: SettingsTextDrafts.from(stored),
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
        // Buffered VM-owned raw text; full-config pushes re-mirror the raw text.
        fun buffered(): Draft =
            draft.value ?: Draft(current.draft, current.draftUi, current.texts)
        when (action) {
            is SettingsAction.ConfigChanged -> {
                draft.value = Draft(action.config, current.draftUi, SettingsTextDrafts.from(action.config))
                check.value = CheckState.Idle
            }
            is SettingsAction.BaseIpChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(baseIp = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.PortWebUiChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(portWebUi = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.PortComfyChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(portComfy = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.PortLlmChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(portLlm = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.PortWakeChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(portWake = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.ExtForgeUrlChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(extForgeUrl = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.ExtWakeUrlChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(extWakeUrl = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.CfClientIdChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(cfClientId = action.value))
                check.value = CheckState.Idle
            }
            is SettingsAction.CfClientSecretChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(cfClientSecret = action.value))
                check.value = CheckState.Idle
            }
            SettingsAction.CommitInputs -> {
                draft.value = draft.value?.let(::commitDraft)
            }
            is SettingsAction.UiPrefsChanged -> {
                draft.value = Draft(current.draft, action.prefs, current.texts)
            }
            SettingsAction.Save -> save(current)
            SettingsAction.Reset -> reset()
            SettingsAction.CheckConnection -> {
                val d = draft.value ?: Draft(current.draft, current.draftUi, current.texts)
                checkConnection(commitDraft(d).config)
            }
            SettingsAction.DismissCheck -> check.value = CheckState.Idle
        }
    }

    /**
     * Synchronously commits raw form text to domain values. Text commits
     * verbatim; ports parse (digits only) and invalid raw keeps the previous
     * domain value so mid-typing text survives. Runs on focus loss and
     * before save / connection check.
     */
    private fun commitDraft(d: Draft): Draft {
        val t = d.texts
        val c = d.config
        return d.copy(
            config = c.copy(
                baseIp = t.baseIp.text,
                portWebUi = parsePort(t.portWebUi.text) ?: c.portWebUi,
                portComfy = parsePort(t.portComfy.text) ?: c.portComfy,
                portLlm = parsePort(t.portLlm.text) ?: c.portLlm,
                portWake = parsePort(t.portWake.text) ?: c.portWake,
                extForgeUrl = t.extForgeUrl.text,
                extWakeUrl = t.extWakeUrl.text,
                cfClientId = t.cfClientId.text,
                cfClientSecret = t.cfClientSecret.text,
            ),
        )
    }

    private fun save(state: SettingsUiState.Success) {
        viewModelScope.launch {
            saving.value = true
            try {
                // Parse raw text on save; invalid ports keep previous domain values.
                val committed = commitDraft(Draft(state.draft, state.draftUi, state.texts))
                connectionRepository.save(committed.config.copy(isConfigured = true))
                connectionRepository.saveUiPrefs(committed.ui)
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
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class BaseIpChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class PortWebUiChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class PortComfyChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class PortLlmChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class PortWakeChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class ExtForgeUrlChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class ExtWakeUrlChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class CfClientIdChanged(val value: TextFieldValue) : SettingsAction
    /** Raw keystroke; domain commit happens on focus loss / save. */
    data class CfClientSecretChanged(val value: TextFieldValue) : SettingsAction
    /** Focus-loss trigger: synchronously commit raw text to domain. */
    data object CommitInputs : SettingsAction
    data class UiPrefsChanged(val prefs: UiPrefs) : SettingsAction
    data object Save : SettingsAction
    data object Reset : SettingsAction
    data object CheckConnection : SettingsAction
    data object DismissCheck : SettingsAction
}

/**
 * Commits raw port text: digits only, empty/unparseable commits nothing
 * (null) so the raw text survives; parsed values clamp to 1..65535.
 */
internal fun parsePort(raw: String): Int? =
    raw.filter(Char::isDigit).take(5).toIntOrNull()?.coerceIn(1, 65535)
