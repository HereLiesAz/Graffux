package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSurfaceTest {
    @Test
    fun `portrait is 3 over 4 of the width by 2 over 3 of the height, centred`() {
        val r = DefaultSurface.rect(1000f, 1500f)!!
        assertEquals(750f, r.width, 0.01f)
        assertEquals(1000f, r.height, 0.01f)
        assertEquals(125f, r.x, 0.01f)
        assertEquals(250f, r.y, 0.01f)
    }

    @Test
    fun `landscape is 2 over 3 of the width by 3 over 4 of the height, centred`() {
        val r = DefaultSurface.rect(1500f, 1000f)!!
        assertEquals(1000f, r.width, 0.01f)
        assertEquals(750f, r.height, 0.01f)
        assertEquals(250f, r.x, 0.01f)
        assertEquals(125f, r.y, 0.01f)
    }

    @Test
    fun `either way it is 3 over 4 of the short side by 2 over 3 of the long side`() {
        for ((w, h) in listOf(900f to 2100f, 2100f to 900f)) {
            val r = DefaultSurface.rect(w, h)!!
            assertEquals(0.75f * minOf(w, h), minOf(r.width, r.height).let { if (w < h) r.width else r.height }, 0.01f)
            assertEquals(2f / 3f * maxOf(w, h), if (w < h) r.height else r.width, 0.01f)
        }
    }

    @Test
    fun `a square counts as portrait`() {
        val r = DefaultSurface.rect(600f, 600f)!!
        assertEquals(450f, r.width, 0.01f)
        assertEquals(400f, r.height, 0.01f)
    }

    @Test
    fun `degenerate sizes give nothing`() {
        assertNull(DefaultSurface.rect(0f, 100f))
        assertNull(DefaultSurface.rect(100f, 0f))
        assertNull(DefaultSurface.rect(-1f, 100f))
        assertNull(DefaultSurface.rect(Float.NaN, 100f))
        assertNull(DefaultSurface.rect(Float.POSITIVE_INFINITY, 100f))
        assertNull(DefaultSurface.profile(0f, 0f, emptyList()))
    }

    @Test
    fun `the profile is a valid flat sheet in the plane z = 0`() {
        val p = DefaultSurface.profile(1000f, 1500f, emptyList())
        assertNotNull(p)
        p!!
        assertTrue(p.isValid && p.isFlat)
        assertEquals(125f, p.point(0)[0], 0.01f)
        assertEquals(875f, p.point(1)[0], 0.01f)
        assertEquals(0f, p.point(0)[2], 0f)
        assertEquals(0f, p.point(1)[2], 0f)
        assertEquals(1f, p.sweepY, 1e-4f)
    }
}
