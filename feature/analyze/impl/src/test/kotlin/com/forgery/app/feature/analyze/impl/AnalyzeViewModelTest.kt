package com.forgery.app.feature.analyze.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.data.AnalyzeHandoffRepository
import com.forgery.app.core.data.HrSettingsRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.RestoredParams
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

private val FULL_RAW = "a cat, detailed\nNegative prompt: blurry, lowres\n" +
    "Steps: 20, Sampler: Euler a, Schedule type: Automatic, CFG scale: 7.0, " +
    "Seed: 123, Size: 512x768, Model: v1-5-pruned, " +
    "Hires upscale: 2.0, Hires steps: 10, Hires upscaler: Latent, Denoising strength: 0.7"

private class FakeImageSource(val bytes: ByteArray?) : AnalyzeImageSource {
    override suspend fun read(uri: String): ByteArray? = bytes
}

private class FakePromptDraftRepository : PromptDraftRepository {
    val set = mutableListOf<Triple<GenerationMode, String, String>>()
    val activeModes = mutableListOf<GenerationMode>()
    private val active = MutableStateFlow(GenerationMode.SDXL)
    private val drafts = mutableMapOf<GenerationMode, MutableStateFlow<PromptDraft>>()
    private fun flowOf(mode: GenerationMode) =
        drafts.getOrPut(mode) { MutableStateFlow(PromptDraft()) }
    override fun observeDraft(mode: GenerationMode): Flow<PromptDraft> = flowOf(mode).asStateFlow()
    override fun observeActiveMode(): Flow<GenerationMode> = active.asStateFlow()
    override suspend fun setPrompt(mode: GenerationMode, prompt: String, negativePrompt: String) {
        set += Triple(mode, prompt, negativePrompt)
        flowOf(mode).value = PromptDraft(prompt, negativePrompt)
    }
    override suspend fun appendPrompt(mode: GenerationMode, text: String, negativeText: String) = Unit
    override suspend fun setActiveMode(mode: GenerationMode) {
        activeModes += mode
        active.value = mode
    }
}

private class FakeHrSettingsRepository : HrSettingsRepository {
    val saved = mutableListOf<Pair<GenerationMode, HrSettings>>()
    override fun observeHr(mode: GenerationMode): Flow<HrSettings> = flowOf(HrSettings())
    override suspend fun saveHr(mode: GenerationMode, hr: HrSettings) {
        saved += mode to hr
    }
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
    ) = AnalyzeViewModel(savedStateHandle, FakeImageSource(bytes), drafts, hr, handoff)

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
    fun `copy to mode sets prompt and activates mode`() = runTest {
        val vm = viewModel(pngWithText("a dog\nNegative prompt: ugly\nSteps: 5"))
        vm.uiState.success()
        vm.onAction(AnalyzeAction.PickResult("content://x"))
        vm.uiState.first { it is AnalyzeUiState.Success && it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.CopyToMode(GenerationMode.FLUX))
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            listOf(Triple(GenerationMode.FLUX, "a dog", "ugly")),
            drafts.set,
        )
        assertEquals(listOf(GenerationMode.FLUX), drafts.activeModes)
    }

    @Test
    fun `copy to mode saves hr and hands off restored params`() = runTest {
        val vm = viewModel(pngWithText(FULL_RAW))
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.CopyToMode(GenerationMode.SDXL))
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            listOf(
                GenerationMode.SDXL to HrSettings(
                    enable = true,
                    upscaler = "Latent",
                    scale = 2.0,
                    steps = 10,
                    denoise = 0.7,
                    cfg = 1.0,
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
                hr = null,
            ),
            handoff.consume(),
        )
        // One-shot: second consume is empty.
        assertNull(handoff.consume())
    }

    @Test
    fun `copy without settings hands off nulls and leaves hr untouched`() = runTest {
        val vm = viewModel(pngWithText("a dog\nNegative prompt: ugly"))
        vm.pickAndWait { it.prompt.isNotBlank() }
        vm.onAction(AnalyzeAction.CopyToMode(GenerationMode.FLUX))
        vm.uiState.first { it is AnalyzeUiState.Success && it.statusMessage != null }
        assertEquals(
            listOf(Triple(GenerationMode.FLUX, "a dog", "ugly")),
            drafts.set,
        )
        assertTrue(hr.saved.isEmpty())
        assertEquals(RestoredParams(), handoff.consume())
    }
}
