package com.forgery.app.feature.gallery.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.forgery.app.core.common.Result
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.ui.LoadingState
import java.io.File
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
    onBackClick: () -> Unit,
    onNavigateToAnalyze: (String) -> Unit,
    onNavigateToInpaint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val item = state.item

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBackClick) { Text("CLOSE") }
            }

            if (item != null) {
                AsyncImage(
                    model = File(item.imagePath),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                SelectionContainer {
                    Text(
                        item.paramsJson,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.4f)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
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
            } else {
                Text(
                    "Image not found.",
                    modifier = Modifier.padding(16.dp),
                )
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

private fun toFileUri(imagePath: String): String =
    if ("://" in imagePath) imagePath else "file://$imagePath"

@Preview
@Composable
private fun GalleryDetailScreenPreview() {
    ForgeryTheme {
        GalleryDetailScreen(
            uiState = GalleryDetailUiState.Success(
                item = HistoryItem(7, "/img7.png", null, "{\"prompt\":\"cat\"}", "today"),
            ),
            onAction = {},
            onBackClick = {},
            onNavigateToAnalyze = {},
            onNavigateToInpaint = {},
        )
    }
}
