package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.model.GenDefaults
import com.forgery.app.core.model.GenerationMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Saved GEN defaults per generation mode, applied on start/mode switch. */
interface DefaultsRepository {
    fun observeDefaults(mode: GenerationMode): Flow<GenDefaults>
    suspend fun saveDefault(mode: GenerationMode, field: DefaultField, value: String)
}

@Singleton
class DefaultDefaultsRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : DefaultsRepository {
    override fun observeDefaults(mode: GenerationMode): Flow<GenDefaults> = prefs.observeDefaults(mode)
    override suspend fun saveDefault(mode: GenerationMode, field: DefaultField, value: String) =
        prefs.saveDefault(mode, field, value)
}
