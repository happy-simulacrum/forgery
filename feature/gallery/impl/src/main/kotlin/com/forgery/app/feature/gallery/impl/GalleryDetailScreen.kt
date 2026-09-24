package com.forgery.app.feature.gallery.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.common.Result
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.ui.LoadingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
internal fun GalleryDetailRoute(
    onBackClick: () -> Unit,
    onNavigateToAnalyze: (String) -> Unit,
    onNavigateToInpaint: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deleted = (uiState as? GalleryDetailUiState.Success)?.deleted == true
    LaunchedEffect(deleted) {
        if (deleted) onBackClick()
    }

    GalleryDetailScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        observeItem = viewModel::observeItem,
        onBackClick = onBackClick,
        onNavigateToAnalyze = onNavigateToAnalyze,
        onNavigateToInpaint = onNavigateToInpaint,
        modifier = modifier,
    )
}

@Composable
internal fun GalleryDetailScreen(
    uiState: GalleryDetailUiState,
    onAction: (GalleryDetailAction) -> Unit,
    observeItem: (Long) -> Flow<HistoryItem?>,
    onBackClick: () -> Unit,
    onNavigateToAnalyze: (String) -> Unit,
    onNavigateToInpaint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            GalleryDetailUiState.Loading -> LoadingState(Modifier.fillMaxSize())
            is GalleryDetailUiState.Success -> GalleryDetailContent(
                state = uiState,
                onAction = onAction,
                observeItem = observeItem,
                onBackClick = onBackClick,
                onNavigateToAnalyze = onNavigateToAnalyze,
                onNavigateToInpaint = onNavigateToInpaint,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GalleryDetailContent(
    state: GalleryDetailUiState.Success,
    onAction: (GalleryDetailAction) -> Unit,
    observeItem: (Long) -> Flow<HistoryItem?>,
    onBackClick: () -> Unit,
    onNavigateToAnalyze: (String) -> Unit,
    onNavigateToInpaint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val item = state.item
    val ids = state.ids

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackClick) { Text("CLOSE") }
            }

            if (ids.isEmpty() || item == null) {
                Text(
                    "Image not found.",
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                var zoomLocked by remember { mutableStateOf(false) }
                // Fresh pager when the history size changes (delete): lands on
                // state.page (the neighbor) with a reset zoom, no out-of-range scroll.
                key(ids.size) {
                    val pagerState = rememberPagerState(
                        initialPage = state.page.coerceIn(0, ids.lastIndex),
                    ) { ids.size }
                    LaunchedEffect(pagerState.currentPage) {
                        zoomLocked = false
                        onAction(GalleryDetailAction.PageChanged(pagerState.currentPage))
                    }
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !zoomLocked,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { page ->
                        DetailPage(
                            id = ids.getOrNull(page),
                            observeItem = observeItem,
                            onZoomChanged = { zoomed ->
                                if (page == pagerState.currentPage) zoomLocked = zoomed
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        scope.launch {
                            when (val r = saveImageToGallery(context, item.imagePath)) {
                                is Result.Success -> snackbar.showSnackbar("Saved to gallery.")
                                is Result.Error -> snackbar.showSnackbar(r.message)
                                is Result.Loading -> Unit
                            }
                        }
                    }) { Icon(Icons.Filled.Download, contentDescription = "Save") }
                    IconButton(onClick = { onNavigateToInpaint(toFileUri(item.imagePath)) }) {
                        Icon(Icons.Filled.Brush, contentDescription = "Inpaint")
                    }
                    IconButton(onClick = { onAction(GalleryDetailAction.RequestDelete) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                    IconButton(onClick = { shareImage(context, item.imagePath) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                }
                Button(
                    onClick = { onNavigateToAnalyze(toFileUri(item.imagePath)) },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) { Text("ANALYZE") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { onAction(GalleryDetailAction.DismissDelete) },
            title = { Text("Delete this image?") },
            confirmButton = {
                TextButton(onClick = { onAction(GalleryDetailAction.ConfirmDelete) }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(GalleryDetailAction.DismissDelete) }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailPage(
    id: Long?,
    observeItem: (Long) -> Flow<HistoryItem?>,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (id == null) {
        LoadingState(modifier.fillMaxSize())
        return
    }
    val item by remember(id) { observeItem(id) }.collectAsStateWithLifecycle(initialValue = null)
    val path = item?.imagePath
    if (path == null) {
        LoadingState(modifier.fillMaxSize())
    } else {
        key(id) {
            ZoomableImage(
                imagePath = path,
                onZoomChanged = onZoomChanged,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

private fun toFileUri(imagePath: String): String =
    if ("://" in imagePath) imagePath else "file://$imagePath"

@Preview
@Composable
private fun GalleryDetailScreenPreview() {
    val previewItem = HistoryItem(7, "/img7.png", null, "{\"prompt\":\"cat\"}", "today")
    ForgeryTheme {
        GalleryDetailScreen(
            uiState = GalleryDetailUiState.Success(
                item = previewItem,
                ids = listOf(9L, 7L, 5L),
                page = 1,
            ),
            onAction = {},
            observeItem = { flowOf(previewItem) },
            onBackClick = {},
            onNavigateToAnalyze = {},
            onNavigateToInpaint = {},
        )
    }
}
