package com.forgery.app.feature.gallery.impl

import com.forgery.app.core.model.HistoryItem

sealed interface GalleryUiState {
    data object Loading : GalleryUiState

    data class Success(
        val items: List<HistoryItem> = emptyList(),
        val page: Int = 0,
        val totalPages: Int = 1,
        val total: Int = 0,
        val selection: Set<Long> = emptySet(),
        val selecting: Boolean = false,
        val confirm: GalleryConfirm? = null,
    ) : GalleryUiState

    data class Error(val message: String) : GalleryUiState
}

sealed interface GalleryConfirm {
    data object All : GalleryConfirm
    data class Selected(val ids: List<Long>) : GalleryConfirm
    data class Single(val id: Long) : GalleryConfirm
}
