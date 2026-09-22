package com.forgery.app.core.data

import com.forgery.app.core.model.RestoredParams
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot in-memory handoff of restored generation params from Analyze to Generate.
 * Written by Analyze on "copy to mode", consumed once by Generate.
 */
@Singleton
class AnalyzeHandoffRepository @Inject constructor() {
    private val state = MutableStateFlow<RestoredParams?>(null)

    fun set(p: RestoredParams) {
        state.value = p
    }

    /** Returns the pending params and clears the slot. */
    fun consume(): RestoredParams? {
        val pending = state.value
        state.value = null
        return pending
    }
}
