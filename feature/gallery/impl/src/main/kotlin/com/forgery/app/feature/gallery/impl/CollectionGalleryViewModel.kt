package com.forgery.app.feature.gallery.impl

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.data.CollectionRepository
import com.forgery.app.core.model.GalleryCollection
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.feature.gallery.api.GalleryCollectionRoute
import com.forgery.app.feature.gallery.api.UNSORTED_COLLECTION_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val collectionRepository: CollectionRepository,
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 50
    }

    // toRoute() needs the Android framework (Bundle) and throws on JVM unit tests;
    // the raw-key fallback reads the same back-stack-entry handle key.
    val collectionId: Long = runCatching {
        savedStateHandle.toRoute<GalleryCollectionRoute>().collectionId
    }.getOrNull() ?: savedStateHandle.get<Long>("collectionId") ?: UNSORTED_COLLECTION_ID

    val isUnsorted: Boolean = collectionId == UNSORTED_COLLECTION_ID

    private val page = MutableStateFlow(0)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val selectingFlow = MutableStateFlow(false)
    private val confirmDeleteCollectionFlow = MutableStateFlow(false)
    private val deletedFlow = MutableStateFlow(false)
    private val renameInputFlow = MutableStateFlow<TextFieldValue?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pageItems: Flow<List<HistoryItem>> = page.flatMapLatest { p ->
        collectionRepository.observeItems(collectionId, PAGE_SIZE, p * PAGE_SIZE)
    }

    private data class PagingState(
        val page: Int = 0,
        val items: List<HistoryItem> = emptyList(),
        val total: Int = 0,
        val collection: GalleryCollection? = null,
    )

    private val pagingState = combine(
        page,
        pageItems,
        collectionRepository.countInCollection(collectionId),
        collectionRepository.observeCollection(collectionId),
    ) { p, items, total, collection ->
        PagingState(p, items, total, collection)
    }

    private data class SelectionState(
        val selection: Set<Long> = emptySet(),
        val selecting: Boolean = false,
        val confirmDeleteCollection: Boolean = false,
        val deleted: Boolean = false,
        val renameInput: TextFieldValue? = null,
    )

    private val selectionState = combine(
        selection,
        selectingFlow,
        confirmDeleteCollectionFlow,
        deletedFlow,
        renameInputFlow,
    ) { sel, selecting, confirmDelete, deleted, renameInput ->
        SelectionState(sel, selecting, confirmDelete, deleted, renameInput)
    }

    val uiState: StateFlow<CollectionGalleryUiState> = combine(
        pagingState,
        selectionState,
    ) { pg, s ->
        val totalPages = maxOf(1, (pg.total + PAGE_SIZE - 1) / PAGE_SIZE)
        CollectionGalleryUiState.Success(
            collection = pg.collection,
            items = pg.items,
            page = pg.page.coerceIn(0, totalPages - 1),
            totalPages = totalPages,
            total = pg.total,
            selection = s.selection,
            selecting = s.selecting,
            confirmDeleteCollection = s.confirmDeleteCollection,
            deleted = s.deleted,
            renameInput = s.renameInput,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionGalleryUiState.Loading,
    )

    init {
        viewModelScope.launch {
            collectionRepository.countInCollection(collectionId).collect { total ->
                val max = maxOf(0, (total + PAGE_SIZE - 1) / PAGE_SIZE - 1)
                if (page.value > max) page.value = max
            }
        }
    }

    fun onAction(action: CollectionGalleryAction) {
        val current = (uiState.value as? CollectionGalleryUiState.Success) ?: return
        when (action) {
            CollectionGalleryAction.NextPage -> {
                if (current.page < current.totalPages - 1) page.value = current.page + 1
            }
            CollectionGalleryAction.PrevPage -> {
                if (current.page > 0) page.value = current.page - 1
            }
            is CollectionGalleryAction.ItemClicked -> {
                if (current.selecting) {
                    selection.value =
                        if (action.id in selection.value) selection.value - action.id
                        else selection.value + action.id
                }
            }
            is CollectionGalleryAction.ItemLongClicked -> {
                selectingFlow.value = true
                if (action.id !in selection.value) selection.value = selection.value + action.id
            }
            CollectionGalleryAction.EnterSelect -> selectingFlow.value = true
            CollectionGalleryAction.ExitSelect -> {
                selectingFlow.value = false
                selection.value = emptySet()
            }
            CollectionGalleryAction.ToggleSelectAll -> {
                selection.value = if (current.selection.size == current.items.size) {
                    emptySet()
                } else {
                    current.items.map { it.id }.toSet()
                }
            }
            CollectionGalleryAction.RequestRemoveSelected -> {
                val ids = current.selection.toList()
                if (ids.isEmpty()) return
                viewModelScope.launch {
                    runCatching {
                        collectionRepository.removeFromCollection(collectionId, ids)
                    }
                    selection.value = emptySet()
                    selectingFlow.value = false
                }
            }
            is CollectionGalleryAction.CommitReorder -> {
                if (isUnsorted || action.visibleOrderedIds.isEmpty()) return
                viewModelScope.launch {
                    runCatching {
                        val full = collectionRepository.observeIds(collectionId).first()
                        val window = current.items.map { it.id }.toSet()
                        val reorderedWindow = action.visibleOrderedIds.filter { it in window }
                        if (reorderedWindow.size != window.size) return@launch
                        val replacement = reorderedWindow.iterator()
                        val newFull = full.map { id ->
                            if (id in window) replacement.next() else id
                        }
                        collectionRepository.reorder(collectionId, newFull)
                    }
                }
            }
            CollectionGalleryAction.RequestDeleteCollection -> {
                confirmDeleteCollectionFlow.value = true
            }
            CollectionGalleryAction.DismissDeleteCollection -> {
                confirmDeleteCollectionFlow.value = false
            }
            CollectionGalleryAction.ConfirmDeleteCollection -> {
                confirmDeleteCollectionFlow.value = false
                viewModelScope.launch {
                    runCatching { collectionRepository.deleteCollection(collectionId) }
                    deletedFlow.value = true
                }
            }
            is CollectionGalleryAction.RenameOpened -> {
                renameInputFlow.value = TextFieldValue(current.collection?.name ?: "")
            }
            is CollectionGalleryAction.RenameChanged -> {
                renameInputFlow.value = action.value
            }
            CollectionGalleryAction.RenameDismissed -> {
                renameInputFlow.value = null
            }
            CollectionGalleryAction.ConfirmRename -> {
                val name = renameInputFlow.value?.text?.trim()
                if (name.isNullOrEmpty()) return
                viewModelScope.launch {
                    runCatching { collectionRepository.rename(collectionId, name) }
                    renameInputFlow.value = null
                }
            }
        }
    }
}

