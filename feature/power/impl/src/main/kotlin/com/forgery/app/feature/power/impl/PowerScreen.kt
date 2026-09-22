package com.forgery.app.feature.power.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.forgery.app.core.data.PowerService
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun PowerRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PowerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PowerScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun PowerScreen(
    uiState: PowerUiState,
    onAction: (PowerAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        PowerUiState.Loading -> LoadingState(modifier)
        is PowerUiState.Error -> ErrorState(uiState.message, modifier)
        is PowerUiState.Success -> PowerContent(state = uiState, onAction = onAction, modifier = modifier)
    }
}

@Composable
private fun PowerContent(
    state: PowerUiState.Success,
    onAction: (PowerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Remote PC power (Bojro Power :5000)")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onAction(PowerAction.Wake) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("WAKE PC") }
            OutlinedButton(
                onClick = { onAction(PowerAction.Kill) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("KILL!") }
        }
        PowerService.entries.forEach { service ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(service.name, Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { onAction(PowerAction.SetService(service, true)) },
                        enabled = !state.busy,
                    ) { Text("ON") }
                    OutlinedButton(
                        onClick = { onAction(PowerAction.SetService(service, false)) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("OFF") }
                }
            }
        }
        state.message?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        Modifier.weight(1f),
                        color = if (state.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    TextButton(onClick = { onAction(PowerAction.Dismiss) }) { Text("OK") }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PowerScreenPreview() {
    ForgeryTheme {
        PowerScreen(
            uiState = PowerUiState.Success(message = "Wake: HTTP 200"),
            onAction = {},
            onBackClick = {},
        )
    }
}
