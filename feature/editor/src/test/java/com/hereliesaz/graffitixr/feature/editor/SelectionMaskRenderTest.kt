package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SelectionMask] against real `android.graphics`.
 *
 * This is the tier that was missing. `Path.op` and `Canvas` were stubbed to no-ops by
 * `isReturnDefaultValues`, so nothing could tell whether the ring stack actually rasterised to the
 * region the pure-Kotlin [SelectionGeometry] says it does — which is the single invariant the whole
 * selection design rests on, and which was quietly violated by a fill-rule mismatch until an audit
 * read the code.
 */
@RunWith(RobolectricTestRunner::class)
// Real pixels. Robolectric's default graphics mode makes every Canvas draw a NO-OP, so without
// this every assertion below would pass or fail for reasons having nothing to do with the code
// under test — the same shape of blindness that let the stubbed suite miss all of this.
//
// The SDK pin is load-bearing, not tidiness. Native graphics needs API 26+, and Robolectric
// otherwise picks this module's minSdk (23) and falls back to the no-op canvas SILENTLY: the tests
// still run, still report green or red, and prove nothing either way.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SelectionMaskRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val canvas = IntSize(40, 40)

    private fun ring(a: Offset, b: Offset, additive: Boolean = true) =
        SelectionRing(SelectionGeometry.rectangle(a, b), additive)

    /** True when the rasterised clip actually covers the bitmap pixel at ([x], [y]). */
    private fun clipCovers(selection: Selection, x: Int, y: Int): Boolean {
        val path = SelectionMask.bitmapPath(selection, 40, 40) ?: return false
        val bmp = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        val c = android.graphics.Canvas(bmp)
        c.clipPath(path)
        c.drawColor(Color.RED)
        return Color.alpha(bmp.getPixel(x, y)) > 0
    }

    // ── The invariant: what the clip covers is what contains() says ─────────────────────────────

    @Test
    fun `a single ring rasterises to the region contains reports`() {
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas)
        for (p in listOf(20 to 20, 5 to 5, 35 to 20, 20 to 35)) {
            assertEquals(
                "at ${p.first},${p.second}",
                SelectionGeometry.contains(sel, Offset(p.first + 0.5f, p.second + 0.5f)),
                clipCovers(sel, p.first, p.second),
            )
        }
    }

    @Test
    fun `an added ring unions in the rasteriser as well as the hit-test`() {
        val sel = Selection(
            listOf(ring(Offset(2f, 2f), Offset(14f, 14f)), ring(Offset(24f, 2f), Offset(36f, 14f))),
            canvas,
        )
        assertTrue(clipCovers(sel, 8, 8))
        assertTrue(clipCovers(sel, 30, 8))
        assertFalse(clipCovers(sel, 19, 8)) // the gap between them
    }

    @Test
    fun `a removed ring cuts a real hole in the rasterised clip`() {
        val sel = Selection(
            listOf(
                ring(Offset(4f, 4f), Offset(36f, 36f)),
                ring(Offset(14f, 14f), Offset(26f, 26f), additive = false),
            ),
            canvas,
        )
        assertTrue(clipCovers(sel, 8, 8))
        assertFalse(clipCovers(sel, 20, 20))
    }

    @Test
    fun `inversion rasterises to the complement`() {
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas, inverted = true)
        assertFalse(clipCovers(sel, 20, 20))
        assertTrue(clipCovers(sel, 2, 2))
    }

    @Test
    fun `a doubly-wound lasso fills the same way the hit-test reads it`() {
        // The fill-rule bug, and the shape that actually exposes it. `Path` defaults to non-zero
        // winding while insidePolygon is even-odd, and the two only disagree where a region is
        // enclosed an EVEN number of times — a figure-eight isn't enough, since each of its lobes
        // is wound once. A pentagram's core is wound twice: winding paints it, even-odd does not.
        //
        // A lasso that loops back over itself produces exactly this, and before the fix the core
        // was painted but reported outside — so tapping the middle of your own selection started a
        // new one instead of moving it.
        val pentagram = SelectionRing(
            listOf(
                Offset(20f, 4f), Offset(29.4f, 32.9f), Offset(4.8f, 15.1f),
                Offset(35.2f, 15.1f), Offset(10.6f, 32.9f),
            )
        )
        val sel = Selection(listOf(pentagram), canvas)
        // The core, then a point in one of the arms, then well outside.
        for (p in listOf(20 to 20, 20 to 10, 2 to 2)) {
            assertEquals(
                "at ${p.first},${p.second}",
                SelectionGeometry.contains(sel, Offset(p.first + 0.5f, p.second + 0.5f)),
                clipCovers(sel, p.first, p.second),
            )
        }
        // And state the direction explicitly, so the test still means something if `contains`
        // itself ever changes: even-odd leaves the twice-enclosed core out.
        assertFalse(clipCovers(sel, 20, 20))
    }

    @Test
    fun `a stack that only subtracts produces no clip at all`() {
        // Not an empty clip — null, meaning "unclipped". An empty Path would confine every
        // subsequent stroke to nothing, silently.
        val sel = Selection(listOf(ring(Offset(4f, 4f), Offset(20f, 20f), additive = false)), canvas)
        assertNull(SelectionMask.bitmapPath(sel, 40, 40))
    }

    // ── Feather ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `feather produces a ramp, not a second hard edge`() {
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas, featherPx = 6f)
        val path = SelectionMask.bitmapPath(sel, 40, 40)!!
        val radius = SelectionMask.featherRadius(sel, 40, 40)
        assertTrue("radius should be positive", radius > 0f)

        val base = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        val painted = RenderTestBase.filled(40, 40, Color.RED)
        val out = SelectionMask.feather(base, painted, path, radius)

        // Deep inside: full paint. Far outside: none. On the boundary: partial — which is the whole
        // point, and what a clip can never produce.
        assertEquals(255, RenderTestBase.alphaAt(out, 20, 20))
        assertEquals(0, RenderTestBase.alphaAt(out, 1, 1))
        val edge = RenderTestBase.alphaAt(out, 10, 20)
        assertTrue("edge alpha $edge should be partial", edge in 1..254)
    }

    @Test
    fun `feather at radius zero returns the painted bitmap untouched`() {
        val painted = RenderTestBase.filled(8, 8, Color.RED)
        val base = RenderTestBase.filled(8, 8, Color.TRANSPARENT)
        val sel = Selection(listOf(ring(Offset(1f, 1f), Offset(7f, 7f))), IntSize(8, 8))
        val path = SelectionMask.bitmapPath(sel, 8, 8)
        assertEquals(painted, SelectionMask.feather(base, painted, path, 0f))
    }

    // ── Ownership of the intermediate ──────────────────────────────────────────────────────────
    //
    // Every call site allocates `painted` a few lines above the call and returns the result, so the
    // superseded buffer has nowhere to be freed except here. It used to be dropped instead: on a
    // feathered selection, every stroke, fill, clear and flood left a full-resolution layer copy
    // behind, on a heap that already holds two bitmaps per layer.

    @Test
    fun `feather recycles the painted intermediate it supersedes`() {
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas, featherPx = 6f)
        val path = SelectionMask.bitmapPath(sel, 40, 40)!!
        val radius = SelectionMask.featherRadius(sel, 40, 40)

        val base = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        val painted = RenderTestBase.filled(40, 40, Color.RED)
        val out = SelectionMask.feather(base, painted, path, radius)

        assertNotEquals(out, painted)
        assertTrue("the superseded intermediate must not be left for the collector", painted.isRecycled)
        assertFalse("the caller still owns base", base.isRecycled)
        assertFalse(out.isRecycled)
    }

    @Test
    fun `feather keeps the painted bitmap alive when it is what it returns`() {
        val painted = RenderTestBase.filled(8, 8, Color.RED)
        val base = RenderTestBase.filled(8, 8, Color.TRANSPARENT)
        val sel = Selection(listOf(ring(Offset(1f, 1f), Offset(7f, 7f))), IntSize(8, 8))
        val path = SelectionMask.bitmapPath(sel, 8, 8)

        assertEquals(painted, SelectionMask.feather(base, painted, path, 0f))
        assertFalse("the hard-edged path returns this bitmap, so it must survive", painted.isRecycled)
    }

    @Test
    fun `feather never recycles the base, even when handed it as the paint`() {
        // What an empty stroke, or an allocation that failed upstream, hands us: the "painted"
        // bitmap IS the base. Recycling it would destroy the layer the caller still owns.
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas, featherPx = 6f)
        val path = SelectionMask.bitmapPath(sel, 40, 40)!!
        val radius = SelectionMask.featherRadius(sel, 40, 40)
        val base = RenderTestBase.filled(40, 40, Color.RED)

        val out = SelectionMask.feather(base, base, path, radius)

        assertFalse(base.isRecycled)
        assertFalse(out.isRecycled)
    }

    @Test
    fun `confine recycles the produced bitmap it supersedes`() {
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas)
        val path = SelectionMask.bitmapPath(sel, 40, 40)!!
        val base = RenderTestBase.filled(40, 40, Color.TRANSPARENT)
        val produced = RenderTestBase.filled(40, 40, Color.RED)

        val out = SelectionMask.confine(base, produced, path, radiusPx = 0f)

        assertNotEquals(out, produced)
        assertTrue(produced.isRecycled)
        assertFalse(base.isRecycled)
    }

    @Test
    fun `confine with no clip hands the produced bitmap straight back`() {
        val base = RenderTestBase.filled(8, 8, Color.TRANSPARENT)
        val produced = RenderTestBase.filled(8, 8, Color.RED)

        assertEquals(produced, SelectionMask.confine(base, produced, clipPath = null, radiusPx = 0f))
        assertFalse(produced.isRecycled)
    }

    @Test
    fun `paintClip withholds the clip only when feathering`() {
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas)
        val path = SelectionMask.bitmapPath(sel, 40, 40)
        assertNotNull(path)
        // Hard edge: the paint is clipped. Feathered: it must go down unclipped, or the ramp has
        // nothing to reveal on the outer side.
        assertEquals(path, SelectionMask.paintClip(path, 0f))
        assertNull(SelectionMask.paintClip(path, 4f))
    }

    // ── Lift (what Copy takes) ─────────────────────────────────────────────────────────────────

    @Test
    fun `lift takes the selected pixels and nothing else`() {
        val source = RenderTestBase.filled(40, 40, Color.GREEN)
        val sel = Selection(listOf(ring(Offset(10f, 10f), Offset(30f, 30f))), canvas)
        val path = SelectionMask.bitmapPath(sel, 40, 40)
        val lifted = SelectionMask.lift(source, path, 0f)!!

        assertEquals(40, lifted.width) // full canvas, so paste lands in register
        assertEquals(Color.GREEN, lifted.getPixel(20, 20))
        assertEquals(0, RenderTestBase.alphaAt(lifted, 2, 2))
    }

    @Test
    fun `lift with no clip copies everything`() {
        val source = RenderTestBase.filled(8, 8, Color.BLUE)
        val lifted = SelectionMask.lift(source, null, 0f)!!
        assertEquals(Color.BLUE, lifted.getPixel(0, 0))
        assertEquals(Color.BLUE, lifted.getPixel(7, 7))
    }
}
