package com.forgery.app.feature.inpaint.impl

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.ui.DraftIntField
import com.forgery.app.core.ui.DraftTextField
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.ForgeryDropdown
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun InpaintRoute(
    onBackClick: () -> Unit,
    onNavigateToModules: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InpaintViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeStroke by viewModel.activeStrokeFlow.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onAction(InpaintAction.PickResult(uri.toString()))
    }

    InpaintScreen(
        uiState = uiState,
        activeStroke = activeStroke,
        onAction = viewModel::onAction,
        onPickImage = { picker.launch("image/*") },
        onBackClick = onBackClick,
        onNavigateToModules = onNavigateToModules,
        modifier = modifier,
    )
}

@Composable
internal fun InpaintScreen(
    uiState: InpaintUiState,
    activeStroke: MaskStroke?,
    onAction: (InpaintAction) -> Unit,
    onPickImage: () -> Unit,
    onBackClick: () -> Unit,
    onNavigateToModules: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        InpaintUiState.Loading -> LoadingState(modifier)
        is InpaintUiState.Error -> ErrorState(uiState.message, modifier)
        is InpaintUiState.Success -> InpaintContent(
            state = uiState,
            activeStroke = activeStroke,
            onAction = onAction,
            onPickImage = onPickImage,
            onNavigateToModules = onNavigateToModules,
            modifier = modifier,
        )
    }
}

