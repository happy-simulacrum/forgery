package com.forgery.app.feature.power.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.common.Result
import com.forgery.app.core.data.PowerRepository
import com.forgery.app.core.data.PowerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PowerViewModel @Inject constructor(
    private val powerRepository: PowerRepository,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val isError = MutableStateFlow(false)

    private data class Misc(val busy: Boolean, val message: String?, val isError: Boolean)

    val uiState: StateFlow<PowerUiState> = combine(
        busy,
        message,
        isError
    ) { b, m, e ->
        PowerUiState.Success(busy = b, message = m, isError = e)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PowerUiState.Loading,
    )

    fun onAction(action: PowerAction) {
        when (action) {
            PowerAction.Wake -> run { powerRepository.wakeAll() }
            PowerAction.Kill -> run { powerRepository.powerOff() }
            is PowerAction.SetService -> run {
                powerRepository.setService(action.service, action.on)
            }
            PowerAction.Dismiss -> {
                message.value = null
                isError.value = false
            }
        }
    }

    private fun run(block: suspend () -> Result<String>) {
        viewModelScope.launch {
            busy.value = true
            isError.value = false
            when (val r = block()) {
                is Result.Success -> message.value = r.data
                is Result.Error -> {
                    message.value = r.message
                    isError.value = true
                }
                is Result.Loading -> Unit
            }
            busy.value = false
        }
    }
}

sealed interface PowerAction {
    data object Wake : PowerAction
    data object Kill : PowerAction
    data class SetService(val service: PowerService, val on: Boolean) : PowerAction
    data object Dismiss : PowerAction
}
