package com.forgery.app.feature.gallery.impl

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.forgery.app.core.common.Result
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun saveImageToGallery(context: Context, imagePath: String): Result<String> =
    withContext(Dispatchers.IO) {
        try {
            val source = File(imagePath)
            if (!source.exists()) {
                return@withContext Result.Error("Source image not found: $imagePath")
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Forgery_${System.currentTimeMillis()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Forgery")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return@withContext Result.Error("Failed to create MediaStore entry.")
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext Result.Error("Failed to write image data.")
            Result.Success(uri.toString())
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to save image.", e)
        }
    }

fun shareImage(context: Context, imagePath: String) {
    try {
        val authority = context.packageName + ".fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(imagePath))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share image"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share image.", Toast.LENGTH_SHORT).show()
    }
}
