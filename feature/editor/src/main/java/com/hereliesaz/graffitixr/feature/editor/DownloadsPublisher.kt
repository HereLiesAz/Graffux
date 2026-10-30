package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Copies a finished export into Downloads through MediaStore (API 29+).
 *
 * The row is inserted with `IS_PENDING = 1`, so neither the user nor other apps see a file while it
 * is being written, and it is published (`IS_PENDING = 0`) only once every byte has landed. Any
 * failure after the insert deletes the row, so a disk-full or revoked stream never leaves a
 * half-written file in Downloads under its final name.
 */
internal object DownloadsPublisher {

    /** The four MediaStore calls [publish] makes; a seam so the cleanup rules can be tested. */
    interface Store {
        fun insert(values: ContentValues): Uri?
        fun openOutputStream(uri: Uri): OutputStream?
        fun update(uri: Uri, values: ContentValues)
        fun delete(uri: Uri)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun publish(resolver: ContentResolver, file: File, mimeType: String): Uri =
        publish(ResolverStore(resolver), file, mimeType)

    @RequiresApi(Build.VERSION_CODES.Q)
    fun publish(store: Store, file: File, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = store.insert(values) ?: throw IOException("Failed to create MediaStore entry")
        var published = false
        try {
            val out = store.openOutputStream(uri) ?: throw IOException("couldn't open the file")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            store.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) })
            published = true
        } finally {
            // Cancellation, IO failure or OOM alike: never leave the pending row behind.
            if (!published) runCatching { store.delete(uri) }
        }
        return uri
    }

    private class ResolverStore(private val resolver: ContentResolver) : Store {
        @RequiresApi(Build.VERSION_CODES.Q)
        override fun insert(values: ContentValues): Uri? =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

        override fun openOutputStream(uri: Uri): OutputStream? = resolver.openOutputStream(uri)

        override fun update(uri: Uri, values: ContentValues) {
            resolver.update(uri, values, null, null)
        }

        override fun delete(uri: Uri) {
            resolver.delete(uri, null, null)
        }
    }
}