sealed interface CollectionGalleryUiState {
    data object Loading : CollectionGalleryUiState

    data class Success(
        val collection: GalleryCollection?,
        val items: List<HistoryItem> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val total: Int = 0,
        val selection: Set<Long> = emptySet(),
        val selecting: Boolean = false,
        val confirmDeleteCollection: Boolean = false,
        val deleted: Boolean = false,
        val renameInput: TextFieldValue? = null,
    ) : CollectionGalleryUiState
}

sealed interface CollectionGalleryAction {
    data object NextPage : CollectionGalleryAction
    data object PrevPage : CollectionGalleryAction
    data class ItemClicked(val id: Long) : CollectionGalleryAction
    data class ItemLongClicked(val id: Long) : CollectionGalleryAction
    data object EnterSelect : CollectionGalleryAction
    data object ExitSelect : CollectionGalleryAction
    data object ToggleSelectAll : CollectionGalleryAction
    data object RequestRemoveSelected : CollectionGalleryAction
    /** Visible page items in their new order after a drag; VM splices them into the full order. */
    data class CommitReorder(val visibleOrderedIds: List<Long>) : CollectionGalleryAction
    data object RequestDeleteCollection : CollectionGalleryAction
    data object DismissDeleteCollection : CollectionGalleryAction
    data object ConfirmDeleteCollection : CollectionGalleryAction
    data object RenameOpened : CollectionGalleryAction
    data class RenameChanged(val value: TextFieldValue) : CollectionGalleryAction
    data object RenameDismissed : CollectionGalleryAction
    data object ConfirmRename : CollectionGalleryAction
}
