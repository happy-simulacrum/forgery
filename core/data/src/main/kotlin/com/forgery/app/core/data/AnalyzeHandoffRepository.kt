package com.forgery.app.core.data

import com.forgery.app.core.model.RestoredParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot in-memory handoff of restored generation params from Analyze to Generate.
 * Written by Analyze on "use again", consumed by Generate. Generate subscribes
 * continuously (not just at init): applies every non-null emission, then
 * consumes it — covers both cold start and warm reuse of an alive GEN screen.
 */
@Singleton
class AnalyzeHandoffRepository @Inject constructor() {
    private val state = MutableStateFlow<RestoredParams?>(null)

    fun set(p: RestoredParams) {
        state.value = p
    }

    /** Peek without clearing (for continuous subscribers). */
    fun observe(): Flow<RestoredParams?> = state.asStateFlow()

    /** Returns the pending params and clears the slot (atomic). */
    fun consume(): RestoredParams? {
        return state.getAndUpdate { null }
    }
}
