package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.model.GenDefaults
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Saved GEN defaults, applied on start. */
interface DefaultsRepository {
    fun observeDefaults(): Flow<GenDefaults>
    suspend fun saveDefault(field: DefaultField, value: String)
}

@Singleton
class DefaultDefaultsRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : DefaultsRepository {
    override fun observeDefaults(): Flow<GenDefaults> = prefs.observeDefaults()
    override suspend fun saveDefault(field: DefaultField, value: String) =
        prefs.saveDefault(field, value)
}
