package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.feature.editor.util.KritaPresetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Fixtures below hand-build minimal PNG byte streams with a `tEXt`/`iTXt`
 * chunk, mirroring the container Krita's `KisPaintOpPreset::saveToDevice()`
 * writes. [KritaPresetParser] never validates chunk CRCs, so the CRC field
 * is left as zero bytes -- a real `.kpp` file has real CRCs, but the parser
 * doesn't need them to be correct to read the "preset" text out.
 */
class KritaPresetParserTest {

    private val samplePresetXml = """
        <Preset paintopid="colorsmudge" name="Sample Smudge" embedded_resources="0">
        <param name="Smudge/Rate" type="string">0.72</param>
        <param name="ColorRate/value" type="string">0.35</param>
        <param name="paintOpMirror">1</param>
        </Preset>
    """.trimIndent()

    private fun pngWithTextChunk(keyword: String, text: String, international: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.writeChunk("IHDR", ByteArray(13))
        val data = if (international) {
            val payload = ByteArrayOutputStream()
            payload.write(keyword.toByteArray(Charsets.ISO_8859_1))
            payload.write(0)
            payload.write(0) // compression flag
            payload.write(0) // compression method
            payload.write(0) // language tag terminator
            payload.write(0) // translated keyword terminator
            payload.write(text.toByteArray(Charsets.UTF_8))
            payload.toByteArray()
        } else {
            val payload = ByteArrayOutputStream()
            payload.write(keyword.toByteArray(Charsets.ISO_8859_1))
            payload.write(0)
            payload.write(text.toByteArray(Charsets.ISO_8859_1))
            payload.toByteArray()
        }
        out.writeChunk(if (international) "iTXt" else "tEXt", data)
        out.writeChunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        write(intToBytesBE(data.size))
        write(type.toByteArray(Charsets.US_ASCII))
        write(data)
        write(ByteArray(4)) // CRC, unchecked by the parser
    }

    private fun intToBytesBE(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    @Test
    fun `parses paintopid, name, and params from a tEXt chunk`() {
        val bytes = pngWithTextChunk("preset", samplePresetXml)

        val preset = KritaPresetParser.parse(bytes)

        assertEquals("colorsmudge", preset.paintopId)
        assertEquals("Sample Smudge", preset.name)
        assertEquals(0, preset.embeddedResourceCount)
        assertEquals("0.72", preset.params.getValue("Smudge/Rate").value)
        assertEquals("string", preset.params.getValue("Smudge/Rate").type)
        assertEquals("0.35", preset.params.getValue("ColorRate/value").value)
    }

    @Test
    fun `a param with no type attribute has a null type`() {
        val bytes = pngWithTextChunk("preset", samplePresetXml)

        val preset = KritaPresetParser.parse(bytes)

        assertNull(preset.params.getValue("paintOpMirror").type)
        assertEquals("1", preset.params.getValue("paintOpMirror").value)
    }

    @Test
    fun `reads an iTXt chunk the same as a tEXt chunk`() {
        val bytes = pngWithTextChunk("preset", samplePresetXml, international = true)

        val preset = KritaPresetParser.parse(bytes)

        assertEquals("colorsmudge", preset.paintopId)
        assertEquals("0.72", preset.params.getValue("Smudge/Rate").value)
    }

    @Test
    fun `throws when there is no preset text chunk`() {
        val bytes = pngWithTextChunk("version", "5.2.0")

        assertThrows(KritaPresetParser.ParseException::class.java) {
            KritaPresetParser.parse(bytes)
        }
    }

    @Test
    fun `readTextChunk returns null for non-PNG bytes`() {
        val bytes = "not a png".toByteArray()

        assertNull(KritaPresetParser.readTextChunk(bytes, "preset"))
    }

    @Test
    fun `throws on malformed preset XML`() {
        val bytes = pngWithTextChunk("preset", "<Preset paintopid=\"x\"><param name=\"a\">unterminated")

        assertThrows(KritaPresetParser.ParseException::class.java) {
            KritaPresetParser.parse(bytes)
        }
    }

    @Test
    fun `throws when the root element is not Preset`() {
        val bytes = pngWithTextChunk("preset", "<NotAPreset/>")

        assertThrows(KritaPresetParser.ParseException::class.java) {
            KritaPresetParser.parse(bytes)
        }
    }

    @Test
    fun `a DOCTYPE with an external entity is rejected, not resolved`() {
        // Attempts to read /etc/hostname into the "name" attribute via XXE. A correctly hardened
        // parser refuses the DOCTYPE outright (ParseException), never resolving the entity and
        // never leaking file content into the parsed Preset.
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE Preset [<!ENTITY x SYSTEM "file:///etc/hostname">]>
            <Preset paintopid="x" name="&x;" embedded_resources="0"/>
        """.trimIndent()
        val bytes = pngWithTextChunk("preset", xxe)

        assertThrows(KritaPresetParser.ParseException::class.java) {
            KritaPresetParser.parse(bytes)
        }
    }

    @Test
    fun `a chunk length near Int overflow fails safely instead of throwing an uncaught Error`() {
        // A tEXt chunk claiming a length of ~2^31-16 bytes, in a file nowhere near that size.
        // dataStart + length + 4 must be computed without wrapping to a small/negative Int, or
        // this reaches copyOfRange() with a corrupted size and throws something other than the
        // documented ParseException (OutOfMemoryError, NegativeArraySizeException, ...).
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(intToBytesBE(Int.MAX_VALUE - 16))
        out.write("tEXt".toByteArray(Charsets.US_ASCII))
        out.write("preset".toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write("not actually this long".toByteArray(Charsets.ISO_8859_1))
        // No CRC/IEND -- the truncated file is the point; a real file of this claimed length
        // would be ~2 GiB, which this test deliberately does not construct.
        val bytes = out.toByteArray()

        assertThrows(KritaPresetParser.ParseException::class.java) {
            KritaPresetParser.parse(bytes)
        }
    }
}
