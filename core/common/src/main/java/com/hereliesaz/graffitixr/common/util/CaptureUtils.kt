package com.hereliesaz.graffitixr.common.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import timber.log.Timber

fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "GraffitiXR_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GraffitiXR")
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false

    return try {
        val saved = resolver.openOutputStream(uri)?.let {
            it.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } ?: false
        // A null stream or a failed compress() leaves the just-inserted MediaStore row pointing at
        // an empty/nonexistent file — clean it up instead of leaving an orphaned gallery entry.
        if (!saved) resolver.delete(uri, null, null)
        saved
    } catch (e: Exception) {
        Timber.e(e, "CaptureUtils: failed to save bitmap")
        resolver.delete(uri, null, null)
        false
    }
}
