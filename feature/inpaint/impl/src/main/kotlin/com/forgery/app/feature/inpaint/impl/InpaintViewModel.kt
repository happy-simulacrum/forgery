package com.forgery.app.feature.inpaint.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.data.buildImg2ImgPayload
import com.forgery.app.core.data.payloadToJsonString
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.GenerationParams
import com.forgery.app.core.model.QueueJob
import com.forgery.app.feature.inpaint.api.InpaintRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class InpaintViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val imageReader: ImageAttachmentReader,
    private val generationRepository: GenerationRepository,
    private val queueRepository: QueueRepository,
    private val modulesSelection: ModulesSelectionRepository,
) : ViewModel() {

    companion object {
        const val MAX_SIDE = 1024
    }

    private val editor = MutableStateFlow(InpaintUiState.Success())
    private val models = MutableStateFlow<List<String>>(emptyList())
    private val modelsLoading = MutableStateFlow(false)
    private val modelsError = MutableStateFlow<String?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val activeStroke = MutableStateFlow<MaskStroke?>(null)

    private val statusAndQueue = combine(
        statusMessage,
        queueRepository.observeSnapshot()
    ) { status, snap ->
        status to (snap?.running == true)
    }

    val uiState: StateFlow<InpaintUiState> = combine(
        editor,
        models,
        modelsLoading,
        modelsError,
        statusAndQueue
    ) { e, m, loading, error, sq ->
        val (status, queueRunning) = sq
        e.copy(
            models = m,
            modelsLoading = loading,
            modelsError = error,
            statusMessage = status,
            queueRunning = queueRunning,
        )
    }.combine(modulesSelection.observeModules(GenerationMode.SDXL)) { state, modules ->
        when (state) {
            InpaintUiState.Loading -> state
            is InpaintUiState.Success -> state.copy(modules = modules)
            is InpaintUiState.Error -> state
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InpaintUiState.Loading,
    )

    init {
        refreshModels()
        // toRoute() needs the Android framework (Bundle) and throws on JVM unit tests;
        // the raw-key fallback reads the same back-stack-entry handle key.
        val id = runCatching { savedStateHandle.toRoute<InpaintRoute>().id }
            .getOrNull() ?: savedStateHandle.get<String>("id")
        id?.let { attachSource(it) }
    }

    fun onAction(action: InpaintAction) {
        val e = editor.value
        when (action) {
            is InpaintAction.PickResult -> attachSource(action.uri)
            InpaintAction.ClearSource -> editor.value = e.copy(source = null, strokes = emptyList())
            is InpaintAction.PromptChanged -> editor.value = e.copy(prompt = action.value)
            is InpaintAction.NegChanged -> editor.value = e.copy(negativePrompt = action.value)
            is InpaintAction.ModelChanged -> editor.value = e.copy(modelTitle = action.value)
            is InpaintAction.StepsChanged -> editor.value = e.copy(steps = action.value)
            is InpaintAction.CfgChanged -> editor.value = e.copy(cfgScale = action.value)
            is InpaintAction.DenoiseChanged -> editor.value = e.copy(denoise = action.value)
            is InpaintAction.MaskBlurChanged -> editor.value = e.copy(maskBlur = action.value)
            is InpaintAction.SamplerChanged -> editor.value = e.copy(sampler = action.value)
            is InpaintAction.SchedulerChanged -> editor.value = e.copy(scheduler = action.value)
            is InpaintAction.BrushChanged -> editor.value = e.copy(brush = action.value)
            is InpaintAction.EraseModeChanged -> editor.value = e.copy(eraseMode = action.value)
            is InpaintAction.StrokeStarted -> {
                activeStroke.value = MaskStroke(
                    points = listOf(action.point),
                    brush = e.brush,
                    erase = e.eraseMode,
                )
            }
            is InpaintAction.StrokePoint -> {
                activeStroke.value = activeStroke.value?.copy(
                    points = activeStroke.value!!.points + action.point,
                )
            }
            InpaintAction.StrokeEnded -> {
                activeStroke.value?.let { finished ->
                    if (finished.points.isNotEmpty()) {
                        editor.value = e.copy(strokes = e.strokes + finished)
                    }
                }
                activeStroke.value = null
            }
            InpaintAction.UndoStroke -> {
                if (e.strokes.isNotEmpty()) editor.value = e.copy(strokes = e.strokes.dropLast(1))
            }
            InpaintAction.ClearMask -> editor.value = e.copy(strokes = emptyList())
            InpaintAction.RefreshModels -> refreshModels()
            InpaintAction.Generate -> enqueue()
            InpaintAction.DismissStatus -> statusMessage.value = null
        }
    }

    /** Committed strokes + the in-progress one, for the canvas overlay. */
    val activeStrokeFlow: StateFlow<MaskStroke?> = activeStroke

    private fun attachSource(uri: String) {
        viewModelScope.launch {
            statusMessage.value = null
            val attached = withContext(Dispatchers.IO) {
                try {
                    imageReader.readDownscaled(uri, MAX_SIDE)
                } catch (_: Exception) {
                    null
                }
            }
            if (attached == null) {
                statusMessage.value = "Could not read image."
            } else {
                editor.value = editor.value.copy(
                    source = AttachedImage(uri, attached.first, attached.second, attached.third),
                    strokes = emptyList(),
                )
            }
        }
    }

    private fun renderMaskBase64(source: AttachedImage, strokes: List<MaskStroke>): String? {
        if (strokes.isEmpty()) return null
        val bmp = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            paint.color = if (s.erase) Color.BLACK else Color.WHITE
            paint.strokeWidth = (s.brush * source.width).coerceAtLeast(2f)
            val path = Path()
            val first = s.points.first()
            path.moveTo(first.x * source.width, first.y * source.height)
            // Single-tap dot: draw a point segment.
            if (s.points.size == 1) path.lineTo(first.x * source.width + 1f, first.y * source.height + 1f)
            s.points.drop(1).forEach { p -> path.lineTo(p.x * source.width, p.y * source.height) }
            canvas.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun refreshModels() {
        viewModelScope.launch {
            modelsLoading.value = true
            modelsError.value = null
            when (val r = generationRepository.fetchSdModels()) {
                is Result.Success -> models.value = r.data
                is Result.Error -> modelsError.value = r.message
                is Result.Loading -> Unit
            }
            when (val s = generationRepository.fetchSamplers()) {
                is Result.Success -> editor.value = editor.value.copy(samplers = s.data)
                is Result.Error -> Unit
                is Result.Loading -> Unit
            }
            modelsLoading.value = false
        }
    }

    private fun enqueue() {
        viewModelScope.launch {
            val e = editor.value
            val source = e.source
            if (source == null) {
                statusMessage.value = "Pick an image first."
                return@launch
            }
            if (e.prompt.isBlank()) {
                statusMessage.value = "Prompt is empty."
                return@launch
            }
            statusMessage.value = "Preparing…"
            try {
                val mask = withContext(Dispatchers.Default) {
                    renderMaskBase64(source, e.strokes)
                }
                val params = GenerationParams(
                    mode = GenerationMode.SDXL,
                    prompt = e.prompt,
                    negativePrompt = e.negativePrompt,
                    steps = e.steps,
                    cfgScale = e.cfgScale,
                    width = source.width,
                    height = source.height,
                    modelTitle = e.modelTitle,
                    sampler = e.sampler,
                    scheduler = e.scheduler,
                    additionalModules = modulesSelection.observeModules(GenerationMode.SDXL).first(),
                )
                val payload = buildImg2ImgPayload(
                    params = params,
                    initImages = listOf(source.base64),
                    maskBase64 = mask,
                    denoisingStrength = e.denoise,
                    maskBlur = e.maskBlur,
                )
                val job = QueueJob(
                    id = UUID.randomUUID().toString(),
                    desc = "INP: ${e.prompt.take(60)}",
                    mode = "inp",
                    modelTitle = e.modelTitle,
                    payloadJson = payloadToJsonString(payload),
                    additionalModules = params.additionalModules,
                )
                val wasRunning = queueRepository.observeSnapshot().first()?.running == true
                queueRepository.enqueueImmediate(listOf(job), origin = "single")
                statusMessage.value = if (wasRunning) {
                    "Added behind current job"
                } else {
                    "Queued inpaint."
                }
            } catch (ex: Exception) {
                statusMessage.value = "Queue failed: ${ex.message}"
            }
        }
    }
}

sealed interface InpaintAction {
    data class PickResult(val uri: String) : InpaintAction
    data object ClearSource : InpaintAction
    data class PromptChanged(val value: String) : InpaintAction
    data class NegChanged(val value: String) : InpaintAction
    data class ModelChanged(val value: String) : InpaintAction
    data class StepsChanged(val value: Int) : InpaintAction
    data class CfgChanged(val value: Double) : InpaintAction
    data class DenoiseChanged(val value: Double) : InpaintAction
    data class MaskBlurChanged(val value: Int) : InpaintAction
    data class SamplerChanged(val value: String) : InpaintAction
    data class SchedulerChanged(val value: String) : InpaintAction
    data class BrushChanged(val value: Float) : InpaintAction
    data class EraseModeChanged(val value: Boolean) : InpaintAction
    data class StrokeStarted(val point: MaskPoint) : InpaintAction
    data class StrokePoint(val point: MaskPoint) : InpaintAction
    data object StrokeEnded : InpaintAction
    data object UndoStroke : InpaintAction
    data object ClearMask : InpaintAction
    data object RefreshModels : InpaintAction
    data object Generate : InpaintAction
    data object DismissStatus : InpaintAction
}
