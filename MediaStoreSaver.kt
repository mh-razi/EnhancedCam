package com.example.enhancedcam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaStoreSaver {

    private const val ALBUM = "EnhancedCam"

    fun newFileName(prefix: String): String =
        "${prefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"

    /** Values for CameraX's direct-to-MediaStore output (non-enhanced path). */
    fun contentValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
    }

    /** Writes encoded JPEG bytes to Pictures/EnhancedCam on the IO dispatcher. */
    suspend fun saveJpeg(context: Context, jpeg: ByteArray): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = contentValues(newFileName("ENH")).apply {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) }
                ?: throw IllegalStateException("Cannot open output stream")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
