package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.GenerationMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selected VAE / Text Encoder modules per generation mode.
 * Written by the Modules browser, read by Generate/Inpaint when building jobs
 * (legacy: Neo `forge_additional_modules` multiselect).
 */
interface ModulesSelectionRepository {
    fun observeModules(mode: GenerationMode): Flow<List<String>>
    suspend fun saveModules(mode: GenerationMode, modules: List<String>)
}

@Singleton
class DefaultModulesSelectionRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : ModulesSelectionRepository {
    override fun observeModules(mode: GenerationMode): Flow<List<String>> =
        prefs.observeModules(mode)

    override suspend fun saveModules(mode: GenerationMode, modules: List<String>) =
        prefs.saveModules(mode, modules)
}
