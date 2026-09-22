package com.forgery.app.feature.gallery.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.data.HistoryRepository
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.feature.gallery.api.GalleryDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val id: Long = runCatching {
        savedStateHandle.toRoute<GalleryDetailRoute>().id
    }.getOrNull() ?: savedStateHandle.get<Long>("id") ?: -1L

    private val confirmFlow = MutableStateFlow(false)
    private val deletedFlow = MutableStateFlow(false)

    val uiState: StateFlow<GalleryDetailUiState> = combine(
        historyRepository.observeDetail(id),
        confirmFlow,
        deletedFlow,
    ) { item, confirm, deleted ->
        GalleryDetailUiState.Success(
            item = item,
            confirmDelete = confirm,
            deleted = deleted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryDetailUiState.Loading,
    )

    fun onAction(action: GalleryDetailAction) {
        when (action) {
            GalleryDetailAction.RequestDelete -> confirmFlow.value = true
            GalleryDetailAction.DismissDelete -> confirmFlow.value = false
            GalleryDetailAction.ConfirmDelete -> {
                confirmFlow.value = false
                viewModelScope.launch {
                    historyRepository.delete(listOf(id))
                    deletedFlow.value = true
                }
            }
        }
    }
}

sealed interface GalleryDetailUiState {
    data object Loading : GalleryDetailUiState

    data class Success(
        val item: HistoryItem?,
        val confirmDelete: Boolean = false,
        val deleted: Boolean = false,
    ) : GalleryDetailUiState
}

sealed interface GalleryDetailAction {
    data object RequestDelete : GalleryDetailAction
    data object ConfirmDelete : GalleryDetailAction
    data object DismissDelete : GalleryDetailAction
}
