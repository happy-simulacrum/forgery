package com.forgery.app.feature.modules.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.input.TextFieldValue
import androidx.navigation.toRoute
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModelParamsRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.feature.modules.api.ModulesRoute
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
 * Forge Neo "VAE / Text Encoder" browser, per-model.
 *
 * Selection is read from the [ModelParamsRepository] record of [modelTitle]
 * (route arg; default — nothing selected) and merge-written back into that
 * record (other entry fields preserved). Every write is also mirrored into
 * the shared [ModulesSelectionRepository] (persisted) — the live source
 * GEN/Inpaint UI reads — DONE just goes back.
 */
@HiltViewModel
class ModulesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val generationRepository: GenerationRepository,
    private val modulesSelection: ModulesSelectionRepository,
    private val modelParams: ModelParamsRepository,
) : ViewModel() {

    // toRoute() needs the Android framework (Bundle) and throws on JVM unit tests;
    // the raw-key fallback reads the same back-stack-entry handle key.
    private val modelTitle: String = runCatching {
        savedStateHandle.toRoute<ModulesRoute>().modelTitle
    }.getOrNull() ?: savedStateHandle.get<String>("modelTitle").orEmpty()

    private val query = MutableStateFlow(TextFieldValue(""))
    private val catalog = MutableStateFlow<List<String>>(emptyList())
    private val listLoading = MutableStateFlow(false)
    private val listError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ModulesUiState> = combine(
        query,
        catalog,
        listLoading,
        listError,
        modelParams.observeForModel(modelTitle),
    ) { q, cat, loading, error, entry ->
        val raw = q.text
        val items = if (raw.isBlank()) cat else cat.filter { it.contains(raw, ignoreCase = true) }
        ModulesUiState.Success(
            query = q,
            items = items,
            totalCount = cat.size,
            selected = entry?.additionalModules ?: emptyList(),
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
                writeModules(emptyList())
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
                    // Drop persisted selections the server no longer offers:
                    // prune the per-model entry (merge-write + mirror)…
                    val known = r.data.toSet()
                    val entry = modelParams.observeForModel(modelTitle).first()
                    val current = entry?.additionalModules ?: emptyList()
                    val pruned = current.filter { it in known }
                    if (pruned.size != current.size) writeModules(pruned)
                    // …and keep the global live mirror hygienic.
                    val mirror = modulesSelection.observeModules().first()
                    val prunedMirror = mirror.filter { it in known }
                    if (prunedMirror.size != mirror.size) modulesSelection.saveModules(prunedMirror)
                }
                is Result.Error -> listError.value = r.message
                is Result.Loading -> Unit
            }
            listLoading.value = false
        }
    }

    private fun toggle(name: String) {
        viewModelScope.launch {
            val current = modelParams.observeForModel(modelTitle).first()?.additionalModules
                ?: emptyList()
            val next = if (name in current) current - name else (current + name).sorted()
            writeModules(next)
        }
    }

    /**
     * Merge-writes [next] into this model's record (other entry fields
     * preserved; a missing record starts from [ModelLastUsed] defaults) and
     * mirrors it into the global live selection GEN/Inpaint read.
     * A blank [modelTitle] is a no-op in the repo — the mirror still updates.
     */
    private suspend fun writeModules(next: List<String>) {
        val prev = modelParams.observeForModel(modelTitle).first()
        modelParams.saveForModel(modelTitle, (prev ?: ModelLastUsed()).copy(additionalModules = next))
        modulesSelection.saveModules(next)
    }
}

sealed interface ModulesAction {
    /** Raw search keystroke (local VM state, no repo). */
    data class QueryChanged(val value: TextFieldValue) : ModulesAction
    data class Toggle(val name: String) : ModulesAction
    data object Clear : ModulesAction
    data object Refresh : ModulesAction
}
