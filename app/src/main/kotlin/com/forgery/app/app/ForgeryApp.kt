package com.forgery.app.app

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.forgery.app.core.network.ForgeApiFactory
import com.forgery.app.core.network.LlmApiFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ForgeryApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var forgeApiFactory: ForgeApiFactory

    @Inject
    lateinit var llmApiFactory: LlmApiFactory

    override fun onCreate() {
        super.onCreate()
        // HTTP logging rides into release builds (network module has no
        // BuildConfig); keep it debug-only.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        forgeApiFactory.debugLogging = debuggable
        llmApiFactory.debugLogging = debuggable
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
