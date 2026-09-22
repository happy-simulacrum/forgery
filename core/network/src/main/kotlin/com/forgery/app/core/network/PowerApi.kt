package com.forgery.app.core.network

import com.forgery.app.core.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bojro Power helper (`BojroPowerv5portable.exe :5000`) — ports
 * `sendPowerSignal / sendStopSignal / sendServiceSignal` in network.js:
 * `POST {wakeUrl}/power`, `POST {wakeUrl}/power/off`,
 * `POST {wakeUrl}/power/{forge|comfy|lm}/on|off`.
 */
@Singleton
class PowerGateway @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun post(url: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .header("ngrok-skip-browser-warning", "true")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.Success(response.code)
                else Result.Error("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString(), e)
        }
    }
}
