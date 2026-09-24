package com.forgery.app.feature.styles.impl

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.StylePreset
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun StylesRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StylesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    StylesScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun StylesScreen(
    uiState: StylesUiState,
    onAction: (StylesAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        StylesUiState.Loading -> LoadingState(modifier)
        is StylesUiState.Error -> ErrorState(uiState.message, modifier)
        is StylesUiState.Success -> StylesContent(state = uiState, onAction = onAction, modifier = modifier)
    }
}

@Composable
private fun StylesContent(
    state: StylesUiState.Success,
    onAction: (StylesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Styles → prompt")
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(StylesAction.QueryChanged(it)) },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onAction(StylesAction.ImportFromServer) }) {
                Text("SYNC SERVER")
            }
            OutlinedButton(onClick = { onAction(StylesAction.OpenEditor) }) { Text("NEW") }
            if (state.importing) LinearProgressIndicator(Modifier.weight(1f))
        }
        state.notice?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onAction(StylesAction.DismissNotice) }) { Text("OK") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.visible, key = { it.name }) { preset ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onAction(StylesAction.ApplyStyle(preset)) }
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(preset.name, Modifier.weight(1f))
                            TextButton(onClick = { onAction(StylesAction.EditStyle(preset)) }) {
                                Text("EDIT")
                            }
                            TextButton(onClick = { onAction(StylesAction.DeleteStyle(preset.name)) }) {
                                Text("DEL")
                            }
                        }
                        if (preset.prompt.isNotBlank()) {
                            Text(
                                preset.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }

    state.editor?.let { preset ->
        StyleEditorDialog(
            preset = preset,
            onSave = { onAction(StylesAction.SaveStyle(it)) },
            onClose = { onAction(StylesAction.CloseEditor) },
        )
    }
}

@Composable
private fun StyleEditorDialog(
    preset: StylePreset,
    onSave: (StylePreset) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember(preset) { mutableStateOf(preset.name) }
    var prompt by remember(preset) { mutableStateOf(preset.prompt) }
    var neg by remember(preset) { mutableStateOf(preset.negativePrompt) }
    Dialog(onDismissRequest = onClose) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(if (preset.name.isBlank()) "New style" else "Edit style")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    readOnly = preset.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = neg,
                    onValueChange = { neg = it },
                    label = { Text("Negative") },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("CANCEL") }
                    TextButton(onClick = { onSave(StylePreset(name.trim(), prompt, neg)) }) {
                        Text("SAVE")
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun StylesScreenPreview() {
    ForgeryTheme {
        StylesScreen(
            uiState = StylesUiState.Success(
                styles = listOf(
                    StylePreset("photo", "photorealistic", "cartoon"),
                    StylePreset("anime", "anime style", ""),
                ),
            ),
            onAction = {},
            onBackClick = {},
        )
    }
}
