package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {

    private val identity = """
        TITLE "Identity"
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    // Each corner value = 1 - identity corner → a colour inversion.
    private val invert = """
        LUT_3D_SIZE 2
        1 1 1
        0 1 1
        1 0 1
        0 0 1
        1 1 0
        0 1 0
        1 0 0
        0 0 0
    """.trimIndent()

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `identity lut leaves pixels unchanged`() {
        val lut = parseCubeLut(identity)
        for (c in intArrayOf(0, 64, 128, 200, 255)) {
            val p = argb(255, c, c / 2, 255 - c)
            val out = lut.applyPixel(p)
            assertEquals("R", (p ushr 16) and 0xFF, (out ushr 16) and 0xFF)
            assertEquals("G", (p ushr 8) and 0xFF, (out ushr 8) and 0xFF)
            assertEquals("B", p and 0xFF, out and 0xFF)
        }
    }

    @Test
    fun `invert lut inverts colour and preserves alpha`() {
        val lut = parseCubeLut(invert)
        val out = lut.applyPixel(argb(0xAB, 30, 90, 200))
        assertEquals(0xAB, (out ushr 24) and 0xFF)
        assertEquals(255 - 30, (out ushr 16) and 0xFF)
        assertEquals(255 - 90, (out ushr 8) and 0xFF)
        assertEquals(255 - 200, out and 0xFF)
    }

    @Test
    fun `applyPixels grades a whole array`() {
        val lut = parseCubeLut(invert)
        val px = intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255))
        lut.applyPixels(px)
        assertEquals(argb(255, 255, 255, 255), px[0])
        assertEquals(argb(255, 0, 0, 0), px[1])
    }

    @Test
    fun `strength blends dry-wet toward the identity in the sampling domain`() {
        val lut = parseCubeLut(invert)
        val p = argb(255, 128, 64, 200)
        // strength 1 = full grade; 0 = original; 0.5 = halfway (blend in the encoded/sampling domain).
        assertEquals(255 - 128, (lut.withStrength(1f).applyPixel(p) ushr 16) and 0xFF)
        assertEquals(128, (lut.withStrength(0f).applyPixel(p) ushr 16) and 0xFF)
        // B: original 200, full grade 55, midpoint ≈ 128.
        val mid = lut.withStrength(0.5f).applyPixel(p) and 0xFF
        assertTrue("mid=$mid", kotlin.math.abs(mid - 128) <= 2)
        // strength >= 1 returns the same instance (no needless table copy).
        assertTrue(lut.withStrength(1f) === lut)
    }

    @Test
    fun `inputTransfer roundtrips an identity lut for linear and log-c`() {
        val lut = parseCubeLut(identity)
        val p = argb(255, 128, 64, 200)
        for (t in listOf(LutInputTransfer.LINEAR, LutInputTransfer.LOG_C)) {
            val out = lut.withInputTransfer(t).applyPixel(p)
            // Converting into the transfer domain and back around an identity table returns the pixel.
            assertTrue(t.name, kotlin.math.abs(((out ushr 16) and 0xFF) - 128) <= 2)
            assertTrue(t.name, kotlin.math.abs(((out ushr 8) and 0xFF) - 64) <= 2)
            assertTrue(t.name, kotlin.math.abs((out and 0xFF) - 200) <= 2)
        }
    }

    @Test
    fun `inputTransfer wire mapping defaults to srgb`() {
        assertEquals(LutInputTransfer.SRGB, LutInputTransfer.fromWire(null))
        assertEquals(LutInputTransfer.SRGB, LutInputTransfer.fromWire("srgb"))
        assertEquals(LutInputTransfer.SRGB, LutInputTransfer.fromWire("weird-future-value"))
        assertEquals(LutInputTransfer.LINEAR, LutInputTransfer.fromWire("linear"))
        assertEquals(LutInputTransfer.LOG_C, LutInputTransfer.fromWire("log-c"))
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val lut = parseCubeLut("# a comment\n\nLUT_3D_SIZE 2\n" + "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1")
        assertEquals(2, lut.size)
    }

    @Test
    fun `1D lut is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) { parseCubeLut("LUT_1D_SIZE 4\n0 0 0") }
        assertTrue(e.message!!.contains("1D"))
    }

    @Test
    fun `wrong value count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseCubeLut("LUT_3D_SIZE 2\n0 0 0\n1 0 0") }
    }

    @Test
    fun `missing size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parseCubeLut("0 0 0\n1 1 1") }
    }
}
