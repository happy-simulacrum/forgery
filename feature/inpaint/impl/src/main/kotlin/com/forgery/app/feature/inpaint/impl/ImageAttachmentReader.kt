package com.forgery.app.feature.inpaint.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a content [android.net.Uri] into a downscaled PNG base64 triple.
 * Separated for testability (Bitmap APIs are unavailable on JVM unit tests).
 */
interface ImageAttachmentReader {
    /** Returns Triple(base64png, width, height) or null when unreadable. */
    suspend fun readDownscaled(uri: String, maxSide: Int): Triple<String, Int, Int>?
}

@Singleton
class ContentResolverImageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImageAttachmentReader {

    override suspend fun readDownscaled(uri: String, maxSide: Int): Triple<String, Int, Int>? {
        val bytes = context.contentResolver.openInputStream(
            android.net.Uri.parse(uri),
        )?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val result = Triple(
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP),
            bmp.width,
            bmp.height,
        )
        bmp.recycle()
        return result
    }
}
