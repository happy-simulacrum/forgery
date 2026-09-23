package com.forgery.app.feature.modules.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.ui.DraftTextField
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun ModulesRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModulesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ModulesScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun ModulesScreen(
    uiState: ModulesUiState,
    onAction: (ModulesAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        ModulesUiState.Loading -> LoadingState(modifier)
        is ModulesUiState.Success -> ModulesContent(
            state = uiState,
            onAction = onAction,
            onBackClick = onBackClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun ModulesContent(
    state: ModulesUiState.Success,
    onAction: (ModulesAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "VAE / Text Encoder (${state.selected.size}/${state.totalCount})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.selected.isNotEmpty()) {
                    TextButton(onClick = { onAction(ModulesAction.Clear) }) { Text("CLEAR") }
                }
            }
        }

        item {
            DraftTextField(
                value = state.query,
                onValueChange = { onAction(ModulesAction.QueryChanged(it)) },
                label = "Search modules",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.listLoading && state.items.isEmpty()) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        state.listError?.let { error ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onAction(ModulesAction.Refresh) }) { Text("RETRY") }
                    }
                }
            }
        }

        if (state.items.isEmpty() && !state.listLoading && state.listError == null) {
            item { ErrorState("No VAE / Text Encoder modules on the server.", Modifier) }
        }

        items(state.items, key = { it }) { name ->
            val checked = name in state.selected
            Row(
                Modifier.fillMaxWidth().clickable { onAction(ModulesAction.Toggle(name)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onAction(ModulesAction.Toggle(name)) },
                )
                Text(
                    name,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(ModulesAction.Refresh) },
                    modifier = Modifier.weight(1f),
                ) { Text("REFRESH") }
                Button(
                    onClick = onBackClick,
                    modifier = Modifier.weight(2f),
                ) { Text("DONE") }
            }
        }
    }
}
