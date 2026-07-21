// FILE: core/common/src/test/java/com/hereliesaz/graffitixr/common/importer/DocumentImporterTest.kt
package com.hereliesaz.graffitixr.common.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentImporterTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun detect_prefersMagicOverExtension() {
        val psdMagic = bytes(0x38, 0x42, 0x50, 0x53, 0, 0)
        // A PSD renamed to .png is still a PSD by magic.
        assertEquals(DocumentFormat.PSD, DocumentImporter.detect("art.png", psdMagic))
        // A PDF stream, whatever the name.
        assertEquals(DocumentFormat.PDF, DocumentImporter.detect(null, bytes(0x25, 0x50, 0x44, 0x46)))
    }

    @Test
    fun detect_fallsBackToExtension() {
        val junk = bytes(0, 1, 2, 3)
        assertEquals(DocumentFormat.PSD, DocumentImporter.detect("a.psd", junk))
        assertEquals(DocumentFormat.PDF, DocumentImporter.detect("a.ai", junk))
        assertEquals(DocumentFormat.PROCREATE, DocumentImporter.detect("a.procreate", junk))
        assertEquals(DocumentFormat.CANVA, DocumentImporter.detect("a.canva", junk))
    }

    @Test
    fun detect_classifiesRasterAndUnknown() {
        assertEquals(DocumentFormat.IMAGE, DocumentImporter.detect(null, bytes(0x89, 0x50, 0x4E, 0x47))) // PNG
        assertEquals(DocumentFormat.IMAGE, DocumentImporter.detect(null, bytes(0xFF, 0xD8, 0xFF)))       // JPEG
        assertEquals(DocumentFormat.UNKNOWN, DocumentImporter.detect("mystery.dat", bytes(1, 2, 3, 4)))
    }

    @Test
    fun readLayered_parsesPsdAndSkipsOthers() {
        // Minimal 1x1 RGB PSD with a merged (flattened) image and no discrete layers.
        val psd = ByteArrayOutputStreamBytes {
            writeAscii("8BPS"); writeBe16(1); write(ByteArray(6))
            writeBe16(3); writeBe32(1); writeBe32(1); writeBe16(8); writeBe16(3) // 1x1, 8-bit, RGB
            writeBe32(0); writeBe32(0) // colour mode + resources
            writeBe32(0)               // no layer/mask section
            writeBe16(0)               // merged compression raw
            write(byteArrayOf(200.toByte(), 100.toByte(), 50.toByte())) // R, G, B planes (1px each)
        }
        val doc = DocumentImporter.readLayered("art.psd", psd)
        assertNotNull(doc)
        assertEquals(1, doc!!.layers.size)
        assertEquals(0xFFC86432.toInt(), doc.layers[0].pixel(0, 0))

        // A PNG isn't decoded here (needs an Android decoder).
        assertNull(DocumentImporter.readLayered("a.png", bytes(0x89, 0x50, 0x4E, 0x47)))
    }

    // Tiny big-endian byte-stream builder to keep the PSD literal readable.
    private class ByteWriter {
        val out = java.io.ByteArrayOutputStream()
        fun write(b: ByteArray) = out.write(b)
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun writeBe16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        fun writeBe32(v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }
    }

    private fun ByteArrayOutputStreamBytes(block: ByteWriter.() -> Unit): ByteArray =
        ByteWriter().apply(block).out.toByteArray()
}
