package com.forgery.app.feature.styles.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.data.StyleRepository
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.StylePreset
import com.forgery.app.feature.styles.api.StylesRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StylesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val styleRepository: StyleRepository,
    private val promptDrafts: PromptDraftRepository,
) : ViewModel() {

    private val mode: GenerationMode = runCatching {
        savedStateHandle.toRoute<StylesRoute>().mode?.let { GenerationMode.valueOf(it) }
    }.getOrNull() ?: GenerationMode.SDXL

    private val query = MutableStateFlow("")
    private val importing = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val editor = MutableStateFlow<StylePreset?>(null)

    val uiState: StateFlow<StylesUiState> = combine(
        query,
        styleRepository.observeAll(),
        importing,
        notice,
        editor
    ) { q, styles, imp, note, ed ->
        StylesUiState.Success(
            mode = mode,
            query = q,
            styles = styles,
            importing = imp,
            notice = note,
            editor = ed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StylesUiState.Loading,
    )

    fun onAction(action: StylesAction) {
        when (action) {
            is StylesAction.QueryChanged -> query.value = action.value
            is StylesAction.ApplyStyle -> apply(action.preset)
            StylesAction.ImportFromServer -> importFromServer()
            StylesAction.OpenEditor -> editor.value = StylePreset("", "", "")
            is StylesAction.EditStyle -> editor.value = action.preset
            StylesAction.CloseEditor -> editor.value = null
            is StylesAction.SaveStyle -> save(action.preset)
            is StylesAction.DeleteStyle -> viewModelScope.launch {
                styleRepository.delete(action.name)
            }
            StylesAction.DismissNotice -> notice.value = null
        }
    }

    private fun apply(preset: StylePreset) {
        viewModelScope.launch {
            promptDrafts.appendPrompt(mode, preset.prompt, preset.negativePrompt)
            notice.value = "Applied ${preset.name}"
        }
    }

    private fun importFromServer() {
        viewModelScope.launch {
            importing.value = true
            when (val r = styleRepository.importFromServer()) {
                is Result.Success -> notice.value = "Imported ${r.data} style(s)"
                is Result.Error -> notice.value = "Import failed: ${r.message}"
                is Result.Loading -> Unit
            }
            importing.value = false
        }
    }

    private fun save(preset: StylePreset) {
        viewModelScope.launch {
            if (preset.name.isBlank()) {
                notice.value = "Name is required."
                return@launch
            }
            styleRepository.upsert(preset)
            editor.value = null
        }
    }
}

sealed interface StylesAction {
    data class QueryChanged(val value: String) : StylesAction
    data class ApplyStyle(val preset: StylePreset) : StylesAction
    data object ImportFromServer : StylesAction
    data object OpenEditor : StylesAction
    data class EditStyle(val preset: StylePreset) : StylesAction
    data object CloseEditor : StylesAction
    data class SaveStyle(val preset: StylePreset) : StylesAction
    data class DeleteStyle(val name: String) : StylesAction
    data object DismissNotice : StylesAction
}
