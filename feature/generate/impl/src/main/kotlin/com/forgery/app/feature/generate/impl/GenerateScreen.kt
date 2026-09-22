package com.forgery.app.feature.generate.impl

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import com.forgery.app.core.ui.ForgeryDropdown
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.ui.DraftIntField
import com.forgery.app.core.ui.DraftTextField
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.ForgeryDropdown
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun GenerateRoute(
    onNavigateToQueue: () -> Unit,
    onNavigateToLora: (GenerationMode) -> Unit,
    onNavigateToStyles: (GenerationMode) -> Unit,
    onNavigateToMagic: (GenerationMode) -> Unit,
    onNavigateToPower: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenerateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GenerateScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onNavigateToQueue = onNavigateToQueue,
        onNavigateToLora = onNavigateToLora,
        onNavigateToStyles = onNavigateToStyles,
        onNavigateToMagic = onNavigateToMagic,
        onNavigateToPower = onNavigateToPower,
        modifier = modifier,
    )
}

@Composable
internal fun GenerateScreen(
    uiState: GenerateUiState,
    onAction: (GenerateAction) -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToLora: (GenerationMode) -> Unit,
    onNavigateToStyles: (GenerationMode) -> Unit,
    onNavigateToMagic: (GenerationMode) -> Unit,
    onNavigateToPower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        GenerateUiState.Loading -> LoadingState(modifier)
        is GenerateUiState.Error -> ErrorState(uiState.message, modifier)
        is GenerateUiState.Success -> GenerateContent(
            state = uiState,
            onAction = onAction,
            onNavigateToQueue = onNavigateToQueue,
            onNavigateToLora = onNavigateToLora,
            onNavigateToStyles = onNavigateToStyles,
            onNavigateToMagic = onNavigateToMagic,
            onNavigateToPower = onNavigateToPower,
            modifier = modifier,
        )
    }
}

