// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/importer/PsdReader.kt
package com.hereliesaz.graffitixr.common.importer

import com.hereliesaz.graffitixr.common.model.BlendMode

/** Thrown when a byte stream is not a PSD, or uses a PSD feature this reader does not support. */
class PsdFormatException(message: String) : Exception(message)

/**
 * A pure-Kotlin reader for Adobe Photoshop `.psd` documents — enough of the (public, well-documented)
 * format to import a layered PSD into the editor as individual [ImportedLayer]s, preserving each
 * layer's name, canvas position, opacity, and blend mode.
 *
 * Scope, chosen to keep the parser correct and testable rather than exhaustive:
 *  - **8-bit depth, RGB colour mode, version 1** (the overwhelmingly common "save for interchange"
 *    case). Anything else — 16/32-bit, CMYK/Lab, PSB (version 2) — raises [PsdFormatException] so the
 *    caller can fall back to a flattened import or tell the user.
 *  - Both channel compressions are handled: **0 = raw**, **1 = RLE/PackBits**.
 *  - Layer groups/dividers (zero-area records) are skipped; effects, masks, adjustment layers and
 *    smart objects are read as their composited pixels only (their extra data is skipped).
 *
 * Everything here is Android-free (plain [IntArray] ARGB out) so it runs under a JVM unit test.
 */
object PsdReader {

    private const val SIGNATURE_8BPS = 0x38425053L // "8BPS"
    private const val SIGNATURE_8BIM = 0x3842494DL // "8BIM"
    private const val MAX_DIMENSION = 30000 // Photoshop's own canvas cap; guards against junk sizes.

    /**
     * [ByteArray]/[IntArray] wrappers that turn an [OutOfMemoryError] into [PsdFormatException].
     *
     * MAX_DIMENSION bounds each side of a layer independently, but a layer sized right at that cap
     * is still a ~900MB single allocation per channel — reachable from a small file, since RLE
     * compression means the declared width/height cost none of the file's actual bytes. A layer
     * declaring many channels multiplies that further. [OutOfMemoryError] extends [Error], not
     * [Exception], so it passed straight through this class's own IndexOutOfBoundsException catch
     * in [read] and every caller's `catch (e: Exception)`, crashing the app outright on the mere act
     * of opening a malformed or oversized PSD. Every allocation sized off a file-declared width/
     * height goes through here instead, so the same failure surfaces as the typed exception this
     * reader already documents and every caller already handles.
     */
    // internal, not private: exercised directly in PsdReaderTest with a deliberately-unallocatable
    // size, rather than through the parser with a multi-hundred-megabyte layer — deterministic and
    // fast on any JVM, where actually exhausting the test heap would be neither.
    internal fun newByteArray(size: Int): ByteArray = try {
        ByteArray(size)
    } catch (e: OutOfMemoryError) {
        throw PsdFormatException("Layer too large to decode ($size bytes)")
    }

    internal fun newIntArray(size: Int): IntArray = try {
        IntArray(size)
    } catch (e: OutOfMemoryError) {
        throw PsdFormatException("Layer too large to decode ($size pixels)")
    }

    /** Cheap sniff: does [bytes] start with the PSD magic + version 1? */
    fun isPsd(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            Cursor(bytes).let { it.u32() == SIGNATURE_8BPS && it.u16() == 1 }

    /**
     * Parses [bytes] into an [ImportedDocument]. Throws [PsdFormatException] for a non-PSD or an
     * unsupported variant.
     */
    fun read(bytes: ByteArray): ImportedDocument {
        val c = Cursor(bytes)

        // ---- File header (26 bytes) ----
        if (c.u32() != SIGNATURE_8BPS) throw PsdFormatException("Not a PSD (bad signature)")
        val version = c.u16()
        if (version != 1) throw PsdFormatException("Unsupported PSD version $version (PSB not supported)")
        c.skip(6) // reserved, must be zero
        val channelCount = c.u16()
        val height = c.u32().toInt()
        val width = c.u32().toInt()
        val depth = c.u16()
        val colorMode = c.u16()
        if (depth != 8) throw PsdFormatException("Unsupported bit depth $depth (only 8-bit)")
        if (colorMode != 3) throw PsdFormatException("Unsupported colour mode $colorMode (only RGB)")
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw PsdFormatException("Unreasonable canvas size ${width}x$height")
        }

        // ---- Colour Mode Data + Image Resources: length-prefixed, skipped ----
        c.skip(c.u32().toInt()) // colour mode data
        c.skip(c.u32().toInt()) // image resources

        // ---- Layer and Mask Information ----
        val layerMaskLen = c.u32().toInt()
        val layerMaskEnd = c.pos + layerMaskLen
        val layers = if (layerMaskLen > 0) {
            try {
                readLayers(c, layerMaskEnd, width, height)
            } catch (e: IndexOutOfBoundsException) {
                // A truncated/corrupt RLE stream (PackBits.decode) or an out-of-range field read
                // during layer parsing previously escaped as a raw IndexOutOfBoundsException, which
                // the KDoc contract above doesn't promise and callers written to catch only
                // PsdFormatException wouldn't handle. Mirrors the merged-image fallback below, which
                // already catches the same failure mode for the flattened path.
                throw PsdFormatException("Corrupt or truncated layer data: ${e.message}")
            }
        } else emptyList()
        c.pos = layerMaskEnd

        if (layers.isNotEmpty()) return ImportedDocument(width, height, layers)

        // Flattened fallback: a PSD with no discrete layers still carries the composited base image
        // after the layer/mask section — import it as one layer so the file still opens. Only attempt
        // this when that section is actually present (some bytes remain); otherwise return no layers.
        if (c.pos + 2 <= bytes.size) {
            try {
                return ImportedDocument(width, height, listOf(readMergedImage(c, width, height, channelCount)))
            } catch (_: IndexOutOfBoundsException) {
                // Malformed/truncated merged section — fall through to an empty document.
            }
        }
        return ImportedDocument(width, height, emptyList())
    }

