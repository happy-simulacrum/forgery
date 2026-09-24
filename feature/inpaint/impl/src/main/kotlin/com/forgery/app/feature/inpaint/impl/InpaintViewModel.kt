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
import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.GenerationRepository
import com.forgery.app.core.data.ModulesSelectionRepository
import com.forgery.app.core.data.QueueInputs
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.data.buildTxt2ImgPayload
import com.forgery.app.core.data.payloadToJsonString
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
    private val queueInputs: QueueInputs,
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
    }.combine(modulesSelection.observeModules()) { state, modules ->
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
            is InpaintAction.PromptChanged -> editor.value = e.copy(promptDraft = action.value)
            is InpaintAction.NegChanged -> editor.value = e.copy(negativeDraft = action.value)
            is InpaintAction.ModelChanged -> editor.value = e.copy(modelDraft = action.value)
            is InpaintAction.StepsChanged -> editor.value = e.copy(stepsDraft = action.value)
            is InpaintAction.CfgChanged -> editor.value = e.copy(cfgDraft = action.value)
            is InpaintAction.DenoiseChanged -> editor.value = e.copy(denoiseDraft = action.value)
            is InpaintAction.MaskBlurChanged -> editor.value = e.copy(maskBlurDraft = action.value)
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
            InpaintAction.CommitInputs -> commitInputs()
            InpaintAction.Generate -> {
                commitInputs()
                enqueue()
            }
            InpaintAction.DismissStatus -> statusMessage.value = null
        }
    }

    /** Committed strokes + the in-progress one, for the canvas overlay. */
    val activeStrokeFlow: StateFlow<MaskStroke?> = activeStroke

    /**
     * Synchronously commits raw drafts to domain values. Text commits verbatim;
     * numerics parse (digits/decimal, clamped to the field range) and invalid
     * raw keeps the previous domain value so mid-typing text survives.
     * Runs on focus loss ([InpaintAction.CommitInputs]) and before enqueue.
     */
    private fun commitInputs() {
        val e = editor.value
        editor.value = e.copy(
            prompt = e.promptDraft.text,
            negativePrompt = e.negativeDraft.text,
            modelTitle = e.modelDraft.text,
            steps = parseIntDraft(e.stepsDraft.text, STEPS_RANGE) ?: e.steps,
            cfgScale = parseDecimalDraft(e.cfgDraft.text, CFG_RANGE) ?: e.cfgScale,
            denoise = parseDecimalDraft(e.denoiseDraft.text, DENOISE_RANGE) ?: e.denoise,
            maskBlur = parseIntDraft(e.maskBlurDraft.text, MASK_BLUR_RANGE) ?: e.maskBlur,
        )
    }

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
                val jobId = UUID.randomUUID().toString()
                val mask = withContext(Dispatchers.Default) {
                    renderMaskBase64(source, e.strokes)
                }
                val params = GenerationParams(
                    prompt = e.prompt,
                    negativePrompt = e.negativePrompt,
                    steps = e.steps,
                    cfgScale = e.cfgScale,
                    width = source.width,
                    height = source.height,
                    modelTitle = e.modelTitle,
                    sampler = e.sampler,
                    scheduler = e.scheduler,
                    additionalModules = modulesSelection.observeModules().first(),
                )
                // C-1 file-back: params-only payload (~1KB); source/mask PNG
                // bytes live in filesDir/queue_inputs/<jobId>/, worker expands
                // them to init_images/mask base64 right before POST.
                val base = buildTxt2ImgPayload(params).toMutableMap()
                base["denoising_strength"] = e.denoise
                base["mask_blur"] = e.maskBlur
                val (initPath, maskPath) = withContext(Dispatchers.IO) {
                    queueInputs.saveBase64(jobId, source.base64, mask)
                }
                val job = QueueJob(
                    id = jobId,
                    desc = "INP: ${e.prompt.take(60)}",
                    mode = "inp",
                    modelTitle = e.modelTitle,
                    payloadJson = payloadToJsonString(base),
                    additionalModules = params.additionalModules,
                    initImagePath = initPath,
                    maskPath = maskPath,
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
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class PromptChanged(val value: TextFieldValue) : InpaintAction
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class NegChanged(val value: TextFieldValue) : InpaintAction
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class ModelChanged(val value: TextFieldValue) : InpaintAction
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class StepsChanged(val value: TextFieldValue) : InpaintAction
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class CfgChanged(val value: TextFieldValue) : InpaintAction
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class DenoiseChanged(val value: TextFieldValue) : InpaintAction
    /** Raw keystroke; domain commit happens in [InpaintViewModel.commitInputs]. */
    data class MaskBlurChanged(val value: TextFieldValue) : InpaintAction
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
    /** Focus-loss / Done trigger: synchronously commit raw drafts to domain. */
    data object CommitInputs : InpaintAction
    data object Generate : InpaintAction
    data object DismissStatus : InpaintAction
}

/** Field ranges mirror the InpaintScreen numeric inputs. */
private val STEPS_RANGE = 1..50
private val CFG_RANGE = 0.0..15.0
private val DENOISE_RANGE = 0.0..1.0
private val MASK_BLUR_RANGE = 0..64

/**
 * Commits raw integer text: digits only, empty/unparseable commits nothing
 * (null) so the raw text survives; parsed values clamp to [range].
 */
internal fun parseIntDraft(raw: String, range: IntRange): Int? {
    val digits = raw.filter(Char::isDigit)
    if (digits.isEmpty()) return null
    val parsed = digits.toIntOrNull() ?: return null
    return parsed.coerceIn(range.first, range.last)
}

/**
 * Commits raw decimal text: digits + single dot, empty/unparseable commits
 * nothing (null); parsed values clamp to [range].
 */
internal fun parseDecimalDraft(raw: String, range: ClosedFloatingPointRange<Double>): Double? {
    val clean = raw.filter { it.isDigit() || it == '.' }
    if (clean.count { it == '.' } > 1) return null
    val parsed = clean.toDoubleOrNull() ?: return null
    return parsed.coerceIn(range.start, range.endInclusive)
}
