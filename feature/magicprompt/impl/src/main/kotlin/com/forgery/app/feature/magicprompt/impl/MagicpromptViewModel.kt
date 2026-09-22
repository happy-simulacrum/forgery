package com.forgery.app.feature.magicprompt.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.data.MagicPromptRepository
import com.forgery.app.core.data.PromptDraftRepository
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.feature.magicprompt.api.MagicpromptRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MagicpromptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val magicPromptRepository: MagicPromptRepository,
    private val connectionRepository: ConnectionRepository,
    private val promptDrafts: PromptDraftRepository,
) : ViewModel() {

    private val mode: GenerationMode = runCatching {
        savedStateHandle.toRoute<MagicpromptRoute>().mode?.let { GenerationMode.valueOf(it) }
    }.getOrNull() ?: GenerationMode.SDXL

    private val input = MutableStateFlow("")
    private val output = MutableStateFlow("")
    private val models = MutableStateFlow<List<String>>(emptyList())
    private val selectedModel = MutableStateFlow("")
    private val apiKey = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private data class Busy(val generating: Boolean = false, val config: ConnectionConfig? = null)

    private val busy = MutableStateFlow(Busy())

    private data class Misc(
        val apiKey: String = "",
        val error: String? = null,
        val notice: String? = null,
        val generating: Boolean = false,
    )

    private val misc = combine(apiKey, error, notice, busy) { key, err, note, b ->
        Misc(key, err, note, b.generating)
    }

    val uiState: StateFlow<MagicpromptUiState> = combine(
        input,
        output,
        models,
        selectedModel,
        misc
    ) { inp, out, m, sel, mi ->
        MagicpromptUiState.Success(
            mode = mode,
            input = inp,
            output = out,
            models = m,
            selectedModel = sel.ifBlank { m.firstOrNull().orEmpty() },
            apiKey = mi.apiKey,
            generating = mi.generating,
            error = mi.error,
            notice = mi.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MagicpromptUiState.Loading,
    )

    init {
        viewModelScope.launch {
            connectionRepository.observe().collect { config ->
                if (apiKey.value.isBlank() && config.llmKey.isNotBlank()) {
                    apiKey.value = config.llmKey
                }
                if (selectedModel.value.isBlank() && config.llmModel.isNotBlank()) {
                    selectedModel.value = config.llmModel
                }
                busy.value = busy.value.copy(config = config)
            }
        }
        refreshModels()
    }

    fun onAction(action: MagicpromptAction) {
        when (action) {
            is MagicpromptAction.InputChanged -> input.value = action.value
            is MagicpromptAction.ModelChanged -> selectedModel.value = action.value
            is MagicpromptAction.ApiKeyChanged -> apiKey.value = action.value
            MagicpromptAction.RefreshModels -> refreshModels()
            MagicpromptAction.Generate -> generate()
            MagicpromptAction.UseAsPrompt -> useAsPrompt()
            MagicpromptAction.Dismiss -> {
                error.value = null
                notice.value = null
            }
        }
    }

    private fun refreshModels() {
        viewModelScope.launch {
            when (val r = magicPromptRepository.fetchModels()) {
                is Result.Success -> models.value = r.data
                is Result.Error -> error.value = r.message
                is Result.Loading -> Unit
            }
        }
    }

    private fun generate() {
        val text = input.value.trim()
        if (text.isEmpty()) {
            error.value = "Type an idea first."
            return
        }
        val model = selectedModel.value.ifBlank { models.value.firstOrNull().orEmpty() }
        if (model.isBlank()) {
            error.value = "No LLM model available."
            return
        }
        viewModelScope.launch {
            busy.value = busy.value.copy(generating = true)
            error.value = null
            persistKeyAndModel(model)
            when (val r = magicPromptRepository.expand(systemPromptFor(mode), model, text)) {
                is Result.Success -> output.value = r.data
                is Result.Error -> error.value = r.message
                is Result.Loading -> Unit
            }
            busy.value = busy.value.copy(generating = false)
        }
    }

    private suspend fun persistKeyAndModel(model: String) {
        val config = busy.value.config ?: return
        if (config.llmKey != apiKey.value || config.llmModel != model) {
            connectionRepository.save(config.copy(llmKey = apiKey.value, llmModel = model))
        }
    }

    private fun useAsPrompt() {
        val text = output.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val current = promptDrafts.observeDraft(mode).first()
            promptDrafts.setPrompt(mode, text, current.negativePrompt)
            notice.value = "Set as ${mode.name} prompt"
        }
    }
}

sealed interface MagicpromptAction {
    data class InputChanged(val value: String) : MagicpromptAction
    data class ModelChanged(val value: String) : MagicpromptAction
    data class ApiKeyChanged(val value: String) : MagicpromptAction
    data object RefreshModels : MagicpromptAction
    data object Generate : MagicpromptAction
    data object UseAsPrompt : MagicpromptAction
    data object Dismiss : MagicpromptAction
}
