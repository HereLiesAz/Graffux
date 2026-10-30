package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Round-2 audit #12: a failed copy must not leave a (partial) row in Downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadsPublisherTest {

    private class FakeStore(private val out: () -> OutputStream?) : DownloadsPublisher.Store {
        val inserted = mutableListOf<ContentValues>()
        val updated = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()
        override fun insert(values: ContentValues): Uri =
            Uri.parse("content://media/downloads/1").also { inserted += values }
        override fun openOutputStream(uri: Uri): OutputStream? = out()
        override fun update(uri: Uri, values: ContentValues) { updated += values }
        override fun delete(uri: Uri) { deleted += uri }
    }

    private fun source(): File =
        File.createTempFile("export", ".svg").apply { writeText("x".repeat(4096)); deleteOnExit() }

    @Test
    fun `a copy that fails mid-stream deletes the row`() {
        val store = FakeStore {
            object : OutputStream() { override fun write(b: Int) = throw IOException("disk full") }
        }
        try {
            DownloadsPublisher.publish(store, source(), "image/svg+xml")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(1, store.deleted.size)
        assertTrue(store.updated.isEmpty())
    }

    @Test
    fun `the row is pending until the copy completes, then published`() {
        val sink = java.io.ByteArrayOutputStream()
        val store = FakeStore { sink }
        DownloadsPublisher.publish(store, source(), "image/svg+xml")
        assertEquals(1, store.inserted.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, store.updated.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(4096, sink.size())
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun `a null stream deletes the row`() {
        val store = FakeStore { null }
        try {
            DownloadsPublisher.publish(store, source(), "application/pdf")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(1, store.deleted.size)
    }
}
