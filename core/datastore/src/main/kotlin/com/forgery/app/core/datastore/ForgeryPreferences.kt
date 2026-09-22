package com.forgery.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.UiPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// 1:1 port of legacy localStorage bojro_* keys (clean start, no migration).
object PrefsKeys {
    val BASE_IP = stringPreferencesKey("bojro_base_ip")
    val PORT_WEBUI = intPreferencesKey("bojro_port_webui")
    val PORT_COMFY = intPreferencesKey("bojro_port_comfy")
    val PORT_LLM = intPreferencesKey("bojro_port_llm")
    val PORT_WAKE = intPreferencesKey("bojro_port_wake")
    val EXT_FORGE = stringPreferencesKey("bojro_ext_forge")
    val EXT_WAKE = stringPreferencesKey("bojro_ext_wake")
    val IS_REMOTE = booleanPreferencesKey("bojro_is_remote")
    val IS_CLOUDFLARE = booleanPreferencesKey("bojro_is_cloudflare")
    val CF_ID = stringPreferencesKey("bojro_cf_id")
    val CF_SECRET = stringPreferencesKey("bojro_cf_secret")
    val IS_CONFIGURED = booleanPreferencesKey("bojro_is_configured")
    val THEME_DARK = booleanPreferencesKey("bojro_theme_dark")
    val SHOW_XL = booleanPreferencesKey("bojro_show_xl")
    val SHOW_FLUX = booleanPreferencesKey("bojro_show_flux")
    val SHOW_QWEN = booleanPreferencesKey("bojro_show_qwen")
    val SHOW_COMFY = booleanPreferencesKey("bojro_show_comfy")
    val MODEL_XL = stringPreferencesKey("bojro_model_xl")
    val MODEL_FLUX = stringPreferencesKey("bojro_model_flux")
    val MODEL_QWEN = stringPreferencesKey("bojro_model_qwen")
    val MODEL_INP = stringPreferencesKey("bojro_model_inp")
    val PROMPT_XL = stringPreferencesKey("bojro_prompt_xl")
    val PROMPT_FLUX = stringPreferencesKey("bojro_prompt_flux")
    val PROMPT_QWEN = stringPreferencesKey("bojro_prompt_qwen")
    val PROMPT_INP = stringPreferencesKey("bojro_prompt_inp")
    val NEG_XL = stringPreferencesKey("bojro_neg_xl")
    val NEG_FLUX = stringPreferencesKey("bojro_neg_flux")
    val NEG_QWEN = stringPreferencesKey("bojro_neg_qwen")
    val NEG_INP = stringPreferencesKey("bojro_neg_inp")
    val ACTIVE_MODE = stringPreferencesKey("bojro_active_mode")
    val LORA_FAVS = stringSetPreferencesKey("bojro_lora_favs")
    val LLM_KEY = stringPreferencesKey("bojro_llm_key")
    val LLM_MODEL = stringPreferencesKey("bojro_llm_model")
    val HR_ENABLE_XL = booleanPreferencesKey("bojro_xl_hr_enable")
    val HR_ENABLE_FLUX = booleanPreferencesKey("bojro_flux_hr_enable")
    val HR_ENABLE_QWEN = booleanPreferencesKey("bojro_qwen_hr_enable")
    val HR_UPSCALER_XL = stringPreferencesKey("bojro_xl_hr_upscaler")
    val HR_UPSCALER_FLUX = stringPreferencesKey("bojro_flux_hr_upscaler")
    val HR_UPSCALER_QWEN = stringPreferencesKey("bojro_qwen_hr_upscaler")
    val HR_SCALE_XL = doublePreferencesKey("bojro_xl_hr_scale")
    val HR_SCALE_FLUX = doublePreferencesKey("bojro_flux_hr_scale")
    val HR_SCALE_QWEN = doublePreferencesKey("bojro_qwen_hr_scale")
    val HR_STEPS_XL = intPreferencesKey("bojro_xl_hr_steps")
    val HR_STEPS_FLUX = intPreferencesKey("bojro_flux_hr_steps")
    val HR_STEPS_QWEN = intPreferencesKey("bojro_qwen_hr_steps")
    val HR_DENOISE_XL = doublePreferencesKey("bojro_xl_hr_denoise")
    val HR_DENOISE_FLUX = doublePreferencesKey("bojro_flux_hr_denoise")
    val HR_DENOISE_QWEN = doublePreferencesKey("bojro_qwen_hr_denoise")
    val HR_CFG_XL = doublePreferencesKey("bojro_xl_hr_cfg")
    val HR_CFG_FLUX = doublePreferencesKey("bojro_flux_hr_cfg")
    val HR_CFG_QWEN = doublePreferencesKey("bojro_qwen_hr_cfg")
}

