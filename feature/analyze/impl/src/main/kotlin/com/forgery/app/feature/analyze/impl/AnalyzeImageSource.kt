package com.forgery.app.feature.analyze.impl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Raw-bytes reader for the analyzer (metadata parsing itself is pure JVM). */
interface AnalyzeImageSource {
    suspend fun read(uri: String): ByteArray?
}

@Singleton
class ContentResolverAnalyzeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AnalyzeImageSource {
    override suspend fun read(uri: String): ByteArray? = try {
        context.contentResolver.openInputStream(
            android.net.Uri.parse(uri),
        )?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
}
