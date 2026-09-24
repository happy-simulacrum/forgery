package com.forgery.app.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.model.UiPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    connectionRepository: ConnectionRepository,
) : ViewModel() {

    val uiPrefs: StateFlow<UiPrefs> = connectionRepository.observeUiPrefs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiPrefs())
}
