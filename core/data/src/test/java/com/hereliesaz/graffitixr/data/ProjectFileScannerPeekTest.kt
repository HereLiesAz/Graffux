package com.hereliesaz.graffitixr.data

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectFileScannerPeekTest {

    private val scanner = ProjectFileScanner(mockk<Context>(relaxed = true))

    /** A stream that counts how many bytes of the underlying archive were consumed. */
    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `reads name and thumbnail`() {
        val bytes = zip(
            "project.json" to """{"name":"Wall"}""".toByteArray(),
            "thumbnail.png" to byteArrayOf(1, 2, 3),
        )
        val peeked = scanner.peek(ByteArrayInputStream(bytes))
        assertEquals("Wall", peeked.name)
        assertEquals(3, peeked.thumbnail?.size)
    }

    @Test
    fun `stops at the manifest when the archive has no thumbnail`() {
        // Incompressible layer data behind the manifest: the scan must not read through it.
        val junk = ByteArray(3 * 1024 * 1024).also { java.util.Random(1).nextBytes(it) }
        val bytes = zip(
            "project.json" to """{"name":"Wall"}""".toByteArray(),
            "layers/a.png" to junk,
            "layers/b.png" to junk,
            "layers/c.png" to junk,
        )
        val counting = CountingStream(ByteArrayInputStream(bytes))
        val peeked = scanner.peek(counting)

        assertEquals("Wall", peeked.name)
        assertNull(peeked.thumbnail)
        val read = counting.count
        org.junit.Assert.assertTrue("read $read of ${bytes.size} bytes", read < bytes.size - junk.size / 2)
    }
}