@Singleton
class ForgeryPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val connectionConfig: Flow<ConnectionConfig> = dataStore.data.map { p ->
        ConnectionConfig(
            isRemote = p[PrefsKeys.IS_REMOTE] ?: false,
            baseIp = p[PrefsKeys.BASE_IP] ?: "192.168.1.100",
            portWebUi = p[PrefsKeys.PORT_WEBUI] ?: 7860,
            portComfy = p[PrefsKeys.PORT_COMFY] ?: 8188,
            portLlm = p[PrefsKeys.PORT_LLM] ?: 1234,
            portWake = p[PrefsKeys.PORT_WAKE] ?: 5000,
            extForgeUrl = p[PrefsKeys.EXT_FORGE] ?: "",
            extWakeUrl = p[PrefsKeys.EXT_WAKE] ?: "",
            isCloudflare = p[PrefsKeys.IS_CLOUDFLARE] ?: false,
            cfClientId = p[PrefsKeys.CF_ID] ?: "",
            cfClientSecret = p[PrefsKeys.CF_SECRET] ?: "",
            llmKey = p[PrefsKeys.LLM_KEY] ?: "",
            llmModel = p[PrefsKeys.LLM_MODEL] ?: "",
            isConfigured = p[PrefsKeys.IS_CONFIGURED] ?: false,
        )
    }

    suspend fun saveConnection(config: ConnectionConfig) {
        dataStore.edit { e ->
            e[PrefsKeys.IS_REMOTE] = config.isRemote
            e[PrefsKeys.BASE_IP] = config.baseIp
            e[PrefsKeys.PORT_WEBUI] = config.portWebUi
            e[PrefsKeys.PORT_COMFY] = config.portComfy
            e[PrefsKeys.PORT_LLM] = config.portLlm
            e[PrefsKeys.PORT_WAKE] = config.portWake
            e[PrefsKeys.EXT_FORGE] = config.extForgeUrl
            e[PrefsKeys.EXT_WAKE] = config.extWakeUrl
            e[PrefsKeys.IS_CLOUDFLARE] = config.isCloudflare
            e[PrefsKeys.CF_ID] = config.cfClientId
            e[PrefsKeys.CF_SECRET] = config.cfClientSecret
            e[PrefsKeys.LLM_KEY] = config.llmKey
            e[PrefsKeys.LLM_MODEL] = config.llmModel
            e[PrefsKeys.IS_CONFIGURED] = true
        }
    }

    val uiPrefs: Flow<UiPrefs> = dataStore.data.map { p ->
        UiPrefs(
            darkTheme = p[PrefsKeys.THEME_DARK] ?: true,
            showXl = p[PrefsKeys.SHOW_XL] ?: true,
            showFlux = p[PrefsKeys.SHOW_FLUX] ?: true,
            showQwen = p[PrefsKeys.SHOW_QWEN] ?: true,
            showComfy = p[PrefsKeys.SHOW_COMFY] ?: false,
        )
    }

    suspend fun saveUiPrefs(prefs: UiPrefs) {
        dataStore.edit { e ->
            e[PrefsKeys.THEME_DARK] = prefs.darkTheme
            e[PrefsKeys.SHOW_XL] = prefs.showXl
            e[PrefsKeys.SHOW_FLUX] = prefs.showFlux
            e[PrefsKeys.SHOW_QWEN] = prefs.showQwen
            e[PrefsKeys.SHOW_COMFY] = prefs.showComfy
        }
    }

    suspend fun resetConnection() {
        dataStore.edit { e ->
            e[PrefsKeys.IS_REMOTE] = false
            e[PrefsKeys.BASE_IP] = "192.168.1.100"
            e[PrefsKeys.PORT_WEBUI] = 7860
            e[PrefsKeys.PORT_COMFY] = 8188
            e[PrefsKeys.PORT_LLM] = 1234
            e[PrefsKeys.PORT_WAKE] = 5000
            e[PrefsKeys.EXT_FORGE] = ""
            e[PrefsKeys.EXT_WAKE] = ""
            e[PrefsKeys.IS_CLOUDFLARE] = false
            e[PrefsKeys.CF_ID] = ""
            e[PrefsKeys.CF_SECRET] = ""
            e[PrefsKeys.IS_CONFIGURED] = false
        }
    }

    // -- prompt drafts (shared GEN/INP <-> LoRA/Styles/MagicPrompt) --

    private fun promptKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.PROMPT_XL
        GenerationMode.FLUX -> PrefsKeys.PROMPT_FLUX
        GenerationMode.QWEN -> PrefsKeys.PROMPT_QWEN
    }

    private fun negKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.NEG_XL
        GenerationMode.FLUX -> PrefsKeys.NEG_FLUX
        GenerationMode.QWEN -> PrefsKeys.NEG_QWEN
    }

    fun observePromptDraft(mode: GenerationMode): Flow<PromptDraft> =
        dataStore.data.map { p ->
            PromptDraft(
                prompt = p[promptKey(mode)].orEmpty(),
                negativePrompt = p[negKey(mode)].orEmpty(),
            )
        }

    suspend fun savePromptDraft(mode: GenerationMode, draft: PromptDraft) {
        dataStore.edit { e ->
            e[promptKey(mode)] = draft.prompt
            e[negKey(mode)] = draft.negativePrompt
        }
    }

    fun observeActiveMode(): Flow<GenerationMode> = dataStore.data.map { p ->
        runCatching { GenerationMode.valueOf(p[PrefsKeys.ACTIVE_MODE] ?: "SDXL") }
            .getOrDefault(GenerationMode.SDXL)
    }

    suspend fun saveActiveMode(mode: GenerationMode) {
        dataStore.edit { e -> e[PrefsKeys.ACTIVE_MODE] = mode.name }
    }

    fun observeLoraFavorites(): Flow<Set<String>> =
        dataStore.data.map { p -> p[PrefsKeys.LORA_FAVS] ?: emptySet() }

    // -- Hi-Res Fix per mode (legacy bojro_{mode}_hr_* keys) --

    private fun hrEnableKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.HR_ENABLE_XL
        GenerationMode.FLUX -> PrefsKeys.HR_ENABLE_FLUX
        GenerationMode.QWEN -> PrefsKeys.HR_ENABLE_QWEN
    }

    private fun hrUpscalerKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.HR_UPSCALER_XL
        GenerationMode.FLUX -> PrefsKeys.HR_UPSCALER_FLUX
        GenerationMode.QWEN -> PrefsKeys.HR_UPSCALER_QWEN
    }

    private fun hrScaleKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.HR_SCALE_XL
        GenerationMode.FLUX -> PrefsKeys.HR_SCALE_FLUX
        GenerationMode.QWEN -> PrefsKeys.HR_SCALE_QWEN
    }

    private fun hrStepsKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.HR_STEPS_XL
        GenerationMode.FLUX -> PrefsKeys.HR_STEPS_FLUX
        GenerationMode.QWEN -> PrefsKeys.HR_STEPS_QWEN
    }

    private fun hrDenoiseKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.HR_DENOISE_XL
        GenerationMode.FLUX -> PrefsKeys.HR_DENOISE_FLUX
        GenerationMode.QWEN -> PrefsKeys.HR_DENOISE_QWEN
    }

    private fun hrCfgKey(mode: GenerationMode) = when (mode) {
        GenerationMode.SDXL -> PrefsKeys.HR_CFG_XL
        GenerationMode.FLUX -> PrefsKeys.HR_CFG_FLUX
        GenerationMode.QWEN -> PrefsKeys.HR_CFG_QWEN
    }

    fun observeHr(mode: GenerationMode): Flow<HrSettings> = dataStore.data.map { p ->
        val d = HrSettings()
        HrSettings(
            enable = p[hrEnableKey(mode)] ?: d.enable,
            upscaler = p[hrUpscalerKey(mode)] ?: d.upscaler,
            scale = p[hrScaleKey(mode)] ?: d.scale,
            steps = p[hrStepsKey(mode)] ?: d.steps,
            denoise = p[hrDenoiseKey(mode)] ?: d.denoise,
            cfg = p[hrCfgKey(mode)] ?: d.cfg,
        )
    }

    suspend fun saveHr(mode: GenerationMode, hr: HrSettings) {
        dataStore.edit { e ->
            e[hrEnableKey(mode)] = hr.enable
            e[hrUpscalerKey(mode)] = hr.upscaler
            e[hrScaleKey(mode)] = hr.scale
            e[hrStepsKey(mode)] = hr.steps
            e[hrDenoiseKey(mode)] = hr.denoise
            e[hrCfgKey(mode)] = hr.cfg
        }
    }

    suspend fun setLoraFavorite(name: String, favorite: Boolean) {
        dataStore.edit { e ->
            val current = e[PrefsKeys.LORA_FAVS] ?: emptySet()
            e[PrefsKeys.LORA_FAVS] = if (favorite) current + name else current - name
        }
    }
}
