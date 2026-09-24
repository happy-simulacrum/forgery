package com.forgery.app.feature.analyze.impl

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.forgery.app.core.common.FileInfo
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.LoadingState
import kotlin.math.round

@Composable
internal fun AnalyzeRoute(
    onNavigateToGenerate: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalyzeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onAction(AnalyzeAction.PickResult(uri.toString()))
    }

    AnalyzeScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onPickImage = { picker.launch("image/*") },
        onNavigateToGenerate = onNavigateToGenerate,
        modifier = modifier,
    )
}

@Composable
internal fun AnalyzeScreen(
    uiState: AnalyzeUiState,
    onAction: (AnalyzeAction) -> Unit,
    onPickImage: () -> Unit,
    onNavigateToGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        AnalyzeUiState.Loading -> LoadingState(modifier)
        is AnalyzeUiState.Error -> ErrorState(uiState.message, modifier)
        is AnalyzeUiState.Success -> AnalyzeContent(
            state = uiState,
            onAction = onAction,
            onPickImage = onPickImage,
            onNavigateToGenerate = onNavigateToGenerate,
            modifier = modifier,
        )
    }
}

@Composable
private fun AnalyzeContent(
    state: AnalyzeUiState.Success,
    onAction: (AnalyzeAction) -> Unit,
    onPickImage: () -> Unit,
    onNavigateToGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.uri == null) {
            Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Text("PICK IMAGE")
            }
        } else {
            AsyncImage(
                model = state.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onAction(AnalyzeAction.Clear) }) { Text("NEW IMAGE") }
        }

        if (state.fileInfo != null) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        Text("File info", style = MaterialTheme.typography.titleSmall)
                        fileInfoLines(state.fileInfo).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (state.prompt.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        Text("Prompt", style = MaterialTheme.typography.titleSmall)
                        Text(state.prompt, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.negativePrompt.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        Text("Negative prompt", style = MaterialTheme.typography.titleSmall)
                        Text(state.negativePrompt, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.settingsSummary != null) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        Text("Parameters", style = MaterialTheme.typography.titleSmall)
                        Text(state.settingsSummary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.rawText != null) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        Text("Raw metadata", style = MaterialTheme.typography.titleSmall)
                        Text(
                            state.rawText.take(4000),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenerationMode.entries.forEach { mode ->
                    OutlinedButton(
                        onClick = {
                            onAction(AnalyzeAction.CopyToMode(mode))
                            onNavigateToGenerate()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(mode.name) }
                }
            }
        }

        state.statusMessage?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(it)
                    }
                    TextButton(onClick = { onAction(AnalyzeAction.DismissStatus) }) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

internal fun fileInfoLines(fi: FileInfo): List<String> {
    val lines = mutableListOf<String>()
    lines += "Format ${fi.format}"
    lines += "Size ${formatFileSize(fi.sizeBytes)} (${fi.sizeBytes} bytes)"
    val w = fi.width
    val h = fi.height
    if (w != null && h != null) {
        val mp = w.toLong() * h.toLong() / 1_000_000.0
        lines += "Dimensions ${w}\u00D7${h} (${formatOneDecimal(mp)} MP)"
    } else {
        lines += "Dimensions \u2014"
    }
    val dx = fi.dpiX
    val dy = fi.dpiY
    when {
        dx != null && dy != null ->
            lines += if (dx == dy) "DPI ${formatOneDecimal(dx)}"
            else "DPI ${formatOneDecimal(dx)} \u00D7 ${formatOneDecimal(dy)}"
        dx != null -> lines += "DPI ${formatOneDecimal(dx)}"
        dy != null -> lines += "DPI ${formatOneDecimal(dy)}"
        else -> lines += "DPI \u2014"
    }
    val bd = fi.bitDepth
    val ct = fi.colorType
    if (bd != null && ct != null) {
        lines += "Bit depth $bd, Color type $ct"
    } else if (bd != null) {
        lines += "Bit depth $bd"
    } else if (ct != null) {
        lines += "Color type $ct"
    }
    return lines
}

private fun formatOneDecimal(v: Double): String {
    val rounded = round(v * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${formatOneDecimal(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${formatOneDecimal(mb)} MB"
    return "${formatOneDecimal(mb / 1024.0)} GB"
}

@Preview
@Composable
private fun AnalyzeScreenPreview() {
    ForgeryTheme {
        AnalyzeScreen(
            uiState = AnalyzeUiState.Success(
                uri = "content://x",
                prompt = "a cat",
                negativePrompt = "blurry",
                rawText = "a cat\nNegative prompt: blurry\nSteps: 20",
                fileInfo = FileInfo(
                    format = com.forgery.app.core.common.FileFormat.PNG,
                    sizeBytes = 2512345L,
                    width = 1024,
                    height = 1536,
                    dpiX = 300.0,
                    dpiY = 300.0,
                    bitDepth = 8,
                    colorType = 2,
                ),
            ),
            onAction = {},
            onPickImage = {},
            onNavigateToGenerate = {},
        )
    }
}
