package com.forgery.app.feature.gallery.impl

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.ui.DraftTextField
import com.forgery.app.core.ui.LoadingState
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun CollectionGalleryRoute(
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long, Long) -> Unit,
    onCollectionDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionGalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deleted = (uiState as? CollectionGalleryUiState.Success)?.deleted == true
    LaunchedEffect(deleted) {
        if (deleted) onCollectionDeleted()
    }

    CollectionGalleryScreen(
        uiState = uiState,
        collectionId = viewModel.collectionId,
        isUnsorted = viewModel.isUnsorted,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        onNavigateToDetail = { id -> onNavigateToDetail(id, viewModel.collectionId) },
        modifier = modifier,
    )
}

@Composable
internal fun CollectionGalleryScreen(
    uiState: CollectionGalleryUiState,
    collectionId: Long,
    isUnsorted: Boolean,
    onAction: (CollectionGalleryAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        CollectionGalleryUiState.Loading -> LoadingState(modifier)
        is CollectionGalleryUiState.Success -> CollectionGalleryContent(
            state = uiState,
            collectionId = collectionId,
            isUnsorted = isUnsorted,
            onAction = onAction,
            onBackClick = onBackClick,
            onNavigateToDetail = onNavigateToDetail,
            modifier = modifier,
        )
    }
}