@Composable
private fun GenerateContent(
    state: GenerateUiState.Success,
    onAction: (GenerateAction) -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToLora: (GenerationMode) -> Unit,
    onNavigateToStyles: (GenerationMode) -> Unit,
    onNavigateToMagic: (GenerationMode) -> Unit,
    onNavigateToPower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = state.params

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(
                onClick = { onAction(GenerateAction.InitializeEngine) },
                enabled = state.engine != EngineState.Initializing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (val e = state.engine) {
                        EngineState.Uninitialized -> "INITIALIZE ENGINE"
                        EngineState.Initializing -> "INITIALIZING…"
                        is EngineState.Initialized -> "INITIALIZED"
                        is EngineState.Failed -> "FAILED"
                    },
                )
            }
            val failed = state.engine as? EngineState.Failed
            if (failed != null) {
                Text(
                    failed.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            OutlinedButton(
                onClick = { onAction(GenerateAction.RequestUnloadModel) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    "UNLOAD MODEL",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                GenerationMode.entries.forEach { mode ->
                    SegmentedButton(
                        selected = p.mode == mode,
                        onClick = { onAction(GenerateAction.ModeChanged(mode)) },
                        shape = MaterialTheme.shapes.medium,
                        label = { Text(mode.name) },
                    )
                }
            }
        }

        item {
            DraftTextField(
                value = p.prompt,
                onValueChange = { onAction(GenerateAction.PromptChanged(it)) },
                label = "Prompt",
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            DraftTextField(
                value = p.negativePrompt,
                onValueChange = { onAction(GenerateAction.NegChanged(it)) },
                label = "Negative prompt",
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        item {
            ModelPicker(
                label = "Model",
                options = state.models,
                selected = p.modelTitle,
                loading = state.modelsLoading,
                error = state.modelsError,
                onRefresh = { onAction(GenerateAction.RefreshModels) },
                onSelect = { onAction(GenerateAction.ModelChanged(it)) },
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgeryDropdown(
                    label = "Sampler",
                    options = state.samplers.ifEmpty { listOf(p.sampler) },
                    selected = p.sampler,
                    onSelect = { onAction(GenerateAction.SamplerChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
                ForgeryDropdown(
                    label = "Scheduler",
                    options = listOf("Normal", "Simple", "Karras", "Exponential", "SGM Uniform", "Align Your Steps"),
                    selected = p.scheduler,
                    onSelect = { onAction(GenerateAction.SchedulerChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            SliderRow("Steps", p.steps.toString(), p.steps.toFloat(), 1f..50f, 49,
                parseManual = { parseStepsInput(it, 1f..50f)?.toFloat() },
                onChange = { onAction(GenerateAction.StepsChanged(it.toInt())) },
            )
            SliderRow("CFG", "%.1f".format(p.cfgScale), p.cfgScale.toFloat(), 0f..15f, 30,
                keyboardType = KeyboardType.Decimal,
                parseManual = { parseCfgInput(it, 0f..15f)?.toFloat() },
                onChange = { onAction(GenerateAction.CfgChanged((it.toDouble()))) },
            )
        }

        item {
            SizeRow(p, onAction)
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onNavigateToLora(p.mode) },
                    modifier = Modifier.weight(1f),
                ) { Text("ADD LORA") }
                OutlinedButton(
                    onClick = { onNavigateToStyles(p.mode) },
                    modifier = Modifier.weight(1f),
                ) { Text("STYLES") }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onNavigateToMagic(p.mode) },
                    modifier = Modifier.weight(1f),
                ) { Text("MAGIC PROMPT") }
                OutlinedButton(
                    onClick = onNavigateToPower,
                    modifier = Modifier.weight(1f),
                ) { Text("POWER") }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeedField(p.seed, Modifier.weight(1f)) {
                    onAction(GenerateAction.SeedChanged(it))
                }
                Stepper("Batch", p.batchSize, 1..4, Modifier.weight(1f)) {
                    onAction(GenerateAction.BatchSizeChanged(it))
                }
                Stepper("Count", p.batchCount, 1..8, Modifier.weight(1f)) {
                    onAction(GenerateAction.BatchCountChanged(it))
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Hi-Res Fix", Modifier.weight(1f))
                        Switch(
                            checked = state.hr.enable,
                            onCheckedChange = { onAction(GenerateAction.HrToggled) },
                        )
                    }
                    if (state.hr.enable) {
                        Spacer(Modifier.height(8.dp))
                        if (state.upscalers.isEmpty()) {
                            DraftTextField(
                                value = state.hr.upscaler,
                                onValueChange = { onAction(GenerateAction.HrUpscalerChanged(it)) },
                                label = "Upscaler",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            ForgeryDropdown(
                                label = "Upscaler",
                                options = state.upscalers,
                                selected = state.hr.upscaler,
                                onSelect = { onAction(GenerateAction.HrUpscalerChanged(it)) },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DecimalField(
                                label = "Scale",
                                value = state.hr.scale,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrScaleChanged(it)) }
                            IntField(
                                label = "Steps",
                                value = state.hr.steps,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrStepsChanged(it)) }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DecimalField(
                                label = "Denoise",
                                value = state.hr.denoise,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrDenoiseChanged(it)) }
                            DecimalField(
                                label = "HR CFG",
                                value = state.hr.cfg,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrCfgChanged(it)) }
                        }
                    }
                }
            }
        }

        item {
            if (state.queueRunning) {
                val snap = state.queueSnapshot
                if (snap != null) {
                    val progress = snap.jobProgress.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Image ${(progress * 100).toInt()}% — job ${snap.currentIndex + 1}/${snap.total}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = onNavigateToQueue) { Text("VIEW QUEUE") }
                ActionRow(onAction)
            } else {
                ActionRow(onAction)
            }
        }

        state.statusMessage?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(msg, Modifier.weight(1f))
                        TextButton(onClick = { onAction(GenerateAction.DismissStatus) }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
    if (state.confirmUnload) {
        AlertDialog(
            onDismissRequest = { onAction(GenerateAction.DismissUnload) },
            confirmButton = {
                TextButton(onClick = { onAction(GenerateAction.ConfirmUnloadModel) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(GenerateAction.DismissUnload) }) {
                    Text("Cancel")
                }
            },
            text = { Text("Unload current model from VRAM?") },
        )
    }
}

@Composable
private fun ActionRow(
    onAction: (GenerateAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { onAction(GenerateAction.StageQueue) },
            modifier = Modifier.weight(1f),
        ) { Text("QUEUE") }
        Button(
            onClick = { onAction(GenerateAction.Generate) },
            modifier = Modifier.weight(3f),
        ) { Text("GENERATE") }
    }
}

@Composable
private fun ModelPicker(
    label: String,
    options: List<String>,
    selected: String,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column {
        if (options.isEmpty()) {
            OutlinedTextField(
                value = selected,
                onValueChange = onSelect,
                label = { Text("$label (type manually)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ForgeryDropdown(label, options, selected.ifBlank { options.first() }, onSelect)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("RETRY") }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Number,
    parseManual: (String) -> Float? = { raw ->
        raw.trim().toFloatOrNull()?.coerceIn(range.start, range.endInclusive)
    },
) {
    var showInput by remember { mutableStateOf(false) }
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            TextButton(onClick = { showInput = true }) {
                Text(value)
            }
        }
        Slider(value = sliderValue, onValueChange = onChange, valueRange = range, steps = steps)
    }
    if (showInput) {
        var text by remember(value) { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { showInput = false },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = parseManual(text)
                    if (parsed != null) {
                        onChange(parsed)
                        showInput = false
                    }
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInput = false }) {
                    Text("Cancel")
                }
            },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

/** Manual Steps input: integer, clamped to the slider range; null when not a number. */
internal fun parseStepsInput(raw: String, range: ClosedFloatingPointRange<Float>): Int? {
    val v = raw.trim().toIntOrNull() ?: return null
    return v.coerceIn(range.start.toInt(), range.endInclusive.toInt())
}

/** Manual CFG input: decimal, clamped to the slider range; null when not a number. */
internal fun parseCfgInput(raw: String, range: ClosedFloatingPointRange<Float>): Double? {
    val v = raw.trim().toDoubleOrNull() ?: return null
    return v.coerceIn(range.start.toDouble(), range.endInclusive.toDouble())
}

@Composable
private fun SizeRow(p: GenerationParams, onAction: (GenerateAction) -> Unit) {
    val presets = listOf(512, 768, 1024, 1216, 1344)
    Column {
        Text("Size: ${p.width}×${p.height}")
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeryDropdown(
                label = "W",
                options = presets.map(Int::toString),
                selected = p.width.toString(),
                onSelect = { onAction(GenerateAction.SizeChanged(it.toInt(), p.height)) },
                modifier = Modifier.weight(1f),
            )
            ForgeryDropdown(
                label = "H",
                options = presets.map(Int::toString),
                selected = p.height.toString(),
                onSelect = { onAction(GenerateAction.SizeChanged(p.width, it.toInt())) },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { onAction(GenerateAction.SizeChanged(p.height, p.width)) },
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { Text("⇄") }
        }
    }
}

@Composable
private fun SeedField(seed: Long, modifier: Modifier = Modifier, onChange: (Long) -> Unit) {
    OutlinedTextField(
        value = if (seed < 0) "" else seed.toString(),
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(19)
            onChange(if (digits.isEmpty()) -1 else digits.toLong())
        },
        label = { Text("Seed (-1 rnd)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun DecimalField(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    onValueChange: (Double) -> Unit,
) {
    DraftTextField(
        value = if (value.isNaN()) "" else value.toString(),
        onValueChange = { raw ->
            val clean = raw.filter { it.isDigit() || it == '.' }
            if (clean.count { it == '.' } <= 1) {
                clean.toDoubleOrNull()?.let(onValueChange)
            }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    DraftIntField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
    )
}

@Composable
private fun Stepper(    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    Column(modifier) {
        Text("$label: $value")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange((value - 1).coerceIn(range)) },
                modifier = Modifier.width(48.dp),
            ) { Text("−") }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = { onChange((value + 1).coerceIn(range)) },
                modifier = Modifier.width(48.dp),
            ) { Text("+") }
        }
    }
}

@Preview
@Composable
private fun GenerateScreenPreview() {
    ForgeryTheme {
        GenerateScreen(
            uiState = GenerateUiState.Success(
                params = GenerationParams(prompt = "a cat", modelTitle = "a.safetensors"),
                models = listOf("a.safetensors"),
                samplers = listOf("Euler"),
            ),
            onAction = {},
            onNavigateToQueue = {},
            onNavigateToLora = {},
            onNavigateToStyles = {},
            onNavigateToMagic = {},
            onNavigateToPower = {},
        )
    }
}
