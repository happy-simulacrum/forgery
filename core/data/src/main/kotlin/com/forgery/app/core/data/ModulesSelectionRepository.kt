package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selected VAE / Text Encoder modules.
 * Written by the Modules browser, read by Generate/Inpaint when building jobs
 * (legacy: Neo `forge_additional_modules` multiselect).
 */
interface ModulesSelectionRepository {
    fun observeModules(): Flow<List<String>>
    suspend fun saveModules(modules: List<String>)
}

@Singleton
class DefaultModulesSelectionRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : ModulesSelectionRepository {
    override fun observeModules(): Flow<List<String>> =
        prefs.observeModules()

    override suspend fun saveModules(modules: List<String>) =
        prefs.saveModules(modules)
}
