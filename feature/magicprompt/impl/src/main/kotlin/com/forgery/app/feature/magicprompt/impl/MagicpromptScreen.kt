package com.forgery.app.feature.magicprompt.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.ForgeryDropdown
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun MagicpromptRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MagicpromptViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MagicpromptScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun MagicpromptScreen(
    uiState: MagicpromptUiState,
    onAction: (MagicpromptAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        MagicpromptUiState.Loading -> LoadingState(modifier)
        is MagicpromptUiState.Error -> ErrorState(uiState.message, modifier)
        is MagicpromptUiState.Success -> MagicpromptContent(
            state = uiState,
            onAction = onAction,
            modifier = modifier,
        )
    }
}

@Composable
private fun MagicpromptContent(
    state: MagicpromptUiState.Success,
    onAction: (MagicpromptAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Magic Prompt → ${state.mode.name}")
        OutlinedTextField(
            value = state.input,
            onValueChange = { onAction(MagicpromptAction.InputChanged(it)) },
            label = { Text("Your idea") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ForgeryDropdown(
                label = "Model",
                options = state.models.ifEmpty { listOf(state.selectedModel).filter { it.isNotBlank() } },
                selected = state.selectedModel,
                onSelect = { onAction(MagicpromptAction.ModelChanged(it)) },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onAction(MagicpromptAction.RefreshModels) }) {
                Text("↻")
            }
        }
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { onAction(MagicpromptAction.ApiKeyChanged(it)) },
            label = { Text("API key (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = { onAction(MagicpromptAction.Generate) },
            enabled = !state.generating,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(if (state.generating) "EXPANDING…" else "GENERATE") }
        if (state.generating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        state.error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onAction(MagicpromptAction.Dismiss) }) { Text("OK") }
            }
        }
        state.notice?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onAction(MagicpromptAction.Dismiss) }) { Text("OK") }
            }
        }
        if (state.output.isNotBlank()) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        state.output,
                        modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(state.output)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("COPY") }
                        Button(
                            onClick = { onAction(MagicpromptAction.UseAsPrompt) },
                            modifier = Modifier.weight(1f),
                        ) { Text("USE AS PROMPT") }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun MagicpromptScreenPreview() {
    ForgeryTheme {
        MagicpromptScreen(
            uiState = MagicpromptUiState.Success(
                input = "a cat",
                output = "a fluffy cat, detailed fur, sunlight",
                models = listOf("model.gguf"),
                selectedModel = "model.gguf",
            ),
            onAction = {},
            onBackClick = {},
        )
    }
}
