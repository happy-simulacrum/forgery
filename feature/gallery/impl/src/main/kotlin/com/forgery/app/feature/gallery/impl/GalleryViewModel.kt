package com.forgery.app.feature.gallery.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.data.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 50
    }

    private val page = MutableStateFlow(0)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val selectingFlow = MutableStateFlow(false)
    private val confirmFlow = MutableStateFlow<GalleryConfirm?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pageItems = page.flatMapLatest { p ->
        historyRepository.observePage(PAGE_SIZE, p * PAGE_SIZE)
    }

    private data class SelectState(
        val selection: Set<Long> = emptySet(),
        val selecting: Boolean = false,
        val confirm: GalleryConfirm? = null,
    )

    private val selectState = combine(selection, selectingFlow, confirmFlow) { sel, selecting, confirm ->
        SelectState(sel, selecting, confirm)
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        page,
        pageItems,
        historyRepository.count(),
        selectState,
    ) { p, items, total, s ->
        val totalPages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
        GalleryUiState.Success(
            items = items,
            page = p.coerceIn(0, totalPages - 1),
            totalPages = totalPages,
            total = total,
            selection = s.selection,
            selecting = s.selecting,
            confirm = s.confirm,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState.Loading,
    )

    fun onAction(action: GalleryAction) {
        val current = (uiState.value as? GalleryUiState.Success) ?: run {
            // Allow page nav even before first emission? No — need bounds; ignore.
            return
        }
        when (action) {
            GalleryAction.NextPage -> {
                if (current.page < current.totalPages - 1) page.value = current.page + 1
            }
            GalleryAction.PrevPage -> {
                if (current.page > 0) page.value = current.page - 1
            }
            is GalleryAction.ItemClicked -> {
                if (current.selecting) {
                    toggle(action.id)
                }
                // Outside select mode navigation is handled by the Screen via onNavigateToDetail.
            }
            is GalleryAction.ItemLongClicked -> {
                selectingFlow.value = true
                toggle(action.id)
            }
            GalleryAction.ToggleSelectAll -> {
                selection.value = if (current.selection.size == current.items.size) {
                    emptySet()
                } else {
                    current.items.map { it.id }.toSet()
                }
            }
            GalleryAction.EnterSelect -> {
                selectingFlow.value = true
            }
            GalleryAction.ExitSelect -> {
                selectingFlow.value = false
                selection.value = emptySet()
            }
            GalleryAction.RequestDeleteAll -> {
                confirmFlow.value = GalleryConfirm.All
            }
            GalleryAction.RequestDeleteSelected -> {
                val ids = current.selection.toList()
                if (ids.isNotEmpty()) {
                    confirmFlow.value = GalleryConfirm.Selected(ids)
                }
            }
            is GalleryAction.RequestDeleteSingle -> {
                confirmFlow.value = GalleryConfirm.Single(action.id)
            }
            GalleryAction.DismissDelete -> {
                confirmFlow.value = null
            }
            GalleryAction.ConfirmDelete -> {
                when (val confirm = confirmFlow.value) {
                    GalleryConfirm.All -> {
                        viewModelScope.launch { historyRepository.clear() }
                        page.value = 0
                        selection.value = emptySet()
                        selectingFlow.value = false
                        confirmFlow.value = null
                    }
                    is GalleryConfirm.Selected -> {
                        viewModelScope.launch { historyRepository.delete(confirm.ids) }
                        selection.value = emptySet()
                        selectingFlow.value = false
                        confirmFlow.value = null
                    }
                    is GalleryConfirm.Single -> {
                        viewModelScope.launch { historyRepository.delete(listOf(confirm.id)) }
                        selection.value = emptySet()
                        selectingFlow.value = false
                        confirmFlow.value = null
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun toggle(id: Long) {
        selection.value = if (id in selection.value) selection.value - id else selection.value + id
    }
}

sealed interface GalleryAction {
    data object NextPage : GalleryAction
    data object PrevPage : GalleryAction
    data class ItemClicked(val id: Long) : GalleryAction
    data class ItemLongClicked(val id: Long) : GalleryAction
    data object ToggleSelectAll : GalleryAction
    data object EnterSelect : GalleryAction
    data object ExitSelect : GalleryAction
    data object RequestDeleteAll : GalleryAction
    data object RequestDeleteSelected : GalleryAction
    data class RequestDeleteSingle(val id: Long) : GalleryAction
    data object ConfirmDelete : GalleryAction
    data object DismissDelete : GalleryAction
}
