package com.forgery.app.feature.settings.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.ui.DraftIntField
import com.forgery.app.core.ui.DraftTextField
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun SettingsRoute(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        SettingsUiState.Loading -> LoadingState(modifier)
        is SettingsUiState.Error -> ErrorState(uiState.message, modifier)
        is SettingsUiState.Success -> SettingsContent(
            state = uiState,
            onAction = onAction,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState.Success,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = state.draft
    val prefs = state.draftUi

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "settings_appearance") {
            SectionCard("APPEARANCE") {
                SwitchRow(
                    label = "Dark theme",
                    checked = prefs.darkTheme,
                    onCheckedChange = {
                        onAction(SettingsAction.UiPrefsChanged(prefs.copy(darkTheme = it)))
                    },
                )
            }
        }

        item(key = "settings_connection") {
            SectionCard("CONNECTION MODE") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !config.isRemote,
                        onClick = {
                            onAction(SettingsAction.ConfigChanged(config.copy(isRemote = false)))
                        },
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("LOCAL") },
                    )
                    SegmentedButton(
                        selected = config.isRemote,
                        onClick = {
                            onAction(SettingsAction.ConfigChanged(config.copy(isRemote = true)))
                        },
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("EXTERNAL") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!config.isRemote) {
                    TextRow(
                        label = "PC IP",
                        value = state.texts.baseIp,
                        onValueChange = { onAction(SettingsAction.BaseIpChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                    PortRow(
                        label = "WebUI port",
                        value = state.texts.portWebUi,
                        onValueChange = { onAction(SettingsAction.PortWebUiChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                    PortRow(
                        label = "ComfyUI port",
                        value = state.texts.portComfy,
                        onValueChange = { onAction(SettingsAction.PortComfyChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                    PortRow(
                        label = "LLM port",
                        value = state.texts.portLlm,
                        onValueChange = { onAction(SettingsAction.PortLlmChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                    PortRow(
                        label = "Wake port",
                        value = state.texts.portWake,
                        onValueChange = { onAction(SettingsAction.PortWakeChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                } else {
                    TextRow(
                        label = "Forge URL",
                        value = state.texts.extForgeUrl,
                        onValueChange = { onAction(SettingsAction.ExtForgeUrlChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                    TextRow(
                        label = "Wake URL",
                        value = state.texts.extWakeUrl,
                        onValueChange = { onAction(SettingsAction.ExtWakeUrlChanged(it)) },
                        onCommit = { onAction(SettingsAction.CommitInputs) },
                    )
                    SwitchRow(
                        label = "Cloudflare Access",
                        checked = config.isCloudflare,
                        onCheckedChange = {
                            onAction(SettingsAction.ConfigChanged(config.copy(isCloudflare = it)))
                        },
                    )
                    if (config.isCloudflare) {
                        TextRow(
                            label = "CF client ID",
                            value = state.texts.cfClientId,
                            onValueChange = { onAction(SettingsAction.CfClientIdChanged(it)) },
                            onCommit = { onAction(SettingsAction.CommitInputs) },
                        )
                        TextRow(
                            label = "CF client secret",
                            value = state.texts.cfClientSecret,
                            onValueChange = { onAction(SettingsAction.CfClientSecretChanged(it)) },
                            onCommit = { onAction(SettingsAction.CommitInputs) },
                        )
                    }
                }
            }
        }

        item(key = "settings_tabs") {
            SectionCard("INTERFACE TABS") {
                SwitchRow("SDXL tab", prefs.showXl) {
                    onAction(SettingsAction.UiPrefsChanged(prefs.copy(showXl = it)))
                }
                SwitchRow("Flux tab", prefs.showFlux) {
                    onAction(SettingsAction.UiPrefsChanged(prefs.copy(showFlux = it)))
                }
                SwitchRow("Qwen tab", prefs.showQwen) {
                    onAction(SettingsAction.UiPrefsChanged(prefs.copy(showQwen = it)))
                }
                SwitchRow("ComfyUI tab", prefs.showComfy) {
                    onAction(SettingsAction.UiPrefsChanged(prefs.copy(showComfy = it)))
                }
            }
        }

        item(key = "settings_system") {
            SectionCard("SYSTEM") {
                when (val c = state.check) {
                    CheckState.Idle -> Unit
                    CheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("Checking…")
                    }
                    is CheckState.Ok -> Text(
                        "Connected: ${c.models} model(s) found.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is CheckState.Failed -> {
                        Text(c.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onAction(SettingsAction.DismissCheck) }) {
                            Text("Dismiss")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onAction(SettingsAction.CheckConnection) },
                        modifier = Modifier.weight(1f),
                    ) { Text("CHECK") }
                    OutlinedButton(
                        onClick = { onAction(SettingsAction.Reset) },
                        modifier = Modifier.weight(1f),
                    ) { Text("RESET") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onAction(SettingsAction.Save) },
                    enabled = state.isDirty && !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSaving) "SAVING…" else "SAVE CONFIGURATION")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TextRow(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onCommit: () -> Unit,
) {
    DraftTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).onFocusChanged {
            if (!it.isFocused) onCommit()
        },
    )
}

@Composable
private fun PortRow(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        DraftIntField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(110.dp).onFocusChanged {
                if (!it.isFocused) onCommit()
            },
        )
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    ForgeryTheme {
        SettingsScreen(
            uiState = SettingsUiState.Success(
                draft = ConnectionConfig(baseIp = "192.168.1.107"),
                draftUi = UiPrefs(),
                texts = SettingsTextDrafts(baseIp = TextFieldValue("192.168.1.107")),
                isDirty = true,
                check = CheckState.Ok(3),
            ),
            onAction = {},
            onBackClick = {},
        )
    }
}
