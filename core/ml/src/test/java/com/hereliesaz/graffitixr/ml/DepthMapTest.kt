package com.hereliesaz.graffitixr.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DepthMapTest {
    @Test fun `normalized maps the range onto 0 to 1 with near as 1`() {
        val m = DepthMap(2, 2, floatArrayOf(200f, 400f, 600f, 1000f)).normalized()
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.5f, 1f), m.values, 1e-6f)
    }

    @Test fun `normalized of a flat map is all zero rather than NaN`() {
        val m = DepthMap(2, 1, floatArrayOf(7f, 7f)).normalized()
        assertArrayEquals(floatArrayOf(0f, 0f), m.values, 0f)
    }

    @Test fun `normalized ignores non-finite samples and zeroes them`() {
        val m = DepthMap(3, 1, floatArrayOf(1f, Float.NaN, 3f)).normalized()
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), m.values, 1e-6f)
    }

    @Test fun `normalized does not mutate the source`() {
        val raw = floatArrayOf(5f, 10f)
        DepthMap(2, 1, raw).normalized()
        assertArrayEquals(floatArrayOf(5f, 10f), raw, 0f)
    }

    @Test fun `resampling to the same size is the identity`() {
        val m = DepthMap(2, 2, floatArrayOf(0f, 1f, 2f, 3f))
        assertSame(m, m.resampledTo(2, 2))
    }

    @Test fun `upsampling interpolates and keeps the extremes`() {
        val up = DepthMap(2, 1, floatArrayOf(0f, 1f)).resampledTo(4, 1)
        assertEquals(4, up.width)
        assertEquals(0f, up[0, 0], 1e-6f)
        assertEquals(1f, up[3, 0], 1e-6f)
        assertEquals(0.25f, up[1, 0], 1e-6f)
        assertEquals(0.75f, up[2, 0], 1e-6f)
    }

    @Test fun `downsampling stays inside the source range`() {
        val src = DepthMap(4, 4, FloatArray(16) { it / 15f })
        val down = src.resampledTo(2, 2)
        assertEquals(4, down.values.size)
        assertEquals(true, down.values.all { it in 0f..1f })
    }

    @Test fun `resampling a different aspect works`() {
        val out = DepthMap(3, 2, FloatArray(6) { it.toFloat() }).resampledTo(7, 5)
        assertEquals(7, out.width)
        assertEquals(5, out.height)
        assertEquals(35, out.values.size)
    }

    @Test fun `size mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DepthMap(2, 2, FloatArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { DepthMap(0, 2, FloatArray(0)) }
    }

    @Test fun `grayscale maps near to white and far to black`() {
        val argb = DepthMap(2, 1, floatArrayOf(0f, 1f)).toGrayscaleArgb()
        assertEquals(0xFF000000.toInt(), argb[0])
        assertEquals(0xFFFFFFFF.toInt(), argb[1])
    }
}
