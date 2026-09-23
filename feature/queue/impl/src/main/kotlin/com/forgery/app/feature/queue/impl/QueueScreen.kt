package com.forgery.app.feature.queue.impl

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.common.overallProgress
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState
import kotlinx.coroutines.launch

private const val DRAG_EDGE_PX = 120f
private const val DRAG_SCROLL_STEP_PX = 24f

@Composable
internal fun QueueRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    QueueScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun QueueScreen(
    uiState: QueueUiState,
    onAction: (QueueAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        QueueUiState.Loading -> LoadingState(modifier)
        is QueueUiState.Error -> ErrorState(uiState.message, modifier)
        is QueueUiState.Success -> QueueContent(state = uiState, onAction = onAction, modifier = modifier)
    }
}

@Composable
private fun QueueContent(
    state: QueueUiState.Success,
    onAction: (QueueAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val running = snapshot?.running == true
    val pendingJobs = state.pendingJobs
    val completedJobs = state.completedJobs
    val executingId = state.executingJobId
    val execPendingPos = executingId?.let { id -> pendingJobs.indexOfFirst { it.id == id } } ?: -1

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val draggedIdState = remember { mutableStateOf<String?>(null) }
    val dragOffsetState = remember { mutableFloatStateOf(0f) }
    val visualOrderState = remember { mutableStateOf<List<QueueJob>?>(null) }
    val dragFromState = remember { mutableIntStateOf(-1) }
    val dragBaseCenterState = remember { mutableFloatStateOf(0f) }
    val dragLayoutCenterState = remember { mutableFloatStateOf(0f) }
    val draggedId by draggedIdState
    val dragOffsetY by dragOffsetState
    val dragBaseCenter by dragBaseCenterState
    val dragLayoutCenter by dragLayoutCenterState
    val visualOrder by visualOrderState

    val pendingJobsRef = rememberUpdatedState(pendingJobs)
    val runningRef = rememberUpdatedState(running)
    val execPosRef = rememberUpdatedState(execPendingPos)
    val onActionRef = rememberUpdatedState(onAction)

    LaunchedEffect(pendingJobs) {
        if (draggedIdState.value == null) {
            visualOrderState.value = null
        }
    }

    val displayJobs = visualOrder ?: pendingJobs
    val hintVisible = pendingJobs.size > 1
    // Lazy indices above TO DO: status card (0) + header (1) + optional hint (2).
    val pendingStart = if (hintVisible) 3 else 2
    val pendingStartRef = rememberUpdatedState(pendingStart)

    LazyColumn(
        state = listState,
        userScrollEnabled = draggedId == null,
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("QUEUE", style = MaterialTheme.typography.titleSmall)
                    if (snapshot?.running == true) {
                        Text("Running ${snapshot.currentIndex}/${snapshot.total} (${snapshot.origin})")
                        val imageProgress = snapshot.jobProgress.coerceIn(0f, 1f)
                        val imagePercent = (imageProgress * 100).toInt()
                        val queuePercent = overallProgress(
                            snapshot.currentIndex,
                            snapshot.total,
                            imageProgress,
                        )
                        Text("Image — $imagePercent%")
                        LinearProgressIndicator(
                            progress = { imageProgress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        Text("Queue — $queuePercent%")
                        LinearProgressIndicator(
                            progress = { queuePercent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        Button(onClick = { onAction(QueueAction.Cancel) }) { Text("CANCEL") }
                    } else if (state.jobs.isEmpty()) {
                        Text("Queue is empty. Add jobs from GEN.")
                    } else if (pendingJobs.isEmpty()) {
                        Text("All done — ${completedJobs.size} completed.")
                    } else {
                        Text("Idle — ${pendingJobs.size} to do, ${completedJobs.size} done.")
                        Button(onClick = { onAction(QueueAction.Start) }) {
                            Text("START QUEUE")
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "TO DO (${pendingJobs.size})",
                clearEnabled = pendingJobs.isNotEmpty(),
                onClear = { onAction(QueueAction.RequestClearPending) },
            )
        }
        if (pendingJobs.isEmpty()) {
            item { Text("No pending jobs.") }
        } else {
            if (hintVisible) {
                item {
                    Text(
                        "Drag by the handle on the left to reorder",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(displayJobs, key = { it.id }) { job ->
                val isExecuting = job.id == executingId
                val isDragged = job.id == draggedId
                val canDrag = !isExecuting && displayJobs.size > 1
                val handleGesture = if (canDrag) {
                    Modifier.pointerInput(job.id) {
                        detectDragGestures(
                            onDragStart = {
                                val base = pendingJobsRef.value
                                dragFromState.intValue = base.indexOfFirst { it.id == job.id }
                                dragOffsetState.floatValue = 0f
                                val item = listState.layoutInfo.visibleItemsInfo
                                    .find { it.key == job.id }
                                dragBaseCenterState.floatValue = if (item != null) {
                                    item.offset + item.size / 2f
                                } else {
                                    0f
                                }
                                // Layout slot the dragged card currently occupies;
                                // re-synced every onDrag so translationY stays
                                // anchored to the live slot, not the start one.
                                dragLayoutCenterState.floatValue =
                                    dragBaseCenterState.floatValue
                                if (visualOrderState.value == null) {
                                    visualOrderState.value = base.toList()
                                }
                                draggedIdState.value = job.id
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                val info = listState.layoutInfo
                                val finger = dragBaseCenterState.floatValue +
                                    dragOffsetState.floatValue
                                val current = visualOrderState.value ?: pendingJobsRef.value
                                val fromNow = current.indexOfFirst { it.id == job.id }
                                val fromInit = dragFromState.intValue
                                val draggedItem = info.visibleItemsInfo
                                    .find { it.key == job.id }
                                val resolvedStart =
                                    if (draggedItem != null && fromNow >= 0) {
                                        draggedItem.index - fromNow
                                    } else {
                                        pendingStartRef.value
                                    }
                                val to = if (fromNow >= 0) {
                                    val draggedLazy = resolvedStart + fromNow
                                    dropPendingIndex(
                                        layoutInfo = info,
                                        draggedLazyIndex = draggedLazy,
                                        draggedCenterY = finger,
                                        pendingSize = current.size,
                                        pendingStart = resolvedStart,
                                    )
                                } else {
                                    fromInit
                                }
                                val isRunning = runningRef.value
                                val execPos = execPosRef.value
                                if (fromInit >= 0 && to != fromInit &&
                                    !(isRunning && to <= execPos)
                                ) {
                                    onActionRef.value(QueueAction.MoveJob(job.id, to))
                                }
                                visualOrderState.value = null
                                draggedIdState.value = null
                                dragOffsetState.floatValue = 0f
                                dragFromState.intValue = -1
                            },
                            onDragCancel = {
                                visualOrderState.value = null
                                draggedIdState.value = null
                                dragOffsetState.floatValue = 0f
                                dragFromState.intValue = -1
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetState.floatValue += dragAmount.y
                                val info = listState.layoutInfo
                                val finger = dragBaseCenterState.floatValue +
                                    dragOffsetState.floatValue
                                if (finger < info.viewportStartOffset + DRAG_EDGE_PX) {
                                    scope.launch {
                                        listState.scrollBy(-DRAG_SCROLL_STEP_PX)
                                    }
                                } else if (finger > info.viewportEndOffset - DRAG_EDGE_PX) {
                                    scope.launch {
                                        listState.scrollBy(DRAG_SCROLL_STEP_PX)
                                    }
                                }
                                val current = visualOrderState.value ?: pendingJobsRef.value
                                val fromVisual = current.indexOfFirst { it.id == job.id }
                                if (fromVisual >= 0) {
                                    val draggedItem = info.visibleItemsInfo
                                        .find { it.key == job.id }
                                    if (draggedItem != null) {
                                        dragLayoutCenterState.floatValue =
                                            draggedItem.offset + draggedItem.size / 2f
                                    }
                                    val resolvedStart = if (draggedItem != null) {
                                        draggedItem.index - fromVisual
                                    } else {
                                        pendingStartRef.value
                                    }
                                    val draggedLazy = resolvedStart + fromVisual
                                    val to = dropPendingIndex(
                                        layoutInfo = info,
                                        draggedLazyIndex = draggedLazy,
                                        draggedCenterY = finger,
                                        pendingSize = current.size,
                                        pendingStart = resolvedStart,
                                    )
                                    val isRunning = runningRef.value
                                    val execPos = execPosRef.value
                                    // Single step toward the target: multi-slot jumps
                                    // in one event displace the tile away from the
                                    // finger; fast drags catch up via rapid steps.
                                    val step = if (to > fromVisual) {
                                        fromVisual + 1
                                    } else if (to < fromVisual) {
                                        fromVisual - 1
                                    } else {
                                        fromVisual
                                    }
                                    if (step != fromVisual && !(isRunning && step <= execPos)) {
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
                    }
                } else {
                    Modifier
                }
                JobCard(
                    job = job,
                    state = state,
                    isExecuting = isExecuting,
                    canDelete = !isExecuting,
                    onDelete = { onAction(QueueAction.DeleteJob(job.id)) },
                    dragHandleVisible = canDrag,
                    dragHandleModifier = handleGesture,
                    dragged = isDragged,
                    modifier = Modifier
                        .then(
                            // Dragged card is positioned manually via
                            // translationY: placement animation would fight it.
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
                                translationY = (dragBaseCenter + dragOffsetY) -
                                    dragLayoutCenter
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        },
                )
            }
        }

        item {
            SectionHeader(
                title = "DONE (${completedJobs.size})",
                clearEnabled = completedJobs.isNotEmpty(),
                onClear = { onAction(QueueAction.ConfirmClearCompleted) },
            )
        }
        if (completedJobs.isEmpty()) {
            item { Text("No completed jobs.") }
        } else {
            items(completedJobs, key = { it.id }) { job ->
                JobCard(
                    job = job,
                    state = state,
                    canDelete = true,
                    onDelete = { onAction(QueueAction.DeleteJob(job.id)) },
                    modifier = Modifier.animateItem(
                        placementSpec = spring(stiffness = Spring.StiffnessHigh),
                    ),
                )
            }
        }
    }

    val dialog = state.pendingDialog
    if (dialog != null) {
        val message = when (dialog) {
            is PendingClearDialogState.Idle ->
                "Clear pending queue? ${dialog.count} job(s) will be removed."
            is PendingClearDialogState.Running ->
                "Queue is running. Clear ${dialog.upcoming} upcoming job(s)? Currently executing job will be kept."
        }
        AlertDialog(
            onDismissRequest = { onAction(QueueAction.DismissClearDialog) },
            confirmButton = {
                TextButton(onClick = { onAction(QueueAction.ConfirmClearPending) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(QueueAction.DismissClearDialog) }) {
                    Text("Cancel")
                }
            },
            text = { Text(message) },
        )
    }
}

/**
 * Maps the dragged card's viewport-relative center to a pending-list position:
 * the first visible pending card whose middle is below the center wins,
 * corrected for the dragged card's removal shift. [pendingStart] is the lazy
 * index of the first pending card (dynamic: status + header + optional hint).
 * Only visible pending cards vote; off-screen ends keep the dragged card in
 * place until auto-scroll brings them into view (unless the whole list fits).
 */
private fun dropPendingIndex(
    layoutInfo: LazyListLayoutInfo,
    draggedLazyIndex: Int,
    draggedCenterY: Float,
    pendingSize: Int,
    pendingStart: Int,
): Int {
    if (pendingSize <= 0) return 0
    val end = pendingStart + pendingSize
    val others = layoutInfo.visibleItemsInfo
        .filter { it.index in pendingStart until end && it.index != draggedLazyIndex }
        .sortedBy { it.index }
    val target = others.firstOrNull { draggedCenterY < it.offset + it.size / 2 }?.index
    if (target == null) {
        val visiblePending = layoutInfo.visibleItemsInfo.count { it.index in pendingStart until end }
        return if (visiblePending >= pendingSize) {
            pendingSize - 1
        } else {
            (draggedLazyIndex - pendingStart).coerceIn(0, pendingSize - 1)
        }
    }
    val targetPos = target - pendingStart
    val fromPos = (draggedLazyIndex - pendingStart).coerceIn(0, pendingSize - 1)
    return if (fromPos < targetPos) targetPos - 1 else targetPos
}

@Composable
private fun SectionHeader(
    title: String,
    clearEnabled: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = onClear, enabled = clearEnabled) {
            Text("CLEAR")
        }
    }
}

@Composable
private fun JobCard(
    job: QueueJob,
    state: QueueUiState.Success,
    modifier: Modifier = Modifier,
    isExecuting: Boolean = false,
    canDelete: Boolean = true,
    onDelete: () -> Unit = {},
    dragHandleVisible: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    dragged: Boolean = false,
) {
    Card(
        modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (dragged) 8.dp else 1.dp,
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragHandleVisible) {
                Box(
                    Modifier.size(48.dp).then(dragHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(job.desc, maxLines = 2)
                Text(
                    "${job.mode} · ${job.modelTitle}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (isExecuting) {
                    Text("running…", style = MaterialTheme.typography.bodySmall)
                } else {
                    when (val s = state.statusOf(job)) {
                        JobStatus.Idle -> Text("idle", style = MaterialTheme.typography.bodySmall)
                        JobStatus.Pending -> Text("pending…", style = MaterialTheme.typography.bodySmall)
                        is JobStatus.Done -> Text(
                            "${s.images} image(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is JobStatus.Failed -> Text(
                            s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete job")
            }
        }
    }
}

@Preview
@Composable
private fun QueueScreenPreview() {
    ForgeryTheme {
        QueueScreen(
            uiState = QueueUiState.Success(
                snapshot = QueueSnapshot(running = true, currentIndex = 1, total = 3, origin = "queue", stopReason = null),
                jobs = listOf(
                    QueueJob("1", "a cat", "txt", "m.safetensors", "{}"),
                    QueueJob("2", "a dog", "txt", "m.safetensors", "{}"),
                ),
                results = listOf(QueueResult("1", "a cat", listOf("/tmp/a.png"), null)),
            ),
            onAction = {},
            onBackClick = {},
        )
    }
}
