package com.forgery.app.feature.queue.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.common.overallProgress
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState

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
    val pendingJobs = state.pendingJobs
    val completedJobs = state.completedJobs
    LazyColumn(
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
            items(pendingJobs, key = { it.id }) { job ->
                JobCard(job = job, state = state)
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
                JobCard(job = job, state = state)
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
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(job.desc, maxLines = 2)
                Text(
                    "${job.mode} · ${job.modelTitle}",
                    style = MaterialTheme.typography.bodySmall,
                )
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
