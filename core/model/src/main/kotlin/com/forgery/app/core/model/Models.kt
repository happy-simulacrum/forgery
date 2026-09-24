package com.forgery.app.core.model

import kotlinx.serialization.Serializable

enum class GenerationTask { TXT2IMG, IMG2IMG }

/** Connection mode: legacy LOCAL (LAN IP+ports) vs EXTERNAL (HTTPS/Ngrok). */
data class ConnectionConfig(
    val isRemote: Boolean = false,
    val baseIp: String = "192.168.1.100",
    val portWebUi: Int = 7860,
    val extForgeUrl: String = "",
    val isCloudflare: Boolean = false,
    val cfClientId: String = "",
    val cfClientSecret: String = "",
    val isConfigured: Boolean = false,
) {
    fun webUiBaseUrl(): String =
        if (isRemote && extForgeUrl.isNotBlank()) extForgeUrl.trimEnd('/')
        else "http://$baseIp:$portWebUi"
}

data class GenerationParams(
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: Int = 20,
    val cfgScale: Double = 7.0,
    val width: Int = 1024,
    val height: Int = 1024,
    val sampler: String = "Euler",
    val scheduler: String = "Normal",
    val seed: Long = -1L,
    val batchSize: Int = 1,
    val batchCount: Int = 1,
    val modelTitle: String = "",
    /** Selected VAE / Text Encoder modules (Forge Neo `forge_additional_modules`). */
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
    /** File-backed inpaint inputs (see QueueInputFiles): payloadJson carries params only. */
    val initImagePath: String? = null,
    val maskPath: String? = null,
)

@Serializable
data class QueueResult(
    val jobId: String,
    val desc: String,
    val files: List<String>,
    val error: String? = null,
)

/** Prompt draft shared between GEN/INP and LoRA/Styles. */
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

/** Last-used generation params per model (keyed by normalized model title). */
@Serializable
data class ModelLastUsed(
    val steps: Int = 20,
    val cfgScale: Double = 7.0,
    val width: Int = 1024,
    val height: Int = 1024,
    val sampler: String = "Euler",
    val scheduler: String = "Normal",
    val batchSize: Int = 1,
    val batchCount: Int = 1,
    val enableHr: Boolean = false,
    val hrUpscaler: String = "Latent",
    val hrScale: Double = 1.5,
    val hrSteps: Int = 6,
    val hrDenoise: Double = 0.4,
    val hrCfg: Double = 1.0,
    val additionalModules: List<String> = emptyList(),
)

/** Saved GEN defaults per generation mode, applied on start/mode switch. */
data class GenDefaults(
    val prompt: String = "",
    val negativePrompt: String = "",
)

enum class DefaultField { PROMPT, NEGATIVE }

data class QueueSnapshot(
    val running: Boolean,
    val executingJobId: String?,
    val total: Int,
    val origin: String,
    val stopReason: String?,
    val jobProgress: Float = 0f,
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
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

/** UI preferences: mirrors legacy bojro_theme flag. */
data class UiPrefs(
    val darkTheme: Boolean = true,
)

/** One-shot handoff of restored generation params from Analyze to Generate. */
data class RestoredParams(
    val steps: Int? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val cfgScale: Double? = null,
    val seed: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val modelTitle: String? = null,
    val additionalModules: List<String>? = null,
    val hr: HrSettings? = null,
)