@Composable
private fun InpaintContent(
    state: InpaintUiState.Success,
    activeStroke: MaskStroke?,
    onAction: (InpaintAction) -> Unit,
    onPickImage: () -> Unit,
    onNavigateToModules: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "inpaint_image") {
            val source = state.source
            if (source == null) {
                Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                    Text("PICK IMAGE")
                }
            } else {
                MaskCanvas(
                    imageUri = source.uri,
                    aspect = source.width.toFloat() / source.height.toFloat().coerceAtLeast(1f),
                    strokes = state.strokes + listOfNotNull(activeStroke),
                    onAction = onAction,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onAction(InpaintAction.UndoStroke) },
                        modifier = Modifier.weight(1f),
                    ) { Text("UNDO") }
                    OutlinedButton(
                        onClick = { onAction(InpaintAction.ClearMask) },
                        modifier = Modifier.weight(1f),
                    ) { Text("CLEAR MASK") }
                    OutlinedButton(
                        onClick = { onAction(InpaintAction.ClearSource) },
                        modifier = Modifier.weight(1f),
                    ) { Text("NEW IMG") }
                }
            }
        }

        if (state.source != null) {
            item(key = "inpaint_brush") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !state.eraseMode,
                        onClick = { onAction(InpaintAction.EraseModeChanged(false)) },
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("DRAW") },
                    )
                    SegmentedButton(
                        selected = state.eraseMode,
                        onClick = { onAction(InpaintAction.EraseModeChanged(true)) },
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("ERASE") },
                    )
                }
                SliderRow(
                    "Brush", "%d".format((state.brush * 100).toInt()),
                    state.brush, 0.01f..0.2f,
                ) { onAction(InpaintAction.BrushChanged(it)) }
            }

            item(key = "inpaint_prompts") {
                DraftTextField(
                    value = state.promptDraft,
                    onValueChange = { onAction(InpaintAction.PromptChanged(it)) },
                    label = "Prompt",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (!it.isFocused) onAction(InpaintAction.CommitInputs)
                    },
                )
                DraftTextField(
                    value = state.negativeDraft,
                    onValueChange = { onAction(InpaintAction.NegChanged(it)) },
                    label = "Negative prompt",
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).onFocusChanged {
                        if (!it.isFocused) onAction(InpaintAction.CommitInputs)
                    },
                )
            }

            item(key = "inpaint_model") {
                if (state.models.isEmpty()) {
                    DraftTextField(
                        value = state.modelDraft,
                        onValueChange = { onAction(InpaintAction.ModelChanged(it)) },
                        label = "Model (type manually)",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (!it.isFocused) onAction(InpaintAction.CommitInputs)
                        },
                    )
                } else {
                    ForgeryDropdown(
                        label = "Model",
                        options = state.models,
                        selected = state.modelTitle.ifBlank { state.models.first() },
                        onSelect = {
                            onAction(InpaintAction.ModelChanged(TextFieldValue(it)))
                            onAction(InpaintAction.CommitInputs)
                        },
                    )
                }
                if (state.modelsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                OutlinedButton(
                    onClick = { onNavigateToModules(state.modelTitle) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        "VAE / TEXT ENCODER" +
                            (if (state.modules.isEmpty()) "" else " (${state.modules.size})"),
                    )
                }
            }

            item(key = "inpaint_samplers") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeryDropdown(
                        label = "Sampler",
                        options = state.samplers.ifEmpty { listOf(state.sampler) },
                        selected = state.sampler,
                        onSelect = { onAction(InpaintAction.SamplerChanged(it)) },
                        modifier = Modifier.weight(1f),
                    )
                    ForgeryDropdown(
                        label = "Scheduler",
                        options = listOf("Normal", "Simple", "Karras", "Exponential"),
                        selected = state.scheduler,
                        onSelect = { onAction(InpaintAction.SchedulerChanged(it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item(key = "inpaint_params") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField(
                        label = "Steps",
                        value = state.stepsDraft,
                        modifier = Modifier.weight(1f),
                        onCommit = { onAction(InpaintAction.CommitInputs) },
                    ) { onAction(InpaintAction.StepsChanged(it)) }
                    DecimalField(
                        label = "CFG",
                        value = state.cfgDraft,
                        modifier = Modifier.weight(1f),
                        onCommit = { onAction(InpaintAction.CommitInputs) },
                    ) { onAction(InpaintAction.CfgChanged(it)) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DecimalField(
                        label = "Denoise",
                        value = state.denoiseDraft,
                        modifier = Modifier.weight(1f),
                        onCommit = { onAction(InpaintAction.CommitInputs) },
                    ) { onAction(InpaintAction.DenoiseChanged(it)) }
                    IntField(
                        label = "Mask blur",
                        value = state.maskBlurDraft,
                        modifier = Modifier.weight(1f),
                        onCommit = { onAction(InpaintAction.CommitInputs) },
                    ) { onAction(InpaintAction.MaskBlurChanged(it)) }
                }
            }

            item(key = "inpaint_generate") {
                Button(
                    onClick = { onAction(InpaintAction.Generate) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("INPAINT") }
            }
        }

        state.statusMessage?.let { msg ->
            item(key = "inpaint_status") {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f))
                        TextButton(onClick = { onAction(InpaintAction.DismissStatus) }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaskCanvas(
    imageUri: String,
    aspect: Float,
    strokes: List<MaskStroke>,
    onAction: (InpaintAction) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.25f, 4f)),
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
        )
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent().changes.firstOrNull() ?: continue
                            if (!down.pressed) continue
                            val size = this.size
                            fun frac(o: Offset) = MaskPoint(
                                (o.x / size.width).coerceIn(0f, 1f),
                                (o.y / size.height).coerceIn(0f, 1f),
                            )
                            onAction(InpaintAction.StrokeStarted(frac(down.position)))
                            down.consume()
                            var dragging = true
                            while (dragging) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    if (change.pressed) {
                                        onAction(InpaintAction.StrokePoint(frac(change.position)))
                                        change.consume()
                                    } else {
                                        dragging = false
                                    }
                                }
                            }
                            onAction(InpaintAction.StrokeEnded)
                        }
                    }
                },
        ) {
            for (s in strokes) {
                if (s.points.isEmpty()) continue
                val path = Path().apply {
                    val f = s.points.first()
                    moveTo(f.x * size.width, f.y * size.height)
                    if (s.points.size == 1) {
                        lineTo(f.x * size.width + 1f, f.y * size.height + 1f)
                    }
                    s.points.drop(1).forEach { p ->
                        lineTo(p.x * size.width, p.y * size.height)
                    }
                }
                drawPath(
                    path = path,
                    color = if (s.erase) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.7f),
                    style = Stroke(
                        width = (s.brush * size.width).coerceAtLeast(4f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
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
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(value)
        }
        Slider(value = sliderValue, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun DecimalField(
    label: String,
    value: TextFieldValue,
    modifier: Modifier = Modifier,
    onCommit: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
) {
    DraftTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged { if (!it.isFocused) onCommit() },
    )
}

@Composable
private fun IntField(
    label: String,
    value: TextFieldValue,
    modifier: Modifier = Modifier,
    onCommit: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
) {
    DraftIntField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier.onFocusChanged { if (!it.isFocused) onCommit() },
    )
}

@Preview
@Composable
private fun InpaintScreenPreview() {
    ForgeryTheme {
        InpaintScreen(
            uiState = InpaintUiState.Success(
                prompt = "fix",
                promptDraft = TextFieldValue("fix"),
            ),
            activeStroke = null,
            onAction = {},
            onPickImage = {},
            onBackClick = {},
            onNavigateToModules = { _ -> },
        )
    }
}
