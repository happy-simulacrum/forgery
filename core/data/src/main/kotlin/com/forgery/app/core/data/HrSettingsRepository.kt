package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.model.HrSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Hi-Res Fix settings per generation mode (legacy bojro_{mode}_hr_*). */
interface HrSettingsRepository {
    fun observeHr(mode: GenerationMode): Flow<HrSettings>
    suspend fun saveHr(mode: GenerationMode, hr: HrSettings)
}

@Singleton
class DefaultHrSettingsRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : HrSettingsRepository {
    override fun observeHr(mode: GenerationMode): Flow<HrSettings> = prefs.observeHr(mode)
    override suspend fun saveHr(mode: GenerationMode, hr: HrSettings) = prefs.saveHr(mode, hr)
}
