// FILE: core/common/src/test/java/com/hereliesaz/graffitixr/common/importer/PsdReaderTest.kt
package com.hereliesaz.graffitixr.common.importer

import com.hereliesaz.graffitixr.common.model.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import org.junit.Test

/**
 * Exercises [PsdReader] against hand-built PSD byte streams (8-bit RGB, version 1) covering the raw
 * and RLE channel paths, layer metadata, the merged-image fallback, and format rejection. Building
 * the bytes in-test keeps the parser honest without checking a binary fixture into the repo.
 */
class PsdReaderTest {

    // ---- little-language for assembling big-endian PSD bytes ------------------------------------

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun ByteArrayOutputStream.w(b: ByteArray) = write(b)

    private fun header(width: Int, height: Int, channels: Int = 3, depth: Int = 8, colorMode: Int = 3) =
        ByteArrayOutputStream().apply {
            w(ascii("8BPS")); w(be16(1)); w(ByteArray(6))
            w(be16(channels)); w(be32(height)); w(be32(width)); w(be16(depth)); w(be16(colorMode))
        }.toByteArray()

    /** A Pascal (length-prefixed, 4-byte-padded) layer name. */
    private fun pascalName(name: String): ByteArray {
        val b = ascii(name)
        val out = ByteArrayOutputStream()
        out.write(b.size); out.w(b)
        val pad = (4 - ((b.size + 1) % 4)) % 4
        out.w(ByteArray(pad))
        return out.toByteArray()
    }

    /** A layer record (metadata only; channel image data is appended separately, in record order). */
    private fun layerRecord(
        name: String, top: Int, left: Int, bottom: Int, right: Int,
        channelIds: List<Int>, channelLen: Int, blendKey: String = "norm",
        opacity: Int = 255, flags: Int = 0,
    ): ByteArray = ByteArrayOutputStream().apply {
        w(be32(top)); w(be32(left)); w(be32(bottom)); w(be32(right))
        w(be16(channelIds.size))
        channelIds.forEach { w(be16(it)); w(be32(channelLen)) }
        w(ascii("8BIM")); w(ascii(blendKey.padEnd(4)))
        write(opacity); write(0); write(flags); write(0)
        val name4 = pascalName(name)
        val extra = ByteArrayOutputStream().apply {
            w(be32(0)) // layer mask data
            w(be32(0)) // blending ranges
            w(name4)
        }.toByteArray()
        w(be32(extra.size)); w(extra)
    }.toByteArray()

    /** Raw-compressed channel image data: compression flag 0 followed by the plane bytes. */
    private fun rawChannel(plane: ByteArray) = ByteArrayOutputStream().apply {
        w(be16(0)); w(plane)
    }.toByteArray()

    /** Assembles a full PSD from a layer section (its layer-info body) — no merged image needed. */
    private fun psdWithLayers(width: Int, height: Int, layerCount: Int, layerInfoBody: ByteArray): ByteArray {
        val layerInfo = ByteArrayOutputStream().apply { w(be16(layerCount)); w(layerInfoBody) }.toByteArray()
        val padded = if (layerInfo.size % 2 == 1) layerInfo + byteArrayOf(0) else layerInfo
        val layerMask = ByteArrayOutputStream().apply { w(be32(padded.size)); w(padded) }.toByteArray()
        return ByteArrayOutputStream().apply {
            w(header(width, height))
            w(be32(0)) // colour mode data
            w(be32(0)) // image resources
            w(be32(layerMask.size)); w(layerMask)
        }.toByteArray()
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    fun isPsd_detectsMagicAndVersion() {
        assertTrue(PsdReader.isPsd(header(4, 4)))
        assertFalse(PsdReader.isPsd(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0))) // PNG
    }

    @Test
    fun readsTwoRawLayers_withMetadataAndPixels() {
        val w = 2; val h = 2; val chLen = 2 + w * h // compression flag + raw plane
        // Layer 0 at (0,0), red-ish; Layer 1 at (1,1) offset, green-ish, 50% opacity, multiply.
        val rec0 = layerRecord("Background", 0, 0, h, w, listOf(0, 1, 2), chLen)
        val rec1 = layerRecord("Shape", 1, 1, 1 + h, 1 + w, listOf(0, 1, 2), chLen, "mul", opacity = 128)
        val ch0 = rawChannel(byteArrayOf(10, 20, 30, 40)) +      // R
            rawChannel(byteArrayOf(11, 21, 31, 41)) +            // G
            rawChannel(byteArrayOf(12, 22, 32, 42))              // B
        val ch1 = rawChannel(byteArrayOf(50, 60, 70, 80)) +
            rawChannel(byteArrayOf(51, 61, 71, 81)) +
            rawChannel(byteArrayOf(52, 62, 72, 82))
        val body = rec0 + rec1 + ch0 + ch1
        val doc = PsdReader.read(psdWithLayers(w, h, 2, body))

        assertEquals(2, doc.width); assertEquals(2, doc.height)
        assertEquals(2, doc.layers.size)

        val l0 = doc.layers[0]
        assertEquals("Background", l0.name)
        assertEquals(0, l0.left); assertEquals(0, l0.top)
        assertEquals(BlendMode.SrcOver, l0.blendMode)
        assertEquals(1f, l0.opacity, 1e-4f)
        // Pixel (0,0): a=255,r=10,g=11,b=12  (no alpha channel => opaque)
        assertEquals(0xFF0A0B0C.toInt(), l0.pixel(0, 0))
        // Pixel (1,1): r=40,g=41,b=42
        assertEquals(0xFF28292A.toInt(), l0.pixel(1, 1))

        val l1 = doc.layers[1]
        assertEquals("Shape", l1.name)
        assertEquals(1, l1.left); assertEquals(1, l1.top)
        assertEquals(BlendMode.Multiply, l1.blendMode)
        assertEquals(128 / 255f, l1.opacity, 1e-4f)
        assertEquals(0xFF323334.toInt(), l1.pixel(0, 0)) // r=50,g=51,b=52
    }

