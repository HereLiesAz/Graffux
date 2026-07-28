package com.hereliesaz.graffitixr.common.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turning a drag across a model into dabs on its texture. The interesting case is the seam: two
 * points that are neighbours on the model can be opposite corners of the texture, and joining them
 * paints a stripe across parts of the model the user never touched.
 */
class TexturePaintTest {

    private val width = 1024
    private val height = 512

    // ── UV to pixel ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `uv maps across the full texture`() {
        assertEquals(0f, TexturePaint.toPixel(0f, 0f, width, height).x, 1e-3f)
        assertEquals(0f, TexturePaint.toPixel(0f, 0f, width, height).y, 1e-3f)
        val mid = TexturePaint.toPixel(0.5f, 0.5f, width, height)
        assertEquals(512f, mid.x, 1e-3f)
        assertEquals(256f, mid.y, 1e-3f)
    }

    @Test
    fun `a non-square texture uses each axis's own size`() {
        // Using one dimension for both is the classic version of this bug, and on a 2:1 texture it
        // puts every dab at half the height it belongs at.
        val p = TexturePaint.toPixel(1f - 1e-6f, 1f - 1e-6f, width, height)
        assertTrue("x=${p.x}", p.x > width * 0.99f && p.x < width)
        assertTrue("y=${p.y}", p.y > height * 0.99f && p.y < height)
    }

    @Test
    fun `uvs outside the unit square wrap rather than clamping`() {
        // A map that tiles its texture is ordinary; clamping would pile every repeat into the edge.
        assertEquals(
            TexturePaint.toPixel(0.25f, 0.25f, width, height),
            TexturePaint.toPixel(1.25f, 3.25f, width, height),
        )
    }

    @Test
    fun `negative uvs wrap by flooring, not truncating`() {
        // Truncation would map -0.25 to 0.25 instead of 0.75, mirroring the paint.
        assertEquals(
            TexturePaint.toPixel(0.75f, 0.75f, width, height),
            TexturePaint.toPixel(-0.25f, -0.25f, width, height),
        )
    }

    @Test
    fun `a wrapped uv never lands one texel past the edge`() {
        // A tiny negative floors to something that rounds to exactly 1f, which would index out of
        // the bitmap the moment a caller truncates it to an int.
        val p = TexturePaint.toPixel(-1e-9f, -1e-9f, width, height)
        assertTrue("x=${p.x}", p.x >= 0f && p.x < width)
        assertTrue("y=${p.y}", p.y >= 0f && p.y < height)
    }

    @Test
    fun `a non-finite uv is treated as the origin rather than producing NaN pixels`() {
        // A degenerate triangle can hand back a NaN barycentric weight; a NaN dab centre would then
        // silently paint nothing forever after.
        val p = TexturePaint.toPixel(Float.NaN, Float.POSITIVE_INFINITY, width, height)
        assertTrue(p.x.isFinite() && p.y.isFinite())
    }

    // ── Seams ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a small step across the surface is not a seam`() {
        assertFalse(TexturePaint.isSeamJump(0.50f, 0.50f, 0.52f, 0.51f))
    }

    @Test
    fun `a jump across the texture is a seam`() {
        assertTrue(TexturePaint.isSeamJump(0.05f, 0.5f, 0.95f, 0.5f))
        assertTrue(TexturePaint.isSeamJump(0.5f, 0.05f, 0.5f, 0.95f))
    }

    @Test
    fun `crossing a seam paints where the finger is and nothing in between`() {
        val stamps = TexturePaint.stamps(0.05f, 0.5f, 0.95f, 0.5f, width, height, spacingPx = 4f)
        assertEquals("should not have interpolated across the seam", 1, stamps.size)
        assertEquals(0.95f * width, stamps.first().x, 1f)
    }

    // ── Filling in a stroke ──────────────────────────────────────────────────────────────────

    @Test
    fun `a stroke is filled in densely enough to be continuous`() {
        // Painting only the picked points leaves a dotted trail whenever the finger outruns the
        // frame rate — the whole reason this exists.
        val stamps = TexturePaint.stamps(0.1f, 0.5f, 0.2f, 0.5f, width, height, spacingPx = 4f)
        assertTrue("only ${stamps.size} stamps over ~102px", stamps.size >= 25)
        stamps.zipWithNext { a, b ->
            assertTrue("gap of ${hypot(b.x - a.x, b.y - a.y)}", hypot(b.x - a.x, b.y - a.y) <= 4.5f)
        }
    }

    @Test
    fun `a stroke ends under the finger`() {
        val stamps = TexturePaint.stamps(0.1f, 0.2f, 0.3f, 0.4f, width, height, spacingPx = 6f)
        val last = stamps.last()
        assertEquals(0.3f * width, last.x, 0.5f)
        assertEquals(0.4f * height, last.y, 0.5f)
    }

    @Test
    fun `a stroke does not re-stamp its own start`() {
        // The previous segment already painted that point. Stamping it twice doubles the paint at
        // every joint, which a soft or low-opacity brush shows as a string of darker beads.
        val stamps = TexturePaint.stamps(0.1f, 0.5f, 0.2f, 0.5f, width, height, spacingPx = 4f)
        val startX = 0.1f * width
        assertTrue("first stamp sat on the start", abs(stamps.first().x - startX) > 0.01f)
    }

    @Test
    fun `stamps stay on the straight line between the two points`() {
        val stamps = TexturePaint.stamps(0.1f, 0.1f, 0.3f, 0.3f, width, height, spacingPx = 8f)
        val x0 = 0.1f * width
        val y0 = 0.1f * height
        val dx = 0.2f * width
        val dy = 0.2f * height
        stamps.forEach { s ->
            // Cross product of (s - start) with the segment direction is zero on the line.
            val cross = (s.x - x0) * dy - (s.y - y0) * dx
            assertEquals("off the line at (${s.x}, ${s.y})", 0f, cross, 1f)
        }
    }

    @Test
    fun `a step shorter than the spacing is a single dab`() {
        val stamps = TexturePaint.stamps(0.5f, 0.5f, 0.5005f, 0.5f, width, height, spacingPx = 4f)
        assertEquals(1, stamps.size)
    }

    @Test
    fun `a stationary finger still paints`() {
        // Holding still is a legitimate way to build up paint with a soft brush; returning nothing
        // would make the brush appear to stop working when the hand stops moving.
        assertEquals(1, TexturePaint.stamps(0.5f, 0.5f, 0.5f, 0.5f, width, height, 4f).size)
    }

    @Test
    fun `a fine brush on a large texture cannot produce unbounded work`() {
        val stamps = TexturePaint.stamps(0.26f, 0.26f, 0.5f, 0.5f, 8192, 8192, spacingPx = 0.5f)
        assertTrue("produced ${stamps.size} stamps in one segment", stamps.size <= 512)
        // Even capped, it must still end under the finger.
        assertEquals(0.5f * 8192, stamps.last().x, 1f)
    }

    @Test
    fun `a zero or negative spacing does not divide by zero`() {
        val stamps = TexturePaint.stamps(0.1f, 0.1f, 0.2f, 0.2f, width, height, spacingPx = 0f)
        assertTrue(stamps.isNotEmpty())
        assertTrue(stamps.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun `a texture with no size paints nothing rather than crashing`() {
        assertTrue(TexturePaint.stamps(0.1f, 0.1f, 0.2f, 0.2f, 0, 0, 4f).isEmpty())
        assertTrue(TexturePaint.stamps(0.1f, 0.1f, 0.2f, 0.2f, 512, 0, 4f).isEmpty())
    }
}
