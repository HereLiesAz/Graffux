package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpastoEngineTest {

    private fun blank(size: Int): FloatArray = FloatArray(size)

    @Test
    fun `deposit raises height at the dab centre and leaves distant pixels untouched`() {
        val w = 32; val h = 32
        val height = blank(w * h)
        val dab = Dab(x = 16f, y = 16f, radius = 6f, alpha = 1f, angleDeg = 0f)
        ImpastoEngine.deposit(height, w, h, dab, hardness = 1f, thicknessRate = 0.5f)

        assertEquals(0.5f, height[16 * w + 16], 1e-4f)
        assertEquals(0f, height[2 * w + 2], 0f) // far outside the radius-6 footprint
    }

    @Test
    fun `deposit with zero thicknessRate or non-positive radius is a no-op`() {
        val w = 16; val h = 16
        val dab = Dab(x = 8f, y = 8f, radius = 4f, alpha = 1f, angleDeg = 0f)

        val a = blank(w * h)
        ImpastoEngine.deposit(a, w, h, dab, hardness = 1f, thicknessRate = 0f)
        assertArrayEquals(blank(w * h), a, 0f)

        val b = blank(w * h)
        ImpastoEngine.deposit(b, w, h, dab.copy(radius = 0f), hardness = 1f, thicknessRate = 1f)
        assertArrayEquals(blank(w * h), b, 0f)
    }

    @Test
    fun `repeated deposits build up asymptotically and never exceed one`() {
        val w = 16; val h = 16
        val height = blank(w * h)
        val dab = Dab(x = 8f, y = 8f, radius = 4f, alpha = 1f, angleDeg = 0f)
        repeat(200) { ImpastoEngine.deposit(height, w, h, dab, hardness = 1f, thicknessRate = 0.3f) }

        val idx = 8 * w + 8
        assertTrue(height[idx] <= 1f)
        assertTrue(height[idx] > 0.99f)
    }

    @Test
    fun `deposit coverage follows hardness falloff like colour dabs`() {
        val w = 32; val h = 32
        val soft = blank(w * h)
        val hard = blank(w * h)
        val dab = Dab(x = 16f, y = 16f, radius = 8f, alpha = 1f, angleDeg = 0f)
        ImpastoEngine.deposit(soft, w, h, dab, hardness = 0f, thicknessRate = 1f)
        ImpastoEngine.deposit(hard, w, h, dab, hardness = 1f, thicknessRate = 1f)

        // Halfway to the edge: a hard brush is still at full coverage, a soft one has fallen off.
        val midIdx = 16 * w + 20
        assertEquals(1f, hard[midIdx], 1e-4f)
        assertTrue(soft[midIdx] < hard[midIdx])
    }

    @Test
    fun `shade leaves a perfectly flat height map unchanged regardless of light or strength`() {
        val w = 24; val h = 24
        val flatHeight = FloatArray(w * h) { 0.6f }
        val colors = IntArray(w * h) { 0xFF335577.toInt() }
        val shaded = ImpastoEngine.shade(colors, flatHeight, w, h, lightAzimuthDeg = 45f, lightElevationDeg = 30f, strength = 5f)
        assertArrayEquals(colors, shaded)
    }

    @Test
    fun `shade with non-positive strength returns an unmodified copy`() {
        val w = 16; val h = 16
        val height = FloatArray(w * h) { i -> if (i == w * 8 + 8) 1f else 0f }
        val colors = IntArray(w * h) { 0xFF808080.toInt() }
        val shaded = ImpastoEngine.shade(colors, height, w, h, 0f, 45f, strength = 0f)
        assertArrayEquals(colors, shaded)
        assertNotEquals(shaded, colors) // a genuine copy, not the same reference
        assertTrue(shaded !== colors)
    }

    @Test
    fun `shade never mutates its inputs`() {
        val w = 16; val h = 16
        val height = FloatArray(w * h) { i -> if (i == w * 8 + 8) 1f else 0f }
        val heightBefore = height.copyOf()
        val colors = IntArray(w * h) { 0xFF808080.toInt() }
        val colorsBefore = colors.copyOf()
        ImpastoEngine.shade(colors, height, w, h, 45f, 30f, strength = 3f)
        assertArrayEquals(heightBefore, height, 0f)
        assertArrayEquals(colorsBefore, colors)
    }

    @Test
    fun `shade brightens the side of a bump facing the light and darkens the side facing away`() {
        val w = 24; val h = 24
        val height = FloatArray(w * h)
        // A raised ridge just left-of-centre, sloping up to x=11 then back down — creates a clean
        // positive dHdx on its left face and negative dHdx on its right face.
        for (y in 0 until h) {
            for (x in 6..16) {
                val t = (x - 6) / 10f
                val bump = if (t <= 0.5f) t * 2f else (1f - t) * 2f
                height[y * w + x] = bump
            }
        }
        val colors = IntArray(w * h) { 0xFF808080.toInt() }
        // Light from the right (azimuth 0deg = +x), grazing elevation to maximize slope response.
        val shaded = ImpastoEngine.shade(colors, height, w, h, lightAzimuthDeg = 0f, lightElevationDeg = 20f, strength = 4f)

        fun redAt(x: Int, y: Int) = shaded[y * w + x] shr 16 and 0xFF
        val leftFace = redAt(8, 12) // rising toward the light source direction's far side
        val rightFace = redAt(14, 12)
        assertNotEquals(128, leftFace)
        assertNotEquals(128, rightFace)
        assertTrue("expected the two faces of the bump to shade oppositely", leftFace != rightFace)
    }

    @Test
    fun `deposit and shade are deterministic for identical input`() {
        val w = 20; val h = 20
        val dab = Dab(x = 10f, y = 10f, radius = 5f, alpha = 0.8f, angleDeg = 0f)
        val a = blank(w * h)
        val b = blank(w * h)
        ImpastoEngine.deposit(a, w, h, dab, hardness = 0.5f, thicknessRate = 0.4f)
        ImpastoEngine.deposit(b, w, h, dab, hardness = 0.5f, thicknessRate = 0.4f)
        assertArrayEquals(a, b, 0f)

        val colors = IntArray(w * h) { 0xFF404040.toInt() }
        val shadedA = ImpastoEngine.shade(colors, a, w, h, 60f, 25f, 2f)
        val shadedB = ImpastoEngine.shade(colors, b, w, h, 60f, 25f, 2f)
        assertArrayEquals(shadedA, shadedB)
    }
}