    @Test
    fun readsRleCompressedChannels() {
        val w = 2; val h = 2
        // RLE plane: per row a 2-byte literal run "[0x01][b0][b1]" => rowBytes=3, table=[3,3].
        fun rlePlane(a: Int, b: Int, cc: Int, d: Int): ByteArray = ByteArrayOutputStream().apply {
            w(be16(1))            // compression = RLE
            w(be16(3)); w(be16(3)) // scanline byte-count table (h entries)
            write(0x01); write(a); write(b) // row 0 literal
            write(0x01); write(cc); write(d) // row 1 literal
        }.toByteArray()
        val chLen = rlePlane(0, 0, 0, 0).size
        val rec = layerRecord("Rle", 0, 0, h, w, listOf(0, 1, 2), chLen)
        val body = rec +
            rlePlane(100, 101, 102, 103) + // R
            rlePlane(110, 111, 112, 113) + // G
            rlePlane(120, 121, 122, 123)   // B
        val doc = PsdReader.read(psdWithLayers(w, h, 1, body))

        val l = doc.layers.single()
        assertEquals(0xFF646E78.toInt(), l.pixel(0, 0)) // r=100(0x64),g=110(0x6E),b=120(0x78)
        assertEquals(0xFF67717B.toInt(), l.pixel(1, 1)) // r=103(0x67),g=113(0x71),b=123(0x7B)
    }

    @Test
    fun mergedImageFallback_whenNoLayers() {
        val w = 2; val h = 2
        val bytes = ByteArrayOutputStream().apply {
            w(header(w, h))
            w(be32(0)); w(be32(0)) // colour mode data + image resources
            w(be32(0))             // layer-and-mask length 0 => no discrete layers
            w(be16(0))             // merged compression = raw
            w(byteArrayOf(1, 2, 3, 4))     // R plane
            w(byteArrayOf(5, 6, 7, 8))     // G plane
            w(byteArrayOf(9, 10, 11, 12))  // B plane
        }.toByteArray()
        val doc = PsdReader.read(bytes)
        val l = doc.layers.single()
        assertEquals("Flattened", l.name)
        assertEquals(0xFF010509.toInt(), l.pixel(0, 0))
        assertEquals(0xFF04080C.toInt(), l.pixel(1, 1))
    }

    @Test
    fun zeroAreaLayer_isSkipped() {
        // A group divider: right==left, bottom==top => no pixels. One channel, minimal data.
        val rec = layerRecord("</group>", 0, 0, 0, 0, listOf(0), channelLen = 2)
        val body = rec + rawChannel(ByteArray(0))
        val doc = PsdReader.read(psdWithLayers(4, 4, 1, body))
        assertTrue(doc.layers.isEmpty())
    }

    @Test
    fun rejectsUnsupportedDepth() {
        val bytes = ByteArrayOutputStream().apply {
            w(header(4, 4, depth = 16))
            w(be32(0)); w(be32(0)); w(be32(0))
        }.toByteArray()
        try {
            PsdReader.read(bytes); throw AssertionError("expected PsdFormatException")
        } catch (e: PsdFormatException) { /* expected */ }
    }

    @Test
    fun blendKeyMapping_coversCommonModes() {
        assertEquals(BlendMode.SrcOver, PsdReader.blendKeyToMode("norm"))
        assertEquals(BlendMode.Multiply, PsdReader.blendKeyToMode("mul "))
        assertEquals(BlendMode.Screen, PsdReader.blendKeyToMode("scrn"))
        assertEquals(BlendMode.Overlay, PsdReader.blendKeyToMode("over"))
        assertEquals(BlendMode.SoftLight, PsdReader.blendKeyToMode("sLit"))
        assertEquals(BlendMode.Plus, PsdReader.blendKeyToMode("lddg"))
        assertEquals(BlendMode.SrcOver, PsdReader.blendKeyToMode("????")) // unknown => normal
    }
}
