package com.forgery.app.feature.settings.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.network.ForgeApiFactory
import com.forgery.app.core.network.ForgeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    private var checkJob: Job? = null

    /** Test seam: replaces [ForgeApiFactory.createControl] in unit tests. */
    var checkServiceProvider: ((baseUrl: String, cfClientId: String, cfClientSecret: String) -> ForgeService)? = null

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
            // UiPrefs applies instantly (see UiPrefsChanged) — SAVE tracks
            // connection edits only, otherwise it would stay lit forever.
            isDirty = draftValue?.let { d ->
                d.config != stored ||
                    d.texts.baseIp.text != stored.baseIp ||
                    d.texts.portWebUi.text != stored.portWebUi.toString() ||
                    d.texts.extForgeUrl.text != stored.extForgeUrl ||
                    d.texts.cfClientId.text != stored.cfClientId ||
                    d.texts.cfClientSecret.text != stored.cfClientSecret
            } == true,
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
            is SettingsAction.ExtForgeUrlChanged -> {
                val d = buffered()
                draft.value = d.copy(texts = d.texts.copy(extForgeUrl = action.value))
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
                viewModelScope.launch { connectionRepository.saveUiPrefs(action.prefs) }
            }
            SettingsAction.Save -> save(current)
            SettingsAction.Reset -> reset()
            SettingsAction.CheckConnection -> {
                val d = draft.value ?: Draft(current.draft, current.draftUi, current.texts)
                checkConnection(commitDraft(d).config)
            }
            SettingsAction.DismissCheck -> {
                checkJob?.cancel()
                check.value = CheckState.Idle
            }
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
                extForgeUrl = t.extForgeUrl.text,
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
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            check.value = CheckState.Checking
            try {
                val baseUrl = config.webUiBaseUrl()
                val api = checkServiceProvider?.invoke(baseUrl, config.cfClientId, config.cfClientSecret)
                    ?: forgeApiFactory.createControl(baseUrl, config.cfClientId, config.cfClientSecret)
                val models = withTimeout(30_000) { api.sdModels() }
                check.value = CheckState.Ok(models.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                check.value = CheckState.Failed(e.message ?: e.toString())
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
    data class ExtForgeUrlChanged(val value: TextFieldValue) : SettingsAction
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
