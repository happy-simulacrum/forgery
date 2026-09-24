package com.forgery.app.feature.analyze.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.common.FileFormat
import com.forgery.app.core.data.AnalyzeHandoffRepository
import com.forgery.app.core.data.HrSettingsRepository
import com.forgery.app.core.data.ModelParamsRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.RestoredParams
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

private fun chunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    dos.writeInt(data.size)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    dos.write(typeBytes)
    dos.write(data)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    dos.writeInt(crc.value.toInt())
    return out.toByteArray()
}

private fun pngWithText(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
    val ihdr = ByteArrayOutputStream()
    DataOutputStream(ihdr).apply {
        writeInt(1)
        writeInt(1)
        writeByte(8)
        writeByte(2)
        writeByte(0)
        writeByte(0)
        writeByte(0)
    }
    out.write(chunk("IHDR", ihdr.toByteArray()))
    val payload = "parameters".toByteArray(Charsets.ISO_8859_1) +
        byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
    out.write(chunk("tEXt", payload))
    out.write(chunk("IEND", ByteArray(0)))
    return out.toByteArray()
}

private fun pngWithoutText(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
    val ihdr = ByteArrayOutputStream()
    DataOutputStream(ihdr).apply {
        writeInt(1)
        writeInt(1)
        writeByte(8)
        writeByte(2)
        writeByte(0)
        writeByte(0)
        writeByte(0)
    }
    out.write(chunk("IHDR", ihdr.toByteArray()))
    out.write(chunk("IEND", ByteArray(0)))
    return out.toByteArray()
}

private val FULL_RAW = "a cat, detailed\nNegative prompt: blurry, lowres\n" +
    "Steps: 20, Sampler: Euler a, Schedule type: Automatic, CFG scale: 7.0, " +
    "Seed: 123, Size: 512x768, Model: v1-5-pruned, VAE: vae-ft-mse-840000-ema-pruned, " +
    "Hires upscale: 2.0, Hires steps: 10, Hires upscaler: Latent, Denoising strength: 0.7"

private class FakeImageSource(val bytes: ByteArray?) : AnalyzeImageSource {
    override suspend fun read(uri: String): ByteArray? = bytes
}

private class FakePromptDraftRepository : PromptDraftRepository {
    val set = mutableListOf<Pair<String, String>>()
    private val draft = MutableStateFlow(PromptDraft())
    override fun observeDraft(): Flow<PromptDraft> = draft.asStateFlow()
    override suspend fun setPrompt(prompt: String, negativePrompt: String) {
        set += prompt to negativePrompt
        draft.value = PromptDraft(prompt, negativePrompt)
    }
    override suspend fun appendPrompt(text: String, negativeText: String) = Unit
}

private class FakeHrSettingsRepository : HrSettingsRepository {
    val saved = mutableListOf<HrSettings>()
    override fun observeHr(): Flow<HrSettings> = flowOf(HrSettings())
    override suspend fun saveHr(hr: HrSettings) {
        saved += hr
    }
}

private class FakeModelParamsRepository : ModelParamsRepository {
    private val entries = MutableStateFlow(mapOf<String, ModelLastUsed>())

    override fun observeForModel(modelTitle: String): Flow<ModelLastUsed?> =
        entries.map { it[modelTitle] }

    override suspend fun saveForModel(modelTitle: String, params: ModelLastUsed) {
        entries.value = entries.value + (modelTitle to params)
    }

    fun seed(title: String, params: ModelLastUsed) {
        entries.value = entries.value + (title to params)
    }

    fun current(title: String) = entries.value[title]

    fun isEmpty() = entries.value.isEmpty()
}

class AnalyzeViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val drafts = FakePromptDraftRepository()
    private val hr = FakeHrSettingsRepository()

    // In-memory, no Android deps — the real implementation doubles as the fake.
    private val handoff = AnalyzeHandoffRepository()

    private fun viewModel(
        bytes: ByteArray?,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        modelParams: FakeModelParamsRepository = FakeModelParamsRepository(),
    ) = AnalyzeViewModel(savedStateHandle, FakeImageSource(bytes), drafts, hr, handoff, modelParams)

    private suspend fun StateFlow<AnalyzeUiState>.success(): AnalyzeUiState.Success =
        first { it is AnalyzeUiState.Success } as AnalyzeUiState.Success

    private suspend fun AnalyzeViewModel.pickAndWait(
        uri: String = "content://x",
        until: (AnalyzeUiState.Success) -> Boolean,
    ): AnalyzeUiState.Success {
        uiState.success()
        onAction(AnalyzeAction.PickResult(uri))
        return uiState.first { it is AnalyzeUiState.Success && until(it) } as AnalyzeUiState.Success
    }

    @Test
    fun `analyzes png metadata into prompt and neg`() = runTest {
        val state = viewModel(pngWithText("a cat, detailed\nNegative prompt: blurry\nSteps: 20"))
            .pickAndWait { it.prompt.isNotBlank() }
        assertEquals("a cat, detailed", state.prompt)
        assertEquals("blurry", state.negativePrompt)
    }

    @Test
    fun `analyze parses settings into summary`() = runTest {
        val state = viewModel(pngWithText(FULL_RAW)).pickAndWait { it.prompt.isNotBlank() }
        assertEquals(
            "Steps 20 \u00B7 Euler a \u00B7 Automatic \u00B7 CFG 7 \u00B7 Seed 123 \u00B7 " +
                "512\u00D7768 \u00B7 v1-5-pruned \u00B7 HR on",
            state.settingsSummary,
        )
    }

    @Test
    fun `analyze without settings tail leaves summary null`() = runTest {
        val state = viewModel(pngWithText("a dog\nNegative prompt: ugly"))
            .pickAndWait { it.prompt.isNotBlank() }
        assertNull(state.settingsSummary)
    }

    @Test
    fun `imagePath route arg auto-analyzes without picker`() = runTest {
        val handle = SavedStateHandle(mapOf("imagePath" to "file:///tmp/history.png"))
        val vm = viewModel(
            pngWithText("a cat, detailed\nNegative prompt: blurry\nSteps: 20"),
            handle,
        )
        vm.uiState.success()
        val state = vm.uiState.first {
            it is AnalyzeUiState.Success && it.prompt.isNotBlank()
        } as AnalyzeUiState.Success
        assertEquals("file:///tmp/history.png", state.uri)
        assertEquals("a cat, detailed", state.prompt)
        assertEquals("blurry", state.negativePrompt)
    }

    @Test
    fun `unreadable image reports status`() = runTest {
        val vm = viewModel(null)
        vm.uiState.success()
        vm.onAction(AnalyzeAction.PickResult("content://x"))
        val state = vm.uiState.first {
            it is AnalyzeUiState.Success && it.statusMessage != null
        } as AnalyzeUiState.Success
        assertEquals("Could not read image.", state.statusMessage)
    }

    @Test
    fun `use again sets prompt`() = runTest {
        val vm = viewModel(pngWithText("a dog\nNegative prompt: ugly\nSteps: 5"))
        vm.uiState.success()
        vm.onAction(AnalyzeAction.PickResult("content://x"))
        vm.uiState.first { it is AnalyzeUiState.Success && it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            listOf("a dog" to "ugly"),
            drafts.set,
        )
    }

    @Test
    fun `use again saves hr and hands off restored params`() = runTest {
        val vm = viewModel(pngWithText(FULL_RAW))
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            listOf(
                HrSettings(
                    enable = true,
                    upscaler = "Latent",
                    scale = 2.0,
                    steps = 10,
                    denoise = 0.7,
                    cfg = 7.0,
                ),
            ),
            hr.saved,
        )
        assertEquals(
            RestoredParams(
                steps = 20,
                sampler = "Euler a",
                scheduler = "Automatic",
                cfgScale = 7.0,
                seed = 123L,
                width = 512,
                height = 768,
                modelTitle = "v1-5-pruned",
                additionalModules = listOf("vae-ft-mse-840000-ema-pruned"),
                hr = null,
            ),
            handoff.consume(),
        )
        // One-shot: second consume is empty.
        assertNull(handoff.consume())
    }

    @Test
    fun `use again saves hr chunk into handing-off model record`() = runTest {
        val modelParams = FakeModelParamsRepository()
        val vm = viewModel(pngWithText(FULL_RAW), modelParams = modelParams)
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            ModelLastUsed(
                enableHr = true,
                hrUpscaler = "Latent",
                hrScale = 2.0,
                hrSteps = 10,
                hrDenoise = 0.7,
                hrCfg = 7.0,
            ),
            modelParams.current("v1-5-pruned"),
        )
    }

    @Test
    fun `use again merges hr into existing model entry`() = runTest {
        val modelParams = FakeModelParamsRepository()
        modelParams.seed(
            "v1-5-pruned",
            ModelLastUsed(steps = 30, additionalModules = listOf("keep.safetensors")),
        )
        val vm = viewModel(pngWithText(FULL_RAW), modelParams = modelParams)
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        val entry = modelParams.current("v1-5-pruned")
        assertEquals(30, entry?.steps)
        assertEquals(listOf("keep.safetensors"), entry?.additionalModules)
        assertEquals(true, entry?.enableHr)
        assertEquals("Latent", entry?.hrUpscaler)
        assertEquals(2.0, entry?.hrScale)
        assertEquals(10, entry?.hrSteps)
        assertEquals(0.7, entry?.hrDenoise)
        assertEquals(7.0, entry?.hrCfg)
    }

    @Test
    fun `use again without model title leaves model records untouched`() = runTest {
        val modelParams = FakeModelParamsRepository()
        val raw = "a dog\nNegative prompt: ugly\nSteps: 5, Sampler: Euler a, CFG scale: 7.0"
        val vm = viewModel(pngWithText(raw), modelParams = modelParams)
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertTrue(modelParams.isEmpty())
    }

    @Test
    fun `use again with automatic vae hands off null modules`() = runTest {
        val raw = "a cat\nNegative prompt: blurry\nSteps: 10, VAE: Automatic"
        val vm = viewModel(pngWithText(raw))
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertNull(handoff.consume()?.additionalModules)
    }

    @Test
    fun `use again without settings hands off nulls and leaves hr untouched`() = runTest {
        val modelParams = FakeModelParamsRepository()
        val vm = viewModel(pngWithText("a dog\nNegative prompt: ugly"), modelParams = modelParams)
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.UseAgain)
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            listOf("a dog" to "ugly"),
            drafts.set,
        )
        assertTrue(hr.saved.isEmpty())
        assertTrue(modelParams.isEmpty())
        assertEquals(RestoredParams(), handoff.consume())
    }

    @Test
    fun `fileInfo present on success`() = runTest {
        val bytes = pngWithText("a cat, detailed\nNegative prompt: blurry\nSteps: 20")
        val state = viewModel(bytes).pickAndWait { it.prompt.isNotBlank() }
        val fi = state.fileInfo
        assertNotNull(fi)
        assertEquals(FileFormat.PNG, fi!!.format)
        assertEquals(bytes.size.toLong(), fi.sizeBytes)
        assertEquals(1, fi.width)
        assertEquals(1, fi.height)
        assertEquals(8, fi.bitDepth)
        assertEquals(2, fi.colorType)
    }

    @Test
    fun `fileInfo present without generative metadata`() = runTest {
        val vm = viewModel(pngWithoutText())
        vm.uiState.success()
        vm.onAction(AnalyzeAction.PickResult("content://x"))
        val state = vm.uiState.first {
            it is AnalyzeUiState.Success && it.statusMessage != null
        } as AnalyzeUiState.Success
        assertEquals("No generation metadata found.", state.statusMessage)
        val fi = state.fileInfo
        assertNotNull(fi)
        assertEquals(FileFormat.PNG, fi!!.format)
        assertEquals(1, fi.width)
        assertEquals(1, fi.height)
    }

    @Test
    fun `clear resets fileInfo`() = runTest {
        val vm = viewModel(pngWithText("a cat\nNegative prompt: blurry\nSteps: 20"))
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.Clear)
        val cleared = vm.uiState.first {
            it is AnalyzeUiState.Success && it.uri == null
        } as AnalyzeUiState.Success
        assertNull(cleared.fileInfo)
        assertNull(cleared.uri)
        assertEquals("", cleared.prompt)
    }
}