    // ------------------------------------------------------------------------------------------
    // Layer section
    // ------------------------------------------------------------------------------------------

    private fun readLayers(c: Cursor, layerMaskEnd: Int, docW: Int, docH: Int): List<ImportedLayer> {
        val layerInfoLen = c.u32().toInt()
        if (layerInfoLen <= 0) return emptyList()
        val layerInfoEnd = c.pos + layerInfoLen

        var count = c.i16()
        if (count < 0) count = -count // negative = first alpha is the merged transparency

        val records = ArrayList<LayerRecord>(count)
        repeat(count) { records += readLayerRecord(c) }

        // Channel image data follows all records, in record order.
        val out = ArrayList<ImportedLayer>(records.size)
        for (rec in records) {
            val layer = readLayerChannels(c, rec, docW, docH)
            if (layer != null) out += layer
        }

        c.pos = layerInfoEnd.coerceAtMost(layerMaskEnd)
        return out
    }

    private fun readLayerRecord(c: Cursor): LayerRecord {
        val top = c.i32()
        val left = c.i32()
        val bottom = c.i32()
        val right = c.i32()
        val numChannels = c.u16()
        val channels = ArrayList<ChannelInfo>(numChannels)
        repeat(numChannels) {
            val id = c.i16()
            val len = c.u32().toInt()
            channels += ChannelInfo(id, len)
        }
        if (c.u32() != SIGNATURE_8BIM) throw PsdFormatException("Bad layer blend signature")
        val blendKey = c.ascii(4)
        val opacity = c.u8()
        c.u8() // clipping
        val flags = c.u8()
        c.u8() // filler

        val extraLen = c.u32().toInt()
        val extraEnd = c.pos + extraLen
        c.skip(c.u32().toInt()) // layer mask / adjustment data
        c.skip(c.u32().toInt()) // layer blending ranges

        // Pascal name: 1 length byte + bytes, padded so the whole thing is a multiple of 4.
        val nameLen = c.u8()
        val pascalName = c.ascii(nameLen)
        val pad = (4 - ((nameLen + 1) % 4)) % 4
        c.skip(pad)

        // Additional layer info blocks — scan for the Unicode name ("luni", supersedes the legacy
        // Pascal name) and the vector mask ("vmsk"/"vsms", the editable shape outline). Rest skipped.
        var unicodeName: String? = null
        var vectorMask: ByteArray? = null
        while (c.pos + 12 <= extraEnd) {
            val sig = c.u32()
            if (sig != SIGNATURE_8BIM) { c.pos -= 4; break }
            val key = c.ascii(4)
            val len = c.u32().toInt()
            // A negative declared length (e.g. from a corrupted/hostile file) would otherwise send
            // blockEnd backward past the position we just read sig/key/len from, re-parsing the same
            // bytes forever. Bail out of the scan rather than loop indefinitely.
            if (len < 0) break
            val blockEnd = c.pos + len + (len and 1) // padded to even
            when (key) {
                "luni" -> unicodeName = readUnicodeName(c)
                "vmsk", "vsms" -> vectorMask =
                    c.bytes.copyOfRange(c.pos, (c.pos + len).coerceIn(c.pos, c.bytes.size))
            }
            c.pos = blockEnd.coerceAtMost(extraEnd)
        }
        c.pos = extraEnd

        val name = unicodeName?.takeIf { it.isNotBlank() } ?: pascalName
        return LayerRecord(
            name = name,
            top = top, left = left, bottom = bottom, right = right,
            channels = channels,
            opacity = opacity / 255f,
            isVisible = (flags and 0x02) == 0, // bit 1 set = hidden
            blendMode = blendKeyToMode(blendKey),
            vectorMaskBytes = vectorMask,
        )
    }

