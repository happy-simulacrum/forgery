package com.forgery.app.feature.comfy.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ComfyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // TODO: Inject repositories here
) : ViewModel() {

    val uiState: StateFlow<ComfyUiState> = flow {
        // TODO: Replace with actual data flow
        emit(ComfyUiState.Success(data = listOf("Item 1", "Item 2")))
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ComfyUiState.Loading,
        )

    fun onAction(action: ComfyAction) {
        when (action) {
            is ComfyAction.ItemClicked -> handleItemClick(action.id)
        }
    }

    private fun handleItemClick(id: String) {
        // TODO: Handle item click
    }
}

sealed interface ComfyAction {
    data class ItemClicked(val id: String) : ComfyAction
}
