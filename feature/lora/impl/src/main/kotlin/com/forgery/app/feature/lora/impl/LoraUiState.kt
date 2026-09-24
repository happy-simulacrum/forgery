package com.forgery.app.feature.lora.impl

import com.forgery.app.core.model.LoraItem
import com.forgery.app.core.model.LoraMeta

data class LoraDetail(
    val item: LoraItem,
    val meta: LoraMeta,
    val weight: Double,
)

sealed interface LoraUiState {
    data object Loading : LoraUiState

    data class Success(
        val query: String = "",
        val items: List<LoraItem> = emptyList(),
        val listLoading: Boolean = false,
        val listError: String? = null,
        val favorites: Set<String> = emptySet(),
        val favoritesOnly: Boolean = false,
        val detail: LoraDetail? = null,
        val detailLoading: Boolean = false,
        val notice: String? = null,
    ) : LoraUiState {
        val visible: List<LoraItem>
            get() {
                var list = if (query.isBlank()) {
                    items
                } else {
                    items.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.alias.contains(query, ignoreCase = true)
                    }
                }
                if (favoritesOnly) list = list.filter { it.name in favorites }
                return list
            }
    }

    data class Error(val message: String) : LoraUiState
}
