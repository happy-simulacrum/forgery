package com.forgery.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.model.GenDefaults
import com.forgery.app.core.model.HrSettings
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.core.model.PromptDraft
import com.forgery.app.core.model.UiPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// 1:1 port of legacy localStorage bojro_* keys (clean start, no migration).
object PrefsKeys {
    val BASE_IP = stringPreferencesKey("bojro_base_ip")
    val PORT_WEBUI = intPreferencesKey("bojro_port_webui")
    val EXT_FORGE = stringPreferencesKey("bojro_ext_forge")
    val IS_REMOTE = booleanPreferencesKey("bojro_is_remote")
    val IS_CLOUDFLARE = booleanPreferencesKey("bojro_is_cloudflare")
    val CF_ID = stringPreferencesKey("bojro_cf_id")
    val CF_SECRET = stringPreferencesKey("bojro_cf_secret")
    val IS_CONFIGURED = booleanPreferencesKey("bojro_is_configured")
    val THEME_DARK = booleanPreferencesKey("bojro_theme_dark")
    val PROMPT = stringPreferencesKey("bojro_prompt")
    val NEG = stringPreferencesKey("bojro_neg")
    val LORA_FAVS = stringSetPreferencesKey("bojro_lora_favs")
    val HR_ENABLE = booleanPreferencesKey("bojro_hr_enable")
    val HR_UPSCALER = stringPreferencesKey("bojro_hr_upscaler")
    val HR_SCALE = doublePreferencesKey("bojro_hr_scale")
    val HR_STEPS = intPreferencesKey("bojro_hr_steps")
    val HR_DENOISE = doublePreferencesKey("bojro_hr_denoise")
    val HR_CFG = doublePreferencesKey("bojro_hr_cfg")
    val MODULES = stringSetPreferencesKey("bojro_modules")
    val DEF_PROMPT = stringPreferencesKey("bojro_def_prompt")
    val DEF_NEG = stringPreferencesKey("bojro_def_neg")
    val DEF_MODEL = stringPreferencesKey("bojro_def_model")
    val DEF_SAMPLER = stringPreferencesKey("bojro_def_sampler")
    val DEF_SCHED = stringPreferencesKey("bojro_def_sched")
    val DEF_UPSCALER = stringPreferencesKey("bojro_def_upscaler")
    val MODEL_PARAMS = stringPreferencesKey("bojro_model_params")
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
            extForgeUrl = p[PrefsKeys.EXT_FORGE] ?: "",
            isCloudflare = p[PrefsKeys.IS_CLOUDFLARE] ?: false,
            cfClientId = p[PrefsKeys.CF_ID] ?: "",
            cfClientSecret = p[PrefsKeys.CF_SECRET] ?: "",
            isConfigured = p[PrefsKeys.IS_CONFIGURED] ?: false,
        )
    }

    suspend fun saveConnection(config: ConnectionConfig) {
        dataStore.edit { e ->
            e[PrefsKeys.IS_REMOTE] = config.isRemote
            e[PrefsKeys.BASE_IP] = config.baseIp
            e[PrefsKeys.PORT_WEBUI] = config.portWebUi
            e[PrefsKeys.EXT_FORGE] = config.extForgeUrl
            e[PrefsKeys.IS_CLOUDFLARE] = config.isCloudflare
            e[PrefsKeys.CF_ID] = config.cfClientId
            e[PrefsKeys.CF_SECRET] = config.cfClientSecret
            e[PrefsKeys.IS_CONFIGURED] = true
        }
    }

    val uiPrefs: Flow<UiPrefs> = dataStore.data.map { p ->
        UiPrefs(
            darkTheme = p[PrefsKeys.THEME_DARK] ?: true,
        )
    }

    suspend fun saveUiPrefs(prefs: UiPrefs) {
        dataStore.edit { e ->
            e[PrefsKeys.THEME_DARK] = prefs.darkTheme
        }
    }

    suspend fun resetConnection() {
        dataStore.edit { e ->
            e[PrefsKeys.IS_REMOTE] = false
            e[PrefsKeys.BASE_IP] = "192.168.1.100"
            e[PrefsKeys.PORT_WEBUI] = 7860
            e[PrefsKeys.EXT_FORGE] = ""
            e[PrefsKeys.IS_CLOUDFLARE] = false
            e[PrefsKeys.CF_ID] = ""
            e[PrefsKeys.CF_SECRET] = ""
            e[PrefsKeys.IS_CONFIGURED] = false
        }
    }

    // -- prompt draft (shared GEN/INP <-> LoRA/Styles) --

    fun observePromptDraft(): Flow<PromptDraft> =
        dataStore.data.catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }.map { p ->
            PromptDraft(
                prompt = p[PrefsKeys.PROMPT].orEmpty(),
                negativePrompt = p[PrefsKeys.NEG].orEmpty(),
            )
        }

    suspend fun savePromptDraft(draft: PromptDraft) {
        dataStore.edit { e ->
            e[PrefsKeys.PROMPT] = draft.prompt
            e[PrefsKeys.NEG] = draft.negativePrompt
        }
    }

    fun observeLoraFavorites(): Flow<Set<String>> =
        dataStore.data.map { p -> p[PrefsKeys.LORA_FAVS] ?: emptySet() }

    // -- Hi-Res Fix (legacy bojro_hr_* keys) --

    fun observeHr(): Flow<HrSettings> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        val d = HrSettings()
        HrSettings(
            enable = p[PrefsKeys.HR_ENABLE] ?: d.enable,
            upscaler = p[PrefsKeys.HR_UPSCALER] ?: d.upscaler,
            scale = p[PrefsKeys.HR_SCALE] ?: d.scale,
            steps = p[PrefsKeys.HR_STEPS] ?: d.steps,
            denoise = p[PrefsKeys.HR_DENOISE] ?: d.denoise,
            cfg = p[PrefsKeys.HR_CFG] ?: d.cfg,
        )
    }

    suspend fun saveHr(hr: HrSettings) {
        dataStore.edit { e ->
            e[PrefsKeys.HR_ENABLE] = hr.enable
            e[PrefsKeys.HR_UPSCALER] = hr.upscaler
            e[PrefsKeys.HR_SCALE] = hr.scale
            e[PrefsKeys.HR_STEPS] = hr.steps
            e[PrefsKeys.HR_DENOISE] = hr.denoise
            e[PrefsKeys.HR_CFG] = hr.cfg
        }
    }

    // -- GEN defaults (saved user values applied on start) --

    private fun defaultsKey(field: DefaultField) = when (field) {
        DefaultField.PROMPT -> PrefsKeys.DEF_PROMPT
        DefaultField.NEGATIVE -> PrefsKeys.DEF_NEG
    }

    fun observeDefaults(): Flow<GenDefaults> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        GenDefaults(
            prompt = p[PrefsKeys.DEF_PROMPT].orEmpty(),
            negativePrompt = p[PrefsKeys.DEF_NEG].orEmpty(),
        )
    }

    suspend fun saveDefault(field: DefaultField, value: String) {
        dataStore.edit { e ->
            e[defaultsKey(field)] = value
        }
    }

    // -- Last-used params per model (keyed by normalized model title) --

    private val modelParamsJson = Json { ignoreUnknownKeys = true }
    private val modelParamsSerializer = MapSerializer(String.serializer(), ModelLastUsed.serializer())

    private fun decodeModelParams(raw: String?): Map<String, ModelLastUsed> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            modelParamsJson.decodeFromString(modelParamsSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun observeModelParams(): Flow<Map<String, ModelLastUsed>> =
        dataStore.data.map { p -> decodeModelParams(p[PrefsKeys.MODEL_PARAMS]) }

    suspend fun saveModelParams(key: String, params: ModelLastUsed) {
        dataStore.edit { e ->
            val current = decodeModelParams(e[PrefsKeys.MODEL_PARAMS])
            e[PrefsKeys.MODEL_PARAMS] =
                modelParamsJson.encodeToString(modelParamsSerializer, current + (key to params))
        }
    }

    suspend fun setLoraFavorite(name: String, favorite: Boolean) {
        dataStore.edit { e ->
            val current = e[PrefsKeys.LORA_FAVS] ?: emptySet()
            e[PrefsKeys.LORA_FAVS] = if (favorite) current + name else current - name
        }
    }

    // -- VAE / Text Encoder selection (Forge Neo forge_additional_modules) --

    /** Sorted for UI/selection stability (DataStore sets are unordered). */
    fun observeModules(): Flow<List<String>> =
        dataStore.data.catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }.map { p -> (p[PrefsKeys.MODULES] ?: emptySet()).sorted() }

    suspend fun saveModules(modules: List<String>) {
        dataStore.edit { e -> e[PrefsKeys.MODULES] = modules.toSet() }
    }
}
