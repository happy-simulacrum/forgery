package com.forgery.app.feature.generate.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.ui.DraftIntField
import com.forgery.app.core.ui.DraftTextField
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.ForgeryDropdown
import com.forgery.app.core.ui.LoadingState
import com.forgery.app.core.ui.AspectRatioGrid
import com.forgery.app.core.ui.SeedInputRow
import com.forgery.app.core.ui.SizeInputRow

@Composable
internal fun GenerateRoute(
    onNavigateToQueue: () -> Unit,
    onNavigateToLora: (GenerationMode) -> Unit,
    onNavigateToStyles: (GenerationMode) -> Unit,
    onNavigateToMagic: (GenerationMode) -> Unit,
    onNavigateToPower: () -> Unit,
    onNavigateToModules: (GenerationMode) -> Unit,
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
        onNavigateToModules = onNavigateToModules,
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
    onNavigateToModules: (GenerationMode) -> Unit,
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
            onNavigateToModules = onNavigateToModules,
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
    onNavigateToModules: (GenerationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = state.params
    val inp = state.inputs

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "engine") {
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
        item(key = "mode") {
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

        item(key = "prompt") {
            Column(
                Modifier.onFocusChanged { if (!it.hasFocus) onAction(GenerateAction.CommitInputs) },
            ) {
                LabelHeader(
                    text = "Prompt",
                    onSave = { onAction(GenerateAction.SaveDefault(DefaultField.PROMPT)) },
                    onClear = { onAction(GenerateAction.ClearPrompt) },
                )
                DraftTextField(
                    value = inp.prompt,
                    onValueChange = { onAction(GenerateAction.PromptChanged(it)) },
                    label = null,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabelHeader(
                    text = "Negative prompt",
                    onSave = { onAction(GenerateAction.SaveDefault(DefaultField.NEGATIVE)) },
                    onClear = { onAction(GenerateAction.ClearNegative) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                DraftTextField(
                    value = inp.negativePrompt,
                    onValueChange = { onAction(GenerateAction.NegChanged(it)) },
                    label = null,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "model") {
            ModelPicker(
                label = "Model",
                options = state.models,
                selected = p.modelTitle,
                loading = state.modelsLoading,
                error = state.modelsError,
                onRefresh = { onAction(GenerateAction.RefreshModels) },
                onSelect = { onAction(GenerateAction.ModelChanged(it)) },
                onSave = { onAction(GenerateAction.SaveDefault(DefaultField.MODEL)) },
            )
        }

        // Forge Neo "VAE / Text Encoder": separate menu, SDXL only.
        if (p.mode == GenerationMode.SDXL) {
            item(key = "vae") {
                OutlinedButton(
                    onClick = { onNavigateToModules(p.mode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "VAE / TEXT ENCODER" +
                            (if (p.additionalModules.isEmpty()) "" else " (${p.additionalModules.size})"),
                    )
                }
            }
        }

        item(key = "params") {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .onFocusChanged { if (!it.hasFocus) onAction(GenerateAction.CommitInputs) },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            LabelHeader(
                                text = "Sampler",
                                onSave = { onAction(GenerateAction.SaveDefault(DefaultField.SAMPLER)) },
                            )
                            ForgeryDropdown(
                                label = "",
                                options = state.samplers.ifEmpty { listOf(p.sampler) },
                                selected = p.sampler,
                                onSelect = { onAction(GenerateAction.SamplerChanged(it)) },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            LabelHeader(
                                text = "Scheduler",
                                onSave = { onAction(GenerateAction.SaveDefault(DefaultField.SCHEDULER)) },
                            )
                            ForgeryDropdown(
                                label = "",
                                options = schedulerOptions(state.serverSchedulers, p.mode, p.scheduler),
                                selected = p.scheduler,
                                onSelect = { onAction(GenerateAction.SchedulerChanged(it)) },
                            )
                        }
                    }
                    SliderRow("Steps", p.steps.toString(), p.steps.toFloat(), 1f..50f, 49,
                        parseManual = { parseStepsInput(it, 1f..50f)?.toFloat() },
                        onChange = { onAction(GenerateAction.StepsChanged(it.toInt())) },
                    )
                    SliderRow("CFG", "%.1f".format(p.cfgScale), p.cfgScale.toFloat(), 0f..15f, 30,
                        keyboardType = KeyboardType.Decimal,
                        parseManual = { parseCfgInput(it, 0f..15f)?.toFloat() },
                        onChange = { onAction(GenerateAction.CfgChanged((it.toDouble()))) },
                    )
                    if (p.mode == GenerationMode.FLUX) {
                        DecimalField(
                            label = "Distilled",
                            value = inp.distilled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { onAction(GenerateAction.DistilledChanged(it)) }
                    }
                    SizeInputRow(
                        width = inp.width,
                        height = inp.height,
                        onWidthChange = { onAction(GenerateAction.WidthChanged(it)) },
                        onHeightChange = { onAction(GenerateAction.HeightChanged(it)) },
                        onSwap = { onAction(GenerateAction.SizeSwap) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AspectRatioGrid(
                        mode = p.mode,
                        currentWidth = p.width,
                        currentHeight = p.height,
                        onSelect = { w, h -> onAction(GenerateAction.SizeChanged(w, h)) },
                        onFlip = { onAction(GenerateAction.SizeChanged(p.height, p.width)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DraftIntField(
                            value = inp.batchSize,
                            onValueChange = { onAction(GenerateAction.BatchSizeChanged(it)) },
                            label = "Batch",
                            maxDigits = 1,
                            modifier = Modifier.width(72.dp),
                        )
                        DraftIntField(
                            value = inp.batchCount,
                            onValueChange = { onAction(GenerateAction.BatchCountChanged(it)) },
                            label = "Count",
                            maxDigits = 1,
                            modifier = Modifier.width(72.dp),
                        )
                        SeedInputRow(
                            seed = inp.seed,
                            onSeedChange = { onAction(GenerateAction.SeedChanged(it)) },
                            onRandomize = { onAction(GenerateAction.SeedRandomized) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item(key = "lora") {
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

        item(key = "magic") {
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

        item(key = "hr") {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .onFocusChanged { if (!it.hasFocus) onAction(GenerateAction.CommitInputs) },
                ) {
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
                        LabelHeader(
                            text = "Upscaler",
                            onSave = { onAction(GenerateAction.SaveDefault(DefaultField.UPSCALER)) },
                        )
                        if (state.upscalers.isEmpty()) {
                            DraftTextField(
                                value = inp.hrUpscaler,
                                onValueChange = { onAction(GenerateAction.HrUpscalerChanged(it)) },
                                label = null,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            ForgeryDropdown(
                                label = "",
                                options = state.upscalers,
                                selected = inp.hrUpscaler.text.ifBlank { state.hr.upscaler },
                                onSelect = {
                                    onAction(
                                        GenerateAction.HrUpscalerChanged(
                                            TextFieldValue(it, TextRange(it.length)),
                                        ),
                                    )
                                },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DecimalField(
                                label = "Scale",
                                value = inp.hrScale,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrScaleChanged(it)) }
                            IntField(
                                label = "Steps",
                                value = inp.hrSteps,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrStepsChanged(it)) }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DecimalField(
                                label = "Denoise",
                                value = inp.hrDenoise,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrDenoiseChanged(it)) }
                            DecimalField(
                                label = "HR CFG",
                                value = inp.hrCfg,
                                modifier = Modifier.weight(1f),
                            ) { onAction(GenerateAction.HrCfgChanged(it)) }
                        }
                    }
                }
            }
        }

        item(key = "actions") {
            if (state.queueRunning) {
                val snap = state.queueSnapshot
                if (snap != null) {
                    val progress = snap.jobProgress.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Image ${(progress * 100).toInt()}% — job ${(snap.batchDone + 1).coerceAtMost(snap.batchTotal.coerceAtLeast(1))}/${snap.batchTotal.coerceAtLeast(1)}",
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
            item(key = "status") {
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
    onSave: () -> Unit,
) {
    Column {
        LabelHeader(text = label, onSave = onSave)
        if (options.isEmpty()) {
            OutlinedTextField(
                value = selected,
                onValueChange = onSelect,
                label = { Text("Type manually") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ForgeryDropdown("", options, selected.ifBlank { options.first() }, onSelect)
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

/**
 * Section label with save-as-default (diskette) and optional clear (trash)
 * actions next to the text, at label scale — not inside the input field.
 */
@Composable
private fun LabelHeader(
    text: String,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Save,
                contentDescription = "Save $text as default",
                modifier = Modifier.size(18.dp),
            )
        }
        if (onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Clear $text",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Scheduler dropdown options: full server list when available, curated
 * per-mode fallback offline. The current value is always kept so a saved
 * (or server-hidden) selection never silently disappears.
 */
internal fun schedulerOptions(
    server: List<String>,
    mode: GenerationMode,
    current: String,
): List<String> {
    val base = server.ifEmpty { schedulerOptionsFor(mode) }
    return if (current.isNotBlank() && current !in base) base + current else base
}

/** Scheduler options per mode — mirrors resolver neo.js defaults + index.html selects. */
internal fun schedulerOptionsFor(mode: GenerationMode): List<String> = when (mode) {    GenerationMode.SDXL -> listOf("Karras", "Normal", "Simple", "Exponential")
    GenerationMode.FLUX -> listOf("Simple", "Beta", "Normal", "Karras")
    GenerationMode.QWEN -> listOf("Simple", "Normal")
}

/**
 * Raw decimal passthrough: the VM owns the [TextFieldValue], parsing happens
 * on commit, never per keystroke.
 */
@Composable
private fun DecimalField(
    label: String,
    value: TextFieldValue,
    modifier: Modifier = Modifier,
    onValueChange: (TextFieldValue) -> Unit,
) {
    DraftTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Raw integer passthrough: the VM owns the [TextFieldValue], parsing happens
 * on commit, never per keystroke.
 */
@Composable
private fun IntField(
    label: String,
    value: TextFieldValue,
    modifier: Modifier = Modifier,
    onValueChange: (TextFieldValue) -> Unit,
) {
    DraftIntField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
    )
}

@Preview
@Composable
private fun GenerateScreenPreview() {
    ForgeryTheme {
        GenerateScreen(
            uiState = GenerateUiState.Success(
                params = GenerationParams(prompt = "a cat", modelTitle = "a.safetensors"),
                inputs = GenerateInputs(
                    prompt = TextFieldValue("a cat", TextRange(5)),
                    negativePrompt = TextFieldValue(""),
                    width = TextFieldValue("1024", TextRange(4)),
                    height = TextFieldValue("1024", TextRange(4)),
                ),
                models = listOf("a.safetensors"),
                samplers = listOf("Euler"),
            ),
            onAction = {},
            onNavigateToQueue = {},
            onNavigateToLora = {},
            onNavigateToStyles = {},
            onNavigateToMagic = {},
            onNavigateToPower = {},
            onNavigateToModules = {},
        )
    }
}