    /** Reads a "luni" block: a 4-byte UTF-16 unit count followed by that many big-endian chars. */
    private fun readUnicodeName(c: Cursor): String {
        val chars = c.u32().toInt()
        // Clamp before allocating too: the raw (attacker-controlled) count was previously only
        // clamped for the read loop, but StringBuilder(chars) itself was sized off the unclamped
        // value — a declared length like 0x7FFFFFFF attempted a multi-gigabyte allocation and
        // threw an uncaught OutOfMemoryError.
        val clamped = chars.coerceIn(0, 4096)
        val sb = StringBuilder(clamped)
        repeat(clamped) { sb.append(c.u16().toChar()) }
        return sb.toString().trimEnd(' ')
    }

    /** Decodes one layer's channel planes and composes them into an [ImportedLayer], or null if the
     *  record has no pixels (a group divider or empty layer). */
    private fun readLayerChannels(c: Cursor, rec: LayerRecord, docW: Int, docH: Int): ImportedLayer? {
        // Long arithmetic: unlike the document canvas, a layer's top/left/bottom/right are raw,
        // unvalidated i32 fields from the file. A crafted pair (e.g. right=1_500_000_000,
        // left=-1_500_000_000) would silently overflow the plain Int subtraction into a bogus w/h
        // that then fed ByteArray(w*h) below — either a NegativeArraySizeException or an OOM,
        // bypassing the MAX_DIMENSION guard that the document-level size check already enforces.
        val wLong = rec.right.toLong() - rec.left.toLong()
        val hLong = rec.bottom.toLong() - rec.top.toLong()
        if (wLong <= 0 || hLong <= 0 || wLong > MAX_DIMENSION || hLong > MAX_DIMENSION) {
            // Still consume this record's (near-empty) channel data to stay aligned.
            for (ch in rec.channels) c.skip(ch.length)
            return null
        }
        val w = wLong.toInt()
        val h = hLong.toInt()

        val planes = HashMap<Int, ByteArray>(rec.channels.size)
        for (ch in rec.channels) {
            val chEnd = c.pos + ch.length
            planes[ch.id] = readChannelPlane(c, w, h)
            c.pos = chEnd // trust the declared length for alignment
        }

        val red = planes[0]
        val green = planes[1]
        val blue = planes[2]
        val alpha = planes[-1]
        val argb = newIntArray(w * h)
        for (i in argb.indices) {
            val r = red?.get(i)?.toInt()?.and(0xFF) ?: 0
            val g = green?.get(i)?.toInt()?.and(0xFF) ?: 0
            val b = blue?.get(i)?.toInt()?.and(0xFF) ?: 0
            val a = alpha?.get(i)?.toInt()?.and(0xFF) ?: 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val vectorPaths = rec.vectorMaskBytes?.let { PsdVectorMask.parse(it, docW, docH) } ?: emptyList()
        return ImportedLayer(
            name = rec.name,
            argb = argb, width = w, height = h,
            left = rec.left, top = rec.top,
            opacity = rec.opacity, isVisible = rec.isVisible, blendMode = rec.blendMode,
            vectorPaths = vectorPaths,
        )
    }

    /** Reads a single `w*h` channel plane (compression flag + data) into a byte array. */
    private fun readChannelPlane(c: Cursor, w: Int, h: Int): ByteArray {
        val plane = newByteArray(w * h)
        when (val compression = c.u16()) {
            0 -> c.readInto(plane, 0, w * h) // raw
            1 -> decodeRleChannel(c, plane, w, h)
            else -> throw PsdFormatException("Unsupported channel compression $compression")
        }
        return plane
    }

    /** RLE channel: an `h`-entry scanline byte-count table, then PackBits rows of `w` bytes each. */
    private fun decodeRleChannel(c: Cursor, dst: ByteArray, w: Int, h: Int) {
        val rowBytes = IntArray(h) { c.u16() }
        for (row in 0 until h) {
            PackBits.decode(c.bytes, c.pos, dst, row * w, w)
            c.pos += rowBytes[row]
        }
    }

    // ------------------------------------------------------------------------------------------
    // Merged (flattened) image fallback
    // ------------------------------------------------------------------------------------------

    private fun readMergedImage(c: Cursor, w: Int, h: Int, channels: Int): ImportedLayer {
        val n = channels.coerceIn(1, 4)
        val planes = Array(n) { newByteArray(w * h) }
        when (val compression = c.u16()) {
            0 -> for (p in planes) c.readInto(p, 0, w * h)
            1 -> {
                // All scanline counts for all channels come first, then all packed data.
                val counts = IntArray(n * h) { c.u16() }
                for (ci in 0 until n) {
                    val plane = planes[ci]
                    for (row in 0 until h) {
                        PackBits.decode(c.bytes, c.pos, plane, row * w, w)
                        c.pos += counts[ci * h + row]
                    }
                }
            }
            else -> throw PsdFormatException("Unsupported merged compression $compression")
        }
        val argb = newIntArray(w * h)
        for (i in argb.indices) {
            val r = planes.getOrNull(0)?.get(i)?.toInt()?.and(0xFF) ?: 0
            val g = planes.getOrNull(1)?.get(i)?.toInt()?.and(0xFF) ?: r
            val b = planes.getOrNull(2)?.get(i)?.toInt()?.and(0xFF) ?: r
            val a = if (n >= 4) planes[3][i].toInt() and 0xFF else 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return ImportedLayer("Flattened", argb, w, h, 0, 0, 1f, true, BlendMode.SrcOver)
    }

    // ------------------------------------------------------------------------------------------
    // Blend-mode keys
    // ------------------------------------------------------------------------------------------

    /** Maps a Photoshop 4-char blend key to the nearest editor [BlendMode]; unknown → SrcOver. */
    internal fun blendKeyToMode(key: String): BlendMode = when (key.trimEnd()) {
        "norm", "pass", "diss" -> BlendMode.SrcOver
        "mul" -> BlendMode.Multiply
        "scrn" -> BlendMode.Screen
        "over" -> BlendMode.Overlay
        "dark" -> BlendMode.Darken
        "lite" -> BlendMode.Lighten
        "dkCl" -> BlendMode.Darken
        "lgCl" -> BlendMode.Lighten
        "div" -> BlendMode.ColorDodge
        "idiv" -> BlendMode.ColorBurn
        "lddg" -> BlendMode.Plus     // linear dodge (add)
        "lbrn" -> BlendMode.ColorBurn // linear burn (approx)
        "hLit" -> BlendMode.HardLight
        "sLit" -> BlendMode.SoftLight
        "vLit", "lLit", "pLit", "hMix" -> BlendMode.Overlay // vivid/linear/pin/hard-mix ≈ overlay
        "diff" -> BlendMode.Difference
        "smud" -> BlendMode.Exclusion
        "hue" -> BlendMode.Hue
        "sat" -> BlendMode.Saturation
        "colr" -> BlendMode.Color
        "lum" -> BlendMode.Luminosity
        else -> BlendMode.SrcOver
    }

    // ------------------------------------------------------------------------------------------

    private class ChannelInfo(val id: Int, val length: Int)

    private class LayerRecord(
        val name: String,
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val channels: List<ChannelInfo>,
        val opacity: Float,
        val isVisible: Boolean,
        val blendMode: BlendMode,
        val vectorMaskBytes: ByteArray? = null,
    )

    /** Big-endian byte cursor over a PSD buffer. All multi-byte reads advance [pos]. */
    private class Cursor(val bytes: ByteArray) {
        var pos: Int = 0

        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun i16(): Int = u16().toShort().toInt()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun i32(): Int = (u16() shl 16) or u16()

        // A negative or oversized `n` (declared lengths straight from the file, e.g. "colour mode
        // data"/"image resources" section lengths) used to be applied with no bounds check, silently
        // pushing `pos` outside [0, bytes.size]; the *next* read would then throw a raw
        // ArrayIndexOutOfBoundsException instead of the documented PsdFormatException. Validate here,
        // at the point of the bad length, so the failure is immediate and typed.
        fun skip(n: Int) {
            val newPos = pos + n
            if (n < 0 || newPos < 0 || newPos > bytes.size) {
                throw PsdFormatException("Corrupt PSD: skip($n) at pos=$pos exceeds buffer (${bytes.size} bytes)")
            }
            pos = newPos
        }

        fun ascii(n: Int): String {
            val s = String(bytes, pos, n, Charsets.ISO_8859_1)
            pos += n
            return s
        }

        fun readInto(dst: ByteArray, dstOffset: Int, n: Int) {
            System.arraycopy(bytes, pos, dst, dstOffset, n)
            pos += n
        }
    }
}
