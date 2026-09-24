package com.forgery.app.feature.gallery.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.data.HistoryRepository
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.feature.gallery.api.GalleryDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    // toRoute() needs the Android framework (Bundle) and throws on JVM unit tests;
    // the raw-key fallback reads the same back-stack-entry handle key.
    private val initialId: Long = runCatching {
        savedStateHandle.toRoute<GalleryDetailRoute>().id
    }.getOrNull() ?: savedStateHandle.get<Long>("id") ?: -1L

    // ids order: new -> old (contract of HistoryRepository.observeIds).
    private val idsFlow: Flow<List<Long>> = historyRepository.observeIds()

    // Null = not set yet: resolved from ids via initialId until the first PageChanged/delete.
    private val pageFlow = MutableStateFlow<Int?>(null)
    private val confirmFlow = MutableStateFlow(false)
    private val deletedFlow = MutableStateFlow(false)

    val uiState: StateFlow<GalleryDetailUiState> = combine(
        idsFlow,
        pageFlow,
        confirmFlow,
        deletedFlow,
    ) { ids, pageOrNull, confirm, deleted ->
        PagerSnapshot(
            ids = ids,
            page = effectivePage(ids, pageOrNull),
            confirmDelete = confirm,
            deleted = deleted,
        )
    }.flatMapLatest { snapshot ->
        val currentId = snapshot.ids.getOrNull(snapshot.page)
        val toState = { item: HistoryItem? ->
            GalleryDetailUiState.Success(
                item = item,
                ids = snapshot.ids,
                page = snapshot.page,
                confirmDelete = snapshot.confirmDelete,
                deleted = snapshot.deleted,
            )
        }
        if (currentId == null) flowOf(toState(null))
        else historyRepository.observeDetail(currentId).map { toState(it) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryDetailUiState.Loading,
    )

    fun observeItem(id: Long): Flow<HistoryItem?> =
        historyRepository.observeDetail(id)

    fun onAction(action: GalleryDetailAction) {
        when (action) {
            GalleryDetailAction.RequestDelete -> confirmFlow.value = true
            GalleryDetailAction.DismissDelete -> confirmFlow.value = false
            is GalleryDetailAction.PageChanged -> pageFlow.value = action.index
            GalleryDetailAction.ConfirmDelete -> {
                confirmFlow.value = false
                viewModelScope.launch {
                    val ids = idsFlow.first()
                    val page = effectivePage(ids, pageFlow.value)
                    val currentId = ids.getOrNull(page) ?: return@launch
                    historyRepository.delete(listOf(currentId))
                    if (ids.size <= 1) {
                        deletedFlow.value = true
                    } else {
                        // Stay on the same index: the next neighbor slides in;
                        // step back when the last page was deleted.
                        pageFlow.value = minOf(page, ids.size - 2)
                    }
                }
            }
        }
    }

    private fun effectivePage(ids: List<Long>, pageOrNull: Int?): Int {
        if (ids.isEmpty()) return 0
        if (pageOrNull != null) return pageOrNull.coerceIn(0, ids.lastIndex)
        return ids.indexOf(initialId).takeIf { it >= 0 } ?: 0
    }

    private data class PagerSnapshot(
        val ids: List<Long>,
        val page: Int,
        val confirmDelete: Boolean,
        val deleted: Boolean,
    )
}

sealed interface GalleryDetailUiState {
    data object Loading : GalleryDetailUiState

    data class Success(
        val item: HistoryItem?,
        val ids: List<Long> = emptyList(),
        val page: Int = 0,
        val confirmDelete: Boolean = false,
        val deleted: Boolean = false,
    ) : GalleryDetailUiState
}

sealed interface GalleryDetailAction {
    data object RequestDelete : GalleryDetailAction
    data object ConfirmDelete : GalleryDetailAction
    data object DismissDelete : GalleryDetailAction
    data class PageChanged(val index: Int) : GalleryDetailAction
}
