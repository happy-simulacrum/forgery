package com.forgery.app.feature.gallery.impl

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.data.CollectionRepository
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
    private val collectionRepository: CollectionRepository,
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 50
    }

    private val page = MutableStateFlow(0)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val selectingFlow = MutableStateFlow(false)
    private val confirmFlow = MutableStateFlow<GalleryConfirm?>(null)
    private val viewModeFlow = MutableStateFlow(GalleryViewMode.SOLO)
    private val collectionDialogFlow = MutableStateFlow<GalleryCollectionDialog?>(null)
    private val dialogInputFlow = MutableStateFlow(TextFieldValue(""))

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

    private data class CollectionState(
        val mode: GalleryViewMode = GalleryViewMode.SOLO,
        val collections: List<com.forgery.app.core.model.GalleryCollection> = emptyList(),
        val unsortedCount: Int = 0,
        val dialog: GalleryCollectionDialog? = null,
        val dialogInput: TextFieldValue = TextFieldValue(""),
    )

    private val collectionState = combine(
        viewModeFlow,
        collectionRepository.observeCollections(),
        collectionRepository.countUnsorted(),
        collectionDialogFlow,
        dialogInputFlow,
    ) { mode, collections, unsortedCount, dialog, input ->
        CollectionState(mode, collections, unsortedCount, dialog, input)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val unsortedCoverFlow = collectionRepository.observeUnsorted(1, 0)

    private data class PagingState(
        val page: Int = 0,
        val items: List<com.forgery.app.core.model.HistoryItem> = emptyList(),
        val total: Int = 0,
    )

    private val pagingState = combine(
        page,
        pageItems,
        historyRepository.count(),
    ) { p, items, total ->
        PagingState(p, items, total)
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        pagingState,
        selectState,
        collectionState,
        unsortedCoverFlow,
    ) { pg, s, c, unsortedCover ->
        val totalPages = maxOf(1, (pg.total + PAGE_SIZE - 1) / PAGE_SIZE)
        GalleryUiState.Success(
            items = pg.items,
            page = pg.page.coerceIn(0, totalPages - 1),
            totalPages = totalPages,
            total = pg.total,
            selection = s.selection,
            selecting = s.selecting,
            confirm = s.confirm,
            viewMode = c.mode,
            collections = c.collections,
            unsortedCount = c.unsortedCount,
            unsortedCover = unsortedCover.firstOrNull(),
            collectionDialog = c.dialog,
            dialogInput = c.dialogInput,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState.Loading,
    )

    init {
        // H-6: clamp the page *source* when the total shrinks underneath us
        // (e.g. external tail deletion). The display coerceIn above is not
        // enough: page.value stays out of range, observePage keeps serving an
        // empty page and Next/Prev stay blocked forever.
        viewModelScope.launch {
            historyRepository.count().collect { total ->
                val max = maxOf(0, (total + PAGE_SIZE - 1) / PAGE_SIZE - 1)
                if (page.value > max) page.value = max
            }
        }
    }

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
            is GalleryAction.SetViewMode -> {
                viewModeFlow.value = action.mode
                if (action.mode == GalleryViewMode.COLLECTIONS) {
                    selectingFlow.value = false
                    selection.value = emptySet()
                    confirmFlow.value = null
                }
            }
            is GalleryAction.RequestCreateCollection -> {
                if (action.ids.isNotEmpty()) {
                    dialogInputFlow.value = TextFieldValue("")
                    collectionDialogFlow.value = GalleryCollectionDialog.Create(action.ids)
                }
            }
            is GalleryAction.RequestAddToCollection -> {
                if (action.ids.isNotEmpty()) {
                    collectionDialogFlow.value = GalleryCollectionDialog.AddTo(action.ids)
                }
            }
            is GalleryAction.CollectionNameChanged -> {
                dialogInputFlow.value = action.value
            }
            GalleryAction.ConfirmCreateCollection -> {
                val dialog = collectionDialogFlow.value as? GalleryCollectionDialog.Create
                    ?: return
                val name = dialogInputFlow.value.text.trim()
                if (name.isEmpty()) return
                viewModelScope.launch {
                    runCatching { collectionRepository.createCollection(name, dialog.ids) }
                    collectionDialogFlow.value = null
                    dialogInputFlow.value = TextFieldValue("")
                    selection.value = emptySet()
                    selectingFlow.value = false
                }
            }
            is GalleryAction.ConfirmAddToCollection -> {
                val dialog = collectionDialogFlow.value as? GalleryCollectionDialog.AddTo
                    ?: return
                viewModelScope.launch {
                    runCatching { collectionRepository.addToCollection(action.collectionId, dialog.ids) }
                    collectionDialogFlow.value = null
                    selection.value = emptySet()
                    selectingFlow.value = false
                }
            }
            is GalleryAction.RequestDeleteCollection -> {
                collectionDialogFlow.value =
                    GalleryCollectionDialog.DeleteCollection(action.collectionId, action.name)
            }
            GalleryAction.ConfirmDeleteCollection -> {
                val dialog = collectionDialogFlow.value as? GalleryCollectionDialog.DeleteCollection
                    ?: return
                viewModelScope.launch {
                    runCatching { collectionRepository.deleteCollection(dialog.collectionId) }
                    collectionDialogFlow.value = null
                }
            }
            GalleryAction.DismissCollectionDialog -> {
                collectionDialogFlow.value = null
                dialogInputFlow.value = TextFieldValue("")
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
    data class SetViewMode(val mode: GalleryViewMode) : GalleryAction
    data class RequestCreateCollection(val ids: List<Long>) : GalleryAction
    data class RequestAddToCollection(val ids: List<Long>) : GalleryAction
    data class CollectionNameChanged(val value: TextFieldValue) : GalleryAction
    data object ConfirmCreateCollection : GalleryAction
    data class ConfirmAddToCollection(val collectionId: Long) : GalleryAction
    data class RequestDeleteCollection(val collectionId: Long, val name: String) : GalleryAction
    data object ConfirmDeleteCollection : GalleryAction
    data object DismissCollectionDialog : GalleryAction
}
