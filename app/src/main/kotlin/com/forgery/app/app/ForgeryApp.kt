package com.forgery.app.app

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.forgery.app.core.data.OfflineFirstHistoryRepository
import com.forgery.app.core.network.ForgeApiFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ForgeryApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var forgeApiFactory: ForgeApiFactory

    @Inject
    lateinit var historyRepository: OfflineFirstHistoryRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // HTTP logging rides into release builds (network module has no
        // BuildConfig); keep it debug-only.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        forgeApiFactory.debugLogging = debuggable
        // H-5: drop gallery files orphaned by crashes/kills (rows are the
        // source of truth, so the sweep only removes unreferenced files).
        appScope.launch {
            runCatching { historyRepository.sweepOrphanFiles() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