@Composable
private fun CollectionGalleryContent(
    state: CollectionGalleryUiState.Success,
    collectionId: Long,
    isUnsorted: Boolean,
    onAction: (CollectionGalleryAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBackClick) { Text("BACK") }
            Text(
                "${state.collection?.name ?: "…"} (${state.total})",
                Modifier.weight(1f),
            )
            if (!isUnsorted) {
                TextButton(onClick = { onAction(CollectionGalleryAction.RenameOpened) }) {
                    Text("RENAME")
                }
                TextButton(onClick = { onAction(CollectionGalleryAction.RequestDeleteCollection) }) {
                    Text("DELETE")
                }
            }
        }

        if (!isUnsorted && !state.selecting) {
            Text(
                "Long-press + drag to reorder.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selecting) {
                TextButton(onClick = { onAction(CollectionGalleryAction.ToggleSelectAll) }) {
                    Text("ALL")
                }
                if (!isUnsorted) {
                    TextButton(onClick = { onAction(CollectionGalleryAction.RequestRemoveSelected) }) {
                        Text("REMOVE (${state.selection.size})")
                    }
                }
                TextButton(onClick = { onAction(CollectionGalleryAction.ExitSelect) }) {
                    Text("DONE")
                }
            } else {
                TextButton(onClick = { onAction(CollectionGalleryAction.EnterSelect) }) {
                    Text("SELECT")
                }
            }
        }

        if (state.items.isEmpty()) {
            Text("Empty collection.", Modifier.padding(16.dp))
        } else if (isUnsorted || state.selecting) {
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
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable {
                                if (state.selecting) {
                                    onAction(CollectionGalleryAction.ItemClicked(item.id))
                                } else {
                                    onNavigateToDetail(item.id)
                                }
                            },
                    )
                }
            }
        } else {
            ReorderableGrid(
                items = state.items,
                onCommit = { orderedIds ->
                    onAction(CollectionGalleryAction.CommitReorder(orderedIds))
                },
                onTap = onNavigateToDetail,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onAction(CollectionGalleryAction.PrevPage) },
                enabled = state.page > 0,
            ) { Text("PREV") }
            Text(
                "Page ${state.page + 1}/${state.totalPages}",
                Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(
                onClick = { onAction(CollectionGalleryAction.NextPage) },
                enabled = state.page < state.totalPages - 1,
            ) { Text("NEXT") }
        }
    }

    if (state.confirmDeleteCollection) {
        AlertDialog(
            onDismissRequest = { onAction(CollectionGalleryAction.DismissDeleteCollection) },
            title = { Text("Delete collection?") },
            text = { Text("Images stay in SOLO.") },
            confirmButton = {
                TextButton(onClick = { onAction(CollectionGalleryAction.ConfirmDeleteCollection) }) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(CollectionGalleryAction.DismissDeleteCollection) }) {
                    Text("Cancel")
                }
            },
        )
    }

    state.renameInput?.let { input ->
        AlertDialog(
            onDismissRequest = { onAction(CollectionGalleryAction.RenameDismissed) },
            title = { Text("Rename collection") },
            text = {
                DraftTextField(
                    value = input,
                    onValueChange = { onAction(CollectionGalleryAction.RenameChanged(it)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(CollectionGalleryAction.ConfirmRename) },
                    enabled = input.text.isNotBlank(),
                ) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(CollectionGalleryAction.RenameDismissed) }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private const val DRAG_EDGE_PX = 120f
private const val DRAG_SCROLL_STEP_PX = 24f
private const val COLLECTION_GRID_SPAN = 3

/**
 * Grid with long-press drag-to-reorder, mirroring the QUE queue mechanics:
 * the dragged tile is anchored to its live layout slot (re-synced from
 * layoutInfo on every move, so swaps don't displace it from the finger),
 * neighbors shift by single steps with placement animation, the drop target
 * comes from measured item rects (no estimated cell math), and the edge
 * auto-scrolls. Transient order lives in the screen; the committed id list
 * goes to the ViewModel on drop.
 */
@Composable
private fun ReorderableGrid(
    items: List<HistoryItem>,
    onCommit: (List<Long>) -> Unit,
    onTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val draggedIdState = remember { mutableStateOf<Long?>(null) }
    val dragOffsetState = remember { mutableStateOf(Offset.Zero) }
    val visualOrderState = remember { mutableStateOf<List<HistoryItem>?>(null) }
    val dragBaseCenterState = remember { mutableStateOf(Offset.Zero) }
    val dragLayoutCenterState = remember { mutableStateOf(Offset.Zero) }
    val draggedId by draggedIdState
    val dragOffset by dragOffsetState
    val dragBaseCenter by dragBaseCenterState
    val dragLayoutCenter by dragLayoutCenterState
    val visualOrder by visualOrderState

    val itemsRef = rememberUpdatedState(items)
    val onCommitRef = rememberUpdatedState(onCommit)
    val onTapRef = rememberUpdatedState(onTap)

    LaunchedEffect(items) {
        if (draggedIdState.value == null) {
            visualOrderState.value = null
        }
    }

    val display = visualOrder ?: items

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLLECTION_GRID_SPAN),
        state = gridState,
        userScrollEnabled = draggedId == null,
        modifier = modifier.fillMaxSize(),
    ) {
        items(display, key = { it.id }) { item ->
            val isDragged = item.id == draggedId
            AsyncImage(
                model = File(item.thumbPath ?: item.imagePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .padding(2.dp)
                    .then(
                        if (isDragged) {
                            Modifier.border(
                                BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Dragged tile is positioned manually via
                        // translation: placement animation would fight it.
                        if (isDragged) {
                            Modifier
                        } else {
                            Modifier.animateItem(
                                placementSpec = spring(stiffness = Spring.StiffnessHigh),
                            )
                        },
                    )
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            // Anchor to the live layout slot: after every
                            // swap the slot moves, translation compensates
                            // so the tile stays under the finger.
                            translationX = (dragBaseCenter.x + dragOffset.x) -
                                dragLayoutCenter.x
                            translationY = (dragBaseCenter.y + dragOffset.y) -
                                dragLayoutCenter.y
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                    }
                    .clickable(enabled = draggedId == null) {
                        onTapRef.value(item.id)
                    }
                    .pointerInput(item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val base = itemsRef.value
                                if (base.none { it.id == item.id }) return@detectDragGesturesAfterLongPress
                                dragOffsetState.value = Offset.Zero
                                val tile = gridState.layoutInfo.visibleItemsInfo
                                    .find { it.key == item.id }
                                val center = if (tile != null) {
                                    Offset(
                                        tile.offset.x + tile.size.width / 2f,
                                        tile.offset.y + tile.size.height / 2f,
                                    )
                                } else {
                                    Offset.Zero
                                }
                                // Layout slot the dragged tile currently occupies;
                                // re-synced every onDrag so translation stays
                                // anchored to the live slot, not the start one.
                                dragBaseCenterState.value = center
                                dragLayoutCenterState.value = center
                                if (visualOrderState.value == null) {
                                    visualOrderState.value = base.toList()
                                }
                                draggedIdState.value = item.id
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                val current = visualOrderState.value ?: itemsRef.value
                                val original = itemsRef.value.map { it.id }
                                val reordered = current.map { it.id }
                                visualOrderState.value = null
                                draggedIdState.value = null
                                dragOffsetState.value = Offset.Zero
                                if (reordered != original) {
                                    onCommitRef.value(reordered)
                                }
                            },
                            onDragCancel = {
                                visualOrderState.value = null
                                draggedIdState.value = null
                                dragOffsetState.value = Offset.Zero
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetState.value += Offset(dragAmount.x, dragAmount.y)
                                val info = gridState.layoutInfo
                                val finger = dragBaseCenterState.value +
                                    dragOffsetState.value
                                if (finger.y < info.viewportStartOffset + DRAG_EDGE_PX) {
                                    scope.launch {
                                        gridState.scrollBy(-DRAG_SCROLL_STEP_PX)
                                    }
                                } else if (finger.y > info.viewportEndOffset - DRAG_EDGE_PX) {
                                    scope.launch {
                                        gridState.scrollBy(DRAG_SCROLL_STEP_PX)
                                    }
                                }
                                val current = visualOrderState.value ?: itemsRef.value
                                val fromVisual = current.indexOfFirst {
                                    it.id == draggedIdState.value
                                }
                                if (fromVisual >= 0) {
                                    val draggedTile = info.visibleItemsInfo
                                        .find { it.key == draggedIdState.value }
                                    if (draggedTile != null) {
                                        dragLayoutCenterState.value = Offset(
                                            draggedTile.offset.x + draggedTile.size.width / 2f,
                                            draggedTile.offset.y + draggedTile.size.height / 2f,
                                        )
                                    }
                                    val to = dropGridTarget(
                                        layoutInfo = info,
                                        draggedKey = draggedIdState.value,
                                        finger = finger,
                                        fromVisual = fromVisual,
                                        size = current.size,
                                        spanCount = COLLECTION_GRID_SPAN,
                                    )
                                    // Single step toward the target, axis-aware:
                                    // horizontal targets move one slot, vertical
                                    // ones one row. Multi-slot jumps in one event
                                    // displace the tile away from the finger;
                                    // fast drags catch up via rapid steps.
                                    val delta = to - fromVisual
                                    val step = when {
                                        delta == 0 -> fromVisual
                                        delta in -1..1 -> fromVisual + delta
                                        else -> fromVisual +
                                            COLLECTION_GRID_SPAN * Integer.signum(delta)
                                    }.coerceIn(0, current.lastIndex)
                                    if (step != fromVisual) {
                                        val reordered = current.toMutableList()
                                        val moving = reordered.removeAt(fromVisual)
                                        reordered.add(step, moving)
                                        visualOrderState.value = reordered
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                    }
                                }
                            },
                        )
                    },
            )
        }
    }
}

/**
 * Maps the finger center to a position in the visual list, QUE-style directional
 * and stable: the step fires only when the finger crosses [DRAG_STEP_FRACTION]
 * into the adjacent cell *relative to the dragged tile's own live rect*.
 * The dead zone around the slot edges absorbs finger jitter and placement-
 * animation noise, so a held finger can never oscillate between two slots —
 * stepping back requires traveling back across the zone. Geometry comes from
 * measured item rects (no estimated cell math).
 */
private const val DRAG_STEP_FRACTION = 0.4f

private fun dropGridTarget(
    layoutInfo: LazyGridLayoutInfo,
    draggedKey: Any?,
    finger: Offset,
    fromVisual: Int,
    size: Int,
    spanCount: Int,
): Int {
    if (size <= 0) return 0
    val myTile = layoutInfo.visibleItemsInfo.find { it.key == draggedKey }
        ?: return fromVisual
    val w = myTile.size.width.toFloat()
    val h = myTile.size.height.toFloat()
    if (w <= 0f || h <= 0f) return fromVisual
    // Finger in cell units relative to my own rect: [0, 1] = inside.
    val fx = (finger.x - myTile.offset.x) / w
    val fy = (finger.y - myTile.offset.y) / h
    // Overshoot past each edge; the dominant one beyond the threshold wins.
    val overRight = fx - 1f
    val overLeft = -fx
    val overDown = fy - 1f
    val overUp = -fy
    var best = fromVisual
    var bestOver = DRAG_STEP_FRACTION
    if (overRight > bestOver) {
        best = fromVisual + 1
        bestOver = overRight
    }
    if (overLeft > bestOver) {
        best = fromVisual - 1
        bestOver = overLeft
    }
    if (overDown > bestOver) {
        best = fromVisual + spanCount
        bestOver = overDown
    }
    if (overUp > bestOver) {
        best = fromVisual - spanCount
        bestOver = overUp
    }
    return best.coerceIn(0, size - 1)
}

@Preview
@Composable
private fun CollectionGalleryScreenPreview() {
    ForgeryTheme {
        CollectionGalleryScreen(
            uiState = CollectionGalleryUiState.Success(
                collection = com.forgery.app.core.model.GalleryCollection(
                    1,
                    "Trip",
                    0,
                    2,
                    null,
                ),
                items = listOf(
                    HistoryItem(1, "/a.png", null, "{}", "today"),
                    HistoryItem(2, "/b.png", null, "{}", "today"),
                ),
                total = 2,
            ),
            collectionId = 1,
            isUnsorted = false,
            onAction = {},
            onBackClick = {},
            onNavigateToDetail = {},
        )
    }
}
