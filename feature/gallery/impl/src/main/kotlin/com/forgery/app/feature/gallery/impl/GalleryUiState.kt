package com.forgery.app.feature.gallery.impl

import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.model.GalleryCollection
import com.forgery.app.core.model.HistoryItem

enum class GalleryViewMode { SOLO, COLLECTIONS }

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
        val viewMode: GalleryViewMode = GalleryViewMode.SOLO,
        val collections: List<GalleryCollection> = emptyList(),
        val unsortedCount: Int = 0,
        val unsortedCover: HistoryItem? = null,
        val collectionDialog: GalleryCollectionDialog? = null,
        val dialogInput: TextFieldValue = TextFieldValue(""),
    ) : GalleryUiState

    data class Error(val message: String) : GalleryUiState
}

sealed interface GalleryConfirm {
    data object All : GalleryConfirm
    data class Selected(val ids: List<Long>) : GalleryConfirm
    data class Single(val id: Long) : GalleryConfirm
}

sealed interface GalleryCollectionDialog {
    data class Create(val ids: List<Long>) : GalleryCollectionDialog
    data class AddTo(val ids: List<Long>) : GalleryCollectionDialog
    data class DeleteCollection(val collectionId: Long, val name: String) : GalleryCollectionDialog
}
