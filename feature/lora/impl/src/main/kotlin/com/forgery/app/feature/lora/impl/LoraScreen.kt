package com.forgery.app.feature.lora.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.LoraItem
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun LoraRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoraViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LoraScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun LoraScreen(
    uiState: LoraUiState,
    onAction: (LoraAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        LoraUiState.Loading -> LoadingState(modifier)
        is LoraUiState.Error -> ErrorState(uiState.message, modifier)
        is LoraUiState.Success -> LoraContent(state = uiState, onAction = onAction, modifier = modifier)
    }
}

@Composable
private fun LoraContent(
    state: LoraUiState.Success,
    onAction: (LoraAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("LoRA → prompt")
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(LoraAction.QueryChanged(it)) },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onAction(LoraAction.ToggleFavoritesOnly) }) {
                Text(if (state.favoritesOnly) "ALL" else "★ FAVS")
            }
            OutlinedButton(onClick = { onAction(LoraAction.Refresh) }) { Text("REFRESH") }
            if (state.listLoading) LinearProgressIndicator(Modifier.weight(1f))
        }
        state.listError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onAction(LoraAction.DismissNotice) }) { Text("OK") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.visible, key = { it.name }) { item ->
                val fav = item.name in state.favorites
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onAction(LoraAction.ItemClicked(item)) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.alias.ifBlank { item.name }, maxLines = 1)
                            if (item.alias.isNotBlank()) {
                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        IconButton(onClick = {
                            onAction(LoraAction.ToggleFavorite(item.name, !fav))
                        }) { Text(if (fav) "★" else "☆") }
                    }
                }
            }
        }
    }

    if (state.detailLoading) {
        Dialog(onDismissRequest = {}) {
            Card { LinearProgressIndicator(Modifier.padding(16.dp)) }
        }
    }
    state.detail?.let { detail ->
        Dialog(onDismissRequest = { onAction(LoraAction.CloseDetail) }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(detail.item.alias.ifBlank { detail.item.name })
                    if (detail.meta.trigger.isNotBlank()) {
                        Text(
                            "Trigger: ${detail.meta.trigger}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Weight", Modifier.weight(1f))
                        Text("%.2f".format(detail.weight))
                    }
                    Slider(
                        value = detail.weight.toFloat(),
                        onValueChange = { onAction(LoraAction.WeightChanged(it.toDouble())) },
                        valueRange = 0f..2f,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onAction(LoraAction.CloseDetail) }) {
                            Text("CANCEL")
                        }
                        TextButton(onClick = { onAction(LoraAction.Insert) }) {
                            Text("INSERT")
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun LoraScreenPreview() {
    ForgeryTheme {
        LoraScreen(
            uiState = LoraUiState.Success(
                items = listOf(
                    LoraItem("detail.safetensors", "detail.safetensors", "detail"),
                    LoraItem("other.safetensors", "other.safetensors", ""),
                ),
                favorites = setOf("detail.safetensors"),
            ),
            onAction = {},
            onBackClick = {},
        )
    }
}
