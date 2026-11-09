@file:Suppress("MagicNumber")

package com.hereliesaz.graffux.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompareTest {
    private fun solid(width: Int, height: Int, argb: Int) = IntArray(width * height) { argb }
    private val grey = 0xFF808080.toInt()

    @Test
    fun `identical images do not differ`() {
        val diff = ImageCompare.compare(solid(4, 4, grey), 4, 4, solid(4, 4, grey), 4, 4)
        assertEquals(0, diff.differingPixels)
        assertEquals(0, diff.maxChannelDelta)
        assertTrue(diff.matches(0.0))
    }

    @Test
    fun `a change within the channel tolerance is ignored but still reported as the largest delta`() {
        val actual = solid(4, 4, grey).also { it[5] = 0xFF828282.toInt() }
        val diff = ImageCompare.compare(solid(4, 4, grey), 4, 4, actual, 4, 4, channelTolerance = 2)
        assertEquals(0, diff.differingPixels)
        assertEquals(2, diff.maxChannelDelta)
    }

    @Test
    fun `a change beyond the tolerance in any single channel counts, alpha included`() {
        val expected = solid(4, 4, grey)
        for (changed in intArrayOf(0xFF8A8080.toInt(), 0xFF808A80.toInt(), 0xFF80808A.toInt(), 0xF0808080.toInt())) {
            val actual = expected.copyOf().also { it[0] = changed }
            val diff = ImageCompare.compare(expected, 4, 4, actual, 4, 4)
            assertEquals("pixel ${changed.toUInt().toString(16)}", 1, diff.differingPixels)
        }
    }

    @Test
    fun `the allowed ratio decides a match`() {
        val actual = solid(10, 10, grey).also { it[0] = 0xFF000000.toInt() }
        val diff = ImageCompare.compare(solid(10, 10, grey), 10, 10, actual, 10, 10)
        assertEquals(0.01, diff.differingRatio, 1e-9)
        assertTrue(diff.matches(0.01))
        assertFalse(diff.matches(0.005))
    }

    @Test
    fun `different sizes never match, whatever the ratio`() {
        val diff = ImageCompare.compare(solid(4, 4, grey), 4, 4, solid(4, 5, grey), 4, 5)
        assertTrue(diff.sizeMismatch)
        assertFalse(diff.matches(1.0))
    }

    @Test
    fun `the diff image marks exactly the differing pixels in red`() {
        val expected = solid(2, 2, grey)
        val actual = expected.copyOf().also { it[3] = 0xFF000000.toInt() }
        val image = ImageCompare.diffImage(expected, actual)
        assertEquals(0xFFFF0000.toInt(), image[3])
        assertTrue(image.take(3).all { it != 0xFFFF0000.toInt() })
    }
}
