package com.forgery.app.feature.modules.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Forge Neo "VAE / Text Encoder" browser. Selection applies immediately to the
 * shared [ModulesSelectionRepository] (persisted) — DONE just goes back.
 */
@HiltViewModel
class ModulesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val generationRepository: GenerationRepository,
    private val modulesSelection: ModulesSelectionRepository,
) : ViewModel() {

    private val query = MutableStateFlow(TextFieldValue(""))
    private val catalog = MutableStateFlow<List<String>>(emptyList())
    private val listLoading = MutableStateFlow(false)
    private val listError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ModulesUiState> = combine(
        query,
        catalog,
        listLoading,
        listError,
        modulesSelection.observeModules(),
    ) { q, cat, loading, error, selected ->
        val raw = q.text
        val items = if (raw.isBlank()) cat else cat.filter { it.contains(raw, ignoreCase = true) }
        ModulesUiState.Success(
            query = q,
            items = items,
            totalCount = cat.size,
            selected = selected,
            listLoading = loading,
            listError = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ModulesUiState.Loading,
    )

    init {
        refresh()
    }

    fun onAction(action: ModulesAction) {
        when (action) {
            is ModulesAction.QueryChanged -> query.value = action.value
            ModulesAction.Refresh -> refresh()
            is ModulesAction.Toggle -> toggle(action.name)
            ModulesAction.Clear -> viewModelScope.launch {
                modulesSelection.saveModules(emptyList())
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            listLoading.value = true
            listError.value = null
            when (val r = generationRepository.fetchModules()) {
                is Result.Success -> {
                    catalog.value = r.data
                    // Drop persisted selections the server no longer offers.
                    val known = r.data.toSet()
                    val current = modulesSelection.observeModules().first()
                    val pruned = current.filter { it in known }
                    if (pruned.size != current.size) modulesSelection.saveModules(pruned)
                }
                is Result.Error -> listError.value = r.message
                is Result.Loading -> Unit
            }
            listLoading.value = false
        }
    }

    private fun toggle(name: String) {
        viewModelScope.launch {
            val current = modulesSelection.observeModules().first()
            val next = if (name in current) current - name else (current + name).sorted()
            modulesSelection.saveModules(next)
        }
    }
}

sealed interface ModulesAction {
    /** Raw search keystroke (local VM state, no repo). */
    data class QueryChanged(val value: TextFieldValue) : ModulesAction
    data class Toggle(val name: String) : ModulesAction
    data object Clear : ModulesAction
    data object Refresh : ModulesAction
}
