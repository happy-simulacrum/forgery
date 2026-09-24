package com.forgery.app.feature.lora.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.LoraRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.LoraItem
import com.forgery.app.core.model.LoraMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LoraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val generationRepository: GenerationRepository,
    private val loraRepository: LoraRepository,
    private val promptDrafts: PromptDraftRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val items = MutableStateFlow<List<LoraItem>>(emptyList())
    private val listLoading = MutableStateFlow(false)
    private val listError = MutableStateFlow<String?>(null)
    private val favoritesOnly = MutableStateFlow(false)
    private val detail = MutableStateFlow<LoraDetail?>(null)
    private val detailLoading = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)

    private data class Secondary(
        val favorites: Set<String> = emptySet(),
        val favoritesOnly: Boolean = false,
        val detail: LoraDetail? = null,
        val detailLoading: Boolean = false,
        val notice: String? = null,
    )

    private val secondary = combine(
        loraRepository.observeFavorites(),
        favoritesOnly,
        detail,
        detailLoading,
        notice
    ) { favs, favOnly, det, detLoading, note ->
        Secondary(favs, favOnly, det, detLoading, note)
    }

    val uiState: StateFlow<LoraUiState> = combine(
        query,
        items,
        listLoading,
        listError,
        secondary
    ) { q, list, loading, error, s ->
        LoraUiState.Success(
            query = q,
            items = list,
            listLoading = loading,
            listError = error,
            favorites = s.favorites,
            favoritesOnly = s.favoritesOnly,
            detail = s.detail,
            detailLoading = s.detailLoading,
            notice = s.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LoraUiState.Loading,
    )

    init {
        refresh()
    }

    fun onAction(action: LoraAction) {
        when (action) {
            is LoraAction.QueryChanged -> query.value = action.value
            LoraAction.Refresh -> refresh()
            LoraAction.ToggleFavoritesOnly -> favoritesOnly.value = !favoritesOnly.value
            is LoraAction.ItemClicked -> openDetail(action.item)
            LoraAction.CloseDetail -> detail.value = null
            is LoraAction.WeightChanged -> {
                detail.value = detail.value?.copy(weight = action.value)
            }
            is LoraAction.ToggleFavorite -> viewModelScope.launch {
                loraRepository.setFavorite(action.name, action.favorite)
            }
            LoraAction.Insert -> insert()
            LoraAction.DismissNotice -> notice.value = null
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            listLoading.value = true
            listError.value = null
            when (val r = generationRepository.fetchLoras()) {
                is Result.Success -> items.value = r.data
                is Result.Error -> listError.value = r.message
                is Result.Loading -> Unit
            }
            listLoading.value = false
        }
    }

    private fun openDetail(item: LoraItem) {
        viewModelScope.launch {
            // Sidecar file is <path-without-extension>.json (legacy: /file={base}.json).
            val base = item.path.substringBeforeLast('.', item.name).ifBlank { item.name }
            detailLoading.value = true
            val meta = when (val r = generationRepository.fetchLoraSidecar(base)) {
                is Result.Success -> r.data
                else -> LoraMeta()
            }
            detailLoading.value = false
            detail.value = LoraDetail(item, meta, meta.weight)
        }
    }

    private fun insert() {
        val d = detail.value ?: return
        viewModelScope.launch {
            val key = d.item.alias.ifBlank { d.item.name }
            val weight = "%.2f".format(Locale.US, d.weight).trimEnd('0').trimEnd('.')
            val tag = "<lora:$key:$weight>"
            val trigger = d.meta.trigger.trim()
            val positive = if (trigger.isBlank()) tag else "$tag $trigger"
            // Memory is the source of truth, disk is best-effort: on storage
            // failure keep the detail open so the user can retry.
            try {
                promptDrafts.appendPrompt(positive, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                notice.value = "Insert failed: storage unavailable"
                return@launch
            }
            detail.value = null
            notice.value = "Inserted $tag"
        }
    }
}

sealed interface LoraAction {
    data class QueryChanged(val value: String) : LoraAction
    data object Refresh : LoraAction
    data object ToggleFavoritesOnly : LoraAction
    data class ItemClicked(val item: LoraItem) : LoraAction
    data object CloseDetail : LoraAction
    data class WeightChanged(val value: Double) : LoraAction
    data class ToggleFavorite(val name: String, val favorite: Boolean) : LoraAction
    data object Insert : LoraAction
    data object DismissNotice : LoraAction
}
