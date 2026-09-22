package com.forgery.app.feature.gallery.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState
import java.io.File

@Composable
internal fun GalleryRoute(
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GalleryScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        onNavigateToDetail = onNavigateToDetail,
        modifier = modifier,
    )
}

@Composable
internal fun GalleryScreen(
    uiState: GalleryUiState,
    onAction: (GalleryAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        GalleryUiState.Loading -> LoadingState(modifier)
        is GalleryUiState.Error -> ErrorState(uiState.message, modifier)
        is GalleryUiState.Success -> GalleryContent(
            state = uiState,
            onAction = onAction,
            onNavigateToDetail = onNavigateToDetail,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryContent(
    state: GalleryUiState.Success,
    onAction: (GalleryAction) -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("HISTORY (${state.total})", Modifier.weight(1f))
            if (state.selecting) {
                TextButton(onClick = { onAction(GalleryAction.ToggleSelectAll) }) { Text("ALL") }
                TextButton(onClick = { onAction(GalleryAction.RequestDeleteSelected) }) {
                    Text("DELETE (${state.selection.size})")
                }
                TextButton(onClick = { onAction(GalleryAction.ExitSelect) }) { Text("DONE") }
            } else {
                TextButton(onClick = { onAction(GalleryAction.EnterSelect) }) { Text("SELECT") }
                TextButton(onClick = { onAction(GalleryAction.RequestDeleteAll) }) { Text("CLEAR") }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(state.items, key = { it.id }) { item ->
                val selected = item.id in state.selection
                AsyncImage(
                    model = File(item.thumbPath ?: item.imagePath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp)
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier
                            },
                        )
                        .combinedClickable(
                            onClick = {
                                if (state.selecting) {
                                    onAction(GalleryAction.ItemClicked(item.id))
                                } else {
                                    onNavigateToDetail(item.id)
                                }
                            },
                            onLongClick = { onAction(GalleryAction.ItemLongClicked(item.id)) },
                        ),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onAction(GalleryAction.PrevPage) },
                enabled = state.page > 0,
            ) { Text("PREV") }
            Text(
                "Page ${state.page + 1}/${state.totalPages}",
                Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(
                onClick = { onAction(GalleryAction.NextPage) },
                enabled = state.page < state.totalPages - 1,
            ) { Text("NEXT") }
        }
    }

    state.confirm?.let { confirm ->
        val text = when (confirm) {
            GalleryConfirm.All -> "Clear all ${state.total} image(s)?"
            is GalleryConfirm.Selected -> "Delete ${confirm.ids.size} selected image(s)?"
            is GalleryConfirm.Single -> "Delete this image?"
        }
        AlertDialog(
            onDismissRequest = { onAction(GalleryAction.DismissDelete) },
            confirmButton = {
                TextButton(onClick = { onAction(GalleryAction.ConfirmDelete) }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(GalleryAction.DismissDelete) }) { Text("Cancel") }
            },
            text = { Text(text) },
        )
    }
}

@Preview
@Composable
private fun GalleryScreenPreview() {
    ForgeryTheme {
        GalleryScreen(
            uiState = GalleryUiState.Success(
                items = listOf(
                    HistoryItem(1, "/a.png", null, "{\"prompt\":\"cat\"}", "today"),
                    HistoryItem(2, "/b.png", null, "{\"prompt\":\"dog\"}", "today"),
                ),
                page = 0,
                totalPages = 2,
                total = 52,
                selection = setOf(1),
                selecting = true,
            ),
            onAction = {},
            onBackClick = {},
            onNavigateToDetail = {},
        )
    }
}
