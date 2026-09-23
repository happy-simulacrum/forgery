package com.forgery.app.core.model

import kotlinx.serialization.Serializable

/** Generation mode: mirrors legacy currentMode (xl/flux/qwen) + inpaint task. */
enum class GenerationMode { SDXL, FLUX, QWEN }

enum class GenerationTask { TXT2IMG, IMG2IMG }

/** Connection mode: legacy LOCAL (LAN IP+ports) vs EXTERNAL (HTTPS/Ngrok). */
data class ConnectionConfig(
    val isRemote: Boolean = false,
    val baseIp: String = "192.168.1.100",
    val portWebUi: Int = 7860,
    val portComfy: Int = 8188,
    val portLlm: Int = 1234,
    val portWake: Int = 5000,
    val extForgeUrl: String = "",
    val extWakeUrl: String = "",
    val isCloudflare: Boolean = false,
    val cfClientId: String = "",
    val cfClientSecret: String = "",
    val llmKey: String = "",
    val llmModel: String = "",
    val isConfigured: Boolean = false,
) {
    fun webUiBaseUrl(): String =
        if (isRemote && extForgeUrl.isNotBlank()) extForgeUrl.trimEnd('/')
        else "http://$baseIp:$portWebUi"

    fun comfyHost(): String =
        if (isRemote) "" else "$baseIp:$portComfy"

    fun llmBaseUrl(): String = "http://$baseIp:$portLlm"

    fun wakeBaseUrl(): String =
        if (isRemote && extWakeUrl.isNotBlank()) extWakeUrl.trimEnd('/')
        else "http://$baseIp:$portWake"
}

data class GenerationParams(
    val mode: GenerationMode = GenerationMode.SDXL,
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: Int = 20,
    val cfgScale: Double = 7.0,
    val distilledCfgScale: Double = 3.5,
    val width: Int = 1024,
    val height: Int = 1024,
    val sampler: String = "Euler",
    val scheduler: String = "Normal",
    val seed: Long = -1L,
    val batchSize: Int = 1,
    val batchCount: Int = 1,
    val modelTitle: String = "",
    /** Selected VAE / Text Encoder modules (Forge Neo `forge_additional_modules`, SDXL only). */
    val additionalModules: List<String> = emptyList(),
    val enableHr: Boolean = false,
    val hrUpscaler: String = "Latent",
    val hrScale: Double = 1.5,
    val hrSteps: Int = 6,
    val hrDenoise: Double = 0.4,
    val hrCfg: Double = 1.0,
)

data class HistoryItem(
    val id: Long = 0,
    val imagePath: String,
    val thumbPath: String?,
    val paramsJson: String,
    val date: String,
)

@Serializable
data class QueueJob(
    val id: String,
    val desc: String,
    val mode: String,
    val modelTitle: String,
    val payloadJson: String,
    /** Frozen VAE / Text Encoder selection (Forge Neo `forge_additional_modules`). */
    val additionalModules: List<String> = emptyList(),
)

@Serializable
data class QueueResult(
    val jobId: String,
    val desc: String,
    val files: List<String>,
    val error: String? = null,
)

/** Per-mode prompt draft shared between GEN/INP and LoRA/Styles/MagicPrompt. */
data class PromptDraft(
    val prompt: String = "",
    val negativePrompt: String = "",
)

data class LoraMeta(
    val weight: Double = 1.0,
    val trigger: String = "",
)

/** Hi-Res Fix subsection per generation mode (legacy defaults). */
data class HrSettings(
    val enable: Boolean = false,
    val upscaler: String = "Latent",
    val scale: Double = 1.5,
    val steps: Int = 6,
    val denoise: Double = 0.4,
    val cfg: Double = 1.0,
)

/** Saved GEN defaults per generation mode, applied on start/mode switch. */
data class GenDefaults(
    val prompt: String = "",
    val negativePrompt: String = "",
    val modelTitle: String = "",
    val sampler: String = "",
    val scheduler: String = "",
    val upscaler: String = "",
)

enum class DefaultField { PROMPT, NEGATIVE, MODEL, SAMPLER, SCHEDULER, UPSCALER }

data class QueueSnapshot(
    val running: Boolean,
    val currentIndex: Int,
    val total: Int,
    val origin: String,
    val stopReason: String?,
    val jobProgress: Float = 0f,
)

@Serializable
data class ForgeModel(val modelName: String, val title: String = "")

@Serializable
data class ForgeSampler(val name: String)

@Serializable
data class LoraItem(val name: String, val path: String, val alias: String = "")

data class StylePreset(
    val name: String,
    val prompt: String,
    val negativePrompt: String,
)

/** UI preferences: mirrors legacy bojro_theme / bojro_show_* / bojro_vis_* flags. */
data class UiPrefs(
    val darkTheme: Boolean = true,
    val showXl: Boolean = true,
    val showFlux: Boolean = true,
    val showQwen: Boolean = true,
    val showComfy: Boolean = false,
)

/** One-shot handoff of restored generation params from Analyze to Generate. */
data class RestoredParams(
    val steps: Int? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val cfgScale: Double? = null,
    val distilledCfgScale: Double? = null,
    val seed: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val modelTitle: String? = null,
    val additionalModules: List<String>? = null,
    val hr: HrSettings? = null,
)
