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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.ui.ErrorState
import com.forgery.app.core.ui.ForgeryDropdown
import com.forgery.app.core.ui.LoadingState

@Composable
internal fun InpaintRoute(
    onBackClick: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
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
            item {
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

            item {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = { onAction(InpaintAction.PromptChanged(it)) },
                    label = { Text("Prompt") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.negativePrompt,
                    onValueChange = { onAction(InpaintAction.NegChanged(it)) },
                    label = { Text("Negative prompt") },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            item {
                if (state.models.isEmpty()) {
                    OutlinedTextField(
                        value = state.modelTitle,
                        onValueChange = { onAction(InpaintAction.ModelChanged(it)) },
                        label = { Text("Model (type manually)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ForgeryDropdown(
                        label = "Model",
                        options = state.models,
                        selected = state.modelTitle.ifBlank { state.models.first() },
                        onSelect = { onAction(InpaintAction.ModelChanged(it)) },
                    )
                }
                if (state.modelsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }

            item {
                SliderRow("Steps", state.steps.toString(), state.steps.toFloat(), 1f..50f, 49) {
                    onAction(InpaintAction.StepsChanged(it.toInt()))
                }
                SliderRow("CFG", "%.1f".format(state.cfgScale), state.cfgScale.toFloat(), 0f..15f, 30) {
                    onAction(InpaintAction.CfgChanged(it.toDouble()))
                }
                SliderRow("Denoise", "%.2f".format(state.denoise), state.denoise.toFloat(), 0f..1f, 20) {
                    onAction(InpaintAction.DenoiseChanged(it.toDouble()))
                }
                SliderRow("Mask blur", state.maskBlur.toString(), state.maskBlur.toFloat(), 0f..32f, 32) {
                    onAction(InpaintAction.MaskBlurChanged(it.toInt()))
                }
            }

            item {
                Button(
                    onClick = { onAction(InpaintAction.Generate) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("INPAINT") }
            }
        }

        state.statusMessage?.let { msg ->
            item {
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

@Preview
@Composable
private fun InpaintScreenPreview() {
    ForgeryTheme {
        InpaintScreen(
            uiState = InpaintUiState.Success(prompt = "fix"),
            activeStroke = null,
            onAction = {},
            onPickImage = {},
            onBackClick = {},
        )
    }
}
