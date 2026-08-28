package com.hereliesaz.graffitixr.feature.editor.util

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads Krita's `.kpp` brush preset container: a PNG whose `tEXt`/`iTXt`/`zTXt`
 * chunks carry the preset XML under the "preset" keyword -- a real preset is
 * usually large enough that Krita's writer reaches for the deflate-compressed
 * `zTXt` form; confirmed by fetching and decompressing a real shipped preset,
 * `plugins/paintops/defaultpresets/colorsmudge.kpp` (KDE/krita repository).
 * Confirmed against
 * Krita's own `KisPaintOpPreset::saveToDevice()`/`loadFromDevice()` (PNG text
 * chunk keywords "version"/"preset", root element `<Preset paintopid name
 * embedded_resources>`) and `KisPropertiesConfiguration::toXML()`/`fromXML()`
 * (child `<param name="..." type="...">value</param>` elements), read from
 * the KDE/krita source directly rather than guessed.
 *
 * This is a read-only *container* parser: it recovers the `<Preset>` element
 * and its `param` children as raw key/value pairs. It deliberately does NOT
 * map those keys onto Graffux's own primitives (`AzphaltBrush` /
 * `ColorSmudgeEngine.Settings` / `MaskedBrushConfig`) -- Krita's per-paintop
 * -engine parameter names (e.g. what the Color Smudge settings widget calls
 * its own rate/dilution keys) have not been verified from source, so
 * semantic mapping is left for a follow-up rather than guessed.
 */
object KritaPresetParser {

    data class Preset(
        val paintopId: String,
        val name: String,
        val embeddedResourceCount: Int,
        val params: Map<String, Param>,
    )

    data class Param(val type: String?, val value: String)

    class ParseException(message: String) : Exception(message)

    /** Parses a `.kpp` file's raw bytes. Throws [ParseException] if there's no "preset" text chunk or the XML is malformed. */
    fun parse(bytes: ByteArray): Preset {
        val presetXml = readTextChunk(bytes, "preset")
            ?: throw ParseException("No \"preset\" text chunk found in PNG")
        return parsePresetXml(presetXml)
    }

    internal fun parsePresetXml(xml: String): Preset {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Untrusted input (a .kpp is just a PNG someone can hand you): disable DOCTYPE entirely,
        // which by itself blocks XXE (external entities) and billion-laughs (internal entity
        // expansion) alike, since neither can exist without a DOCTYPE declaration. The other
        // features are defense in depth in case some parser implementation doesn't honor the
        // first one for every entity path.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val doc = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw ParseException("Malformed preset XML: ${e.message}")
        }
        val root = doc.documentElement
            ?: throw ParseException("Preset XML has no root element")
        if (root.tagName != "Preset") {
            throw ParseException("Expected root element <Preset>, found <${root.tagName}>")
        }
        val params = mutableMapOf<String, Param>()
        val children = root.getElementsByTagName("param")
        for (i in 0 until children.length) {
            val node = children.item(i) as Element
            val name = node.getAttribute("name")
            val type = node.getAttribute("type").ifEmpty { null }
            params[name] = Param(type, node.textContent)
        }
        return Preset(
            paintopId = root.getAttribute("paintopid"),
            name = root.getAttribute("name"),
            embeddedResourceCount = root.getAttribute("embedded_resources").toIntOrNull() ?: 0,
            params = params,
        )
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Walks PNG chunks looking for a `tEXt`/`iTXt` chunk with the given keyword. Returns null if absent or the file isn't a PNG. */
    internal fun readTextChunk(bytes: ByteArray, keyword: String): String? {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null

        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = readInt32BE(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            // Long arithmetic here is load-bearing, not style: a length near Int.MAX_VALUE makes
            // `dataStart + length + 4` wrap around as an Int and pass the bounds check with a
            // corrupted (possibly negative) result, which then reaches copyOfRange() with an
            // effectively arbitrary size and throws something other than ParseException (an
            // uncaught OutOfMemoryError/NegativeArraySizeException) -- exactly the failure this
            // function's contract promises callers won't see.
            if (length < 0 || dataStart.toLong() + length.toLong() + 4L > bytes.size.toLong()) break
            val data = bytes.copyOfRange(dataStart, dataStart + length)
            when (type) {
                "tEXt" -> readLatin1TextChunk(data, keyword)?.let { return it }
                "iTXt" -> readInternationalTextChunk(data, keyword)?.let { return it }
                "zTXt" -> readCompressedTextChunk(data, keyword)?.let { return it }
                "IEND" -> return null
            }
            offset = dataStart + length + 4 // + CRC
        }
        return null
    }

    private fun readLatin1TextChunk(data: ByteArray, keyword: String): String? {
        val nul = data.indexOfByte(0)
        if (nul < 0) return null
        val kw = String(data, 0, nul, Charsets.ISO_8859_1)
        if (kw != keyword) return null
        return String(data, nul + 1, data.size - nul - 1, Charsets.ISO_8859_1)
    }

    /**
     * A `zTXt` chunk (PNG spec: keyword, NUL, one-byte compression method, then zlib-deflated
     * Latin-1 text). Krita's own `.kpp` writer reaches for this instead of `tEXt`/`iTXt` for a
     * "preset" chunk large enough to be worth compressing -- confirmed directly: the real
     * `plugins/paintops/defaultpresets/colorsmudge.kpp` (fetched from the KDE/krita repository,
     * `invent.kde.org/graphics/krita`, `master` branch) stores its `"preset"` text this way, not
     * as `tEXt`/`iTXt`. A compression method other than `0` (deflate) is rejected rather than
     * guessed at, matching [readInternationalTextChunk]'s existing "unsupported compression"
     * contract for `iTXt`.
     */
    private fun readCompressedTextChunk(data: ByteArray, keyword: String): String? {
        val nul = data.indexOfByte(0)
        if (nul < 0) return null
        val kw = String(data, 0, nul, Charsets.ISO_8859_1)
        if (kw != keyword) return null
        val compressionMethod = data.getOrNull(nul + 1)?.toInt() ?: return null
        if (compressionMethod != 0) {
            throw ParseException("Unsupported zTXt compression method $compressionMethod for \"$keyword\" chunk")
        }
        val compressed = data.copyOfRange(nul + 2, data.size)
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(compressed.size * 3)
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, count)
            }
        } catch (e: Exception) {
            throw ParseException("Malformed zTXt \"$keyword\" chunk: ${e.message}")
        } finally {
            inflater.end()
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readInternationalTextChunk(data: ByteArray, keyword: String): String? {
        val nul = data.indexOfByte(0)
        if (nul < 0) return null
        val kw = String(data, 0, nul, Charsets.ISO_8859_1)
        if (kw != keyword) return null
        var p = nul + 1
        if (p >= data.size) return null
        val compressionFlag = data[p].toInt()
        p += 2 // compression flag + compression method
        val langEnd = data.indexOfByte(0, p).takeIf { it >= 0 } ?: return null
        p = langEnd + 1
        val transEnd = data.indexOfByte(0, p).takeIf { it >= 0 } ?: return null
        p = transEnd + 1
        if (compressionFlag != 0) {
            throw ParseException("Compressed iTXt \"$keyword\" chunk is not supported")
        }
        return String(data, p, data.size - p, Charsets.UTF_8)
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun ByteArray.indexOfByte(byte: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == byte) return i
        return -1
    }
}
