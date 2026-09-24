package com.forgery.app.core.data

import com.forgery.app.core.common.normalizeModelTitle
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.ModelLastUsed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Last-used generation params per model, keyed by normalized model title. */
interface ModelParamsRepository {
    fun observeForModel(modelTitle: String): Flow<ModelLastUsed?>
    suspend fun saveForModel(modelTitle: String, params: ModelLastUsed)
}

@Singleton
class DefaultModelParamsRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : ModelParamsRepository {
    override fun observeForModel(modelTitle: String): Flow<ModelLastUsed?> {
        val key = normalizeModelTitle(modelTitle)
        if (key.isBlank()) return flowOf(null)
        return prefs.observeModelParams().map { it[key] }
    }

    override suspend fun saveForModel(modelTitle: String, params: ModelLastUsed) {
        val key = normalizeModelTitle(modelTitle)
        if (key.isBlank()) return
        prefs.saveModelParams(key, params)
    }
}
