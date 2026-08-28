package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirtyRegionTest {

    private fun dab(x: Float, y: Float, radius: Float, mask: MaskDab? = null) = Dab(
        x = x, y = y, radius = radius, alpha = 1f, angleDeg = 0f, mask = mask,
    )

    @Test
    fun `fromDabs is null for an empty list`() {
        assertNull(DirtyRegion.fromDabs(emptyList()))
    }

    @Test
    fun `fromDabs bounds a single dab by its radius`() {
        val region = DirtyRegion.fromDabs(listOf(dab(x = 10f, y = 20f, radius = 5f)))

        assertEquals(DirtyRegion(5, 15, 15, 25), region)
    }

    @Test
    fun `fromDabs unions the bounds of multiple dabs`() {
        val region = DirtyRegion.fromDabs(
            listOf(
                dab(x = 10f, y = 10f, radius = 2f),
                dab(x = 100f, y = 50f, radius = 3f),
            ),
        )

        assertEquals(DirtyRegion(8, 8, 103, 53), region)
    }

    @Test
    fun `fromDabs includes a secondary masked tip's extent`() {
        val mask = MaskDab(x = 200f, y = 5f, radius = 10f, tipRatio = 1f, alpha = 1f, angleDeg = 0f)
        val region = DirtyRegion.fromDabs(listOf(dab(x = 10f, y = 10f, radius = 2f, mask = mask)))

        // The primary dab alone would bound to [8,8,12,12) -- the mask tip at x=200,y=5,r=10
        // (bounds [190,210) x [-5,15)) must widen the union to cover both.
        assertEquals(DirtyRegion(8, -5, 210, 15), region)
    }

    @Test
    fun `union combines two regions`() {
        val a = DirtyRegion(0, 0, 10, 10)
        val b = DirtyRegion(5, -5, 20, 8)

        assertEquals(DirtyRegion(0, -5, 20, 10), a.union(b))
    }

    @Test
    fun `clampTo intersects with the canvas bounds`() {
        val region = DirtyRegion(-5, -5, 15, 15)

        assertEquals(DirtyRegion(0, 0, 10, 10), region.clampTo(10, 10))
    }

    @Test
    fun `clampTo can produce an empty region when entirely outside the canvas`() {
        val region = DirtyRegion(-20, -20, -5, -5)

        val clamped = region.clampTo(10, 10)

        assertTrue(clamped.isEmpty)
    }

    @Test
    fun `width and height are derived from the bounds`() {
        val region = DirtyRegion(5, 5, 25, 15)

        assertEquals(20, region.width)
        assertEquals(10, region.height)
        assertTrue(!region.isEmpty)
    }

    @Test
    fun `fromPixelDiff is null when the two buffers are identical`() {
        val w = 8; val h = 8
        val a = IntArray(w * h) { 0xFF000000.toInt() }
        val b = a.copyOf()

        assertNull(DirtyRegion.fromPixelDiff(a, b, w, h))
    }

    @Test
    fun `fromPixelDiff bounds exactly the differing pixels`() {
        val w = 10; val h = 10
        val before = IntArray(w * h) { 0 }
        val after = before.copyOf()
        // A single differing pixel at (3,4) and another at (6,7) -- the bounding box of both.
        after[4 * w + 3] = 1
        after[7 * w + 6] = 1

        val region = DirtyRegion.fromPixelDiff(before, after, w, h)

        assertEquals(DirtyRegion(3, 4, 7, 8), region)
    }

    @Test
    fun `fromPixelDiff is null for a size mismatch instead of crashing`() {
        val w = 8; val h = 8
        val before = IntArray(w * h)
        val after = IntArray(4) // too small

        assertNull(DirtyRegion.fromPixelDiff(before, after, w, h))
    }

    @Test
    fun `fromPixelDiff is null for non-positive dimensions`() {
        assertNull(DirtyRegion.fromPixelDiff(IntArray(0), IntArray(0), 0, 0))
    }
}
