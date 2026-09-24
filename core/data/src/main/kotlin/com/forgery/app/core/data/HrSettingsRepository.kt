package com.forgery.app.core.data

import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.HrSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Hi-Res Fix settings (legacy bojro_hr_*). */
interface HrSettingsRepository {
    fun observeHr(): Flow<HrSettings>
    suspend fun saveHr(hr: HrSettings)
}

@Singleton
class DefaultHrSettingsRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : HrSettingsRepository {
    override fun observeHr(): Flow<HrSettings> = prefs.observeHr()
    override suspend fun saveHr(hr: HrSettings) = prefs.saveHr(hr)
}
