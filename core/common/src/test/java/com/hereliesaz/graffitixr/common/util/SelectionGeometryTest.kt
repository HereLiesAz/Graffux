package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.ELLIPSE_VERTICES
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.SelectionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGeometryTest {

    private val canvas = IntSize(100, 100)

    /** A 40×40 square from (10,10) to (50,50). */
    private fun square(inverted: Boolean = false) = Selection.ofPolygon(
        path = listOf(Offset(10f, 10f), Offset(50f, 10f), Offset(50f, 50f), Offset(10f, 50f)),
        canvasSize = canvas,
        inverted = inverted,
    )

    @Test
    fun `a point inside the polygon is selected`() {
        assertTrue(SelectionGeometry.contains(square(), Offset(30f, 30f)))
    }

    @Test
    fun `a point outside the polygon is not selected`() {
        assertFalse(SelectionGeometry.contains(square(), Offset(80f, 30f)))
        assertFalse(SelectionGeometry.contains(square(), Offset(30f, 90f)))
    }

    @Test
    fun `inverting swaps inside for outside`() {
        val inverted = square(inverted = true)
        assertFalse(SelectionGeometry.contains(inverted, Offset(30f, 30f)))
        assertTrue(SelectionGeometry.contains(inverted, Offset(80f, 30f)))
    }

    @Test
    fun `a concave polygon excludes its notch`() {
        // A "C": the gap between the arms is outside the shape even though it is within its bounds.
        val c = Selection.ofPolygon(
            path = listOf(
                Offset(0f, 0f), Offset(50f, 0f), Offset(50f, 10f), Offset(10f, 10f),
                Offset(10f, 40f), Offset(50f, 40f), Offset(50f, 50f), Offset(0f, 50f),
            ),
            canvasSize = canvas,
        )
        assertTrue(SelectionGeometry.contains(c, Offset(5f, 25f)))   // the spine
        assertFalse(SelectionGeometry.contains(c, Offset(30f, 25f))) // the notch
    }

    @Test
    fun `a polygon too small to enclose area selects nothing`() {
        val degenerate = Selection.ofPolygon(listOf(Offset(1f, 1f), Offset(2f, 2f)), canvas)
        assertFalse(degenerate.isUsable)
        assertFalse(SelectionGeometry.contains(degenerate, Offset(1f, 1f)))
    }

    @Test
    fun `a zero-sized canvas selects nothing`() {
        assertFalse(square().copy(canvasSize = IntSize.Zero).isUsable)
    }

    @Test
    fun `simplify thins a densely traced loop but keeps its shape`() {
        // A 40x40 square perimeter sampled every unit — what a finger actually produces.
        val dense = buildList {
            for (x in 0..39) add(Offset(x.toFloat(), 0f))
            for (y in 0..39) add(Offset(40f, y.toFloat()))
            for (x in 39 downTo 0) add(Offset(x.toFloat(), 40f))
            for (y in 39 downTo 0) add(Offset(0f, y.toFloat()))
        }
        val thinned = SelectionGeometry.simplify(dense, minSpacing = 5f)

        assertTrue("should thin the trace", thinned.size < dense.size / 4)
        assertTrue("should stay a usable polygon", thinned.size >= 3)
        // No two retained points sit closer than the spacing.
        thinned.zipWithNext { a, b -> assertTrue((b - a).getDistance() >= 5f) }
        // And it still bounds what it did before: the square's centre is inside, a far point isn't.
        assertTrue(SelectionGeometry.insidePolygon(thinned, Offset(20f, 20f)))
        assertFalse(SelectionGeometry.insidePolygon(thinned, Offset(80f, 20f)))
    }

    @Test
    fun `simplify keeps a polygon usable rather than thinning it away`() {
        // Every point falls inside the spacing, so thinning would leave a degenerate shape;
        // the original is returned instead of a selection that can never clip anything.
        val tight = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
        assertEquals(tight, SelectionGeometry.simplify(tight, minSpacing = 50f))
    }

    @Test
    fun `simplify drops a closing point that lands back on the start`() {
        val loop = listOf(Offset(0f, 0f), Offset(20f, 0f), Offset(20f, 20f), Offset(0f, 20f), Offset(0.5f, 0.5f))
        val thinned = SelectionGeometry.simplify(loop, minSpacing = 3f)
        assertEquals(4, thinned.size)
        assertEquals(Offset(0f, 20f), thinned.last())
    }

    // ── Selection modes (Procreate's Rectangle / Ellipse) ────────────────────────────────────────

    @Test
    fun `a rectangle is built from either drag direction`() {
        val downRight = SelectionGeometry.rectangle(Offset(10f, 10f), Offset(50f, 50f))
        val upLeft = SelectionGeometry.rectangle(Offset(50f, 50f), Offset(10f, 10f))
        // Same four corners in the same order regardless of which way the finger went — dragging
        // up-left must not wind the polygon backwards and draw the marquee as a bow tie.
        assertEquals(downRight, upLeft)
        assertEquals(4, downRight.size)
        assertEquals(Offset(10f, 10f), downRight[0])
        assertEquals(Offset(50f, 50f), downRight[2])
    }

    @Test
    fun `a rectangle selects inside its bounds and not outside`() {
        val rect = SelectionGeometry.rectangle(Offset(10f, 10f), Offset(50f, 50f))
        assertTrue(SelectionGeometry.insidePolygon(rect, Offset(30f, 30f)))
        assertFalse(SelectionGeometry.insidePolygon(rect, Offset(60f, 30f)))
    }

    @Test
    fun `an ellipse fills its box at the axes and misses at the corners`() {
        val ellipse = SelectionGeometry.ellipse(Offset(0f, 0f), Offset(100f, 100f))
        // The point of the mode: an ellipse is not its bounding box. The centre and the near-axis
        // points are in; the box's own corners are out, which is the whole difference from RECTANGLE.
        assertTrue(SelectionGeometry.insidePolygon(ellipse, Offset(50f, 50f)))
        assertTrue(SelectionGeometry.insidePolygon(ellipse, Offset(50f, 5f)))
        assertTrue(SelectionGeometry.insidePolygon(ellipse, Offset(95f, 50f)))
        assertFalse(SelectionGeometry.insidePolygon(ellipse, Offset(5f, 5f)))
        assertFalse(SelectionGeometry.insidePolygon(ellipse, Offset(95f, 95f)))
    }

    @Test
    fun `an ellipse does not repeat its first vertex`() {
        val ellipse = SelectionGeometry.ellipse(Offset(0f, 0f), Offset(100f, 50f))
        assertEquals(ELLIPSE_VERTICES, ellipse.size)
        // Selection's path is implicitly closed. A duplicated start point would put a zero-length
        // edge into every consumer that walks the list.
        assertTrue((ellipse.last() - ellipse.first()).getDistance() > 1f)
    }

    @Test
    fun `polygonFor traces the finger for freehand and ignores it for the box shapes`() {
        val traced = listOf(Offset(1f, 1f), Offset(2f, 9f), Offset(9f, 4f))
        val start = Offset(0f, 0f)
        val end = Offset(20f, 20f)
        assertEquals(traced, SelectionGeometry.polygonFor(SelectionShape.FREEHAND, start, end, traced))
        assertEquals(
            SelectionGeometry.rectangle(start, end),
            SelectionGeometry.polygonFor(SelectionShape.RECTANGLE, start, end, traced),
        )
        assertEquals(
            SelectionGeometry.ellipse(start, end),
            SelectionGeometry.polygonFor(SelectionShape.ELLIPSE, start, end, traced),
        )
    }

    @Test
    fun `simplify keeps a rectangle too thin to thin`() {
        // simplify() drops points within 3px of the last kept one, which would leave a 2px-tall
        // marquee with fewer than three vertices — i.e. nothing selected. It must fall back to the
        // original polygon rather than quietly discard the selection.
        val thin = SelectionGeometry.rectangle(Offset(10f, 10f), Offset(50f, 12f))
        assertEquals(4, SelectionGeometry.simplify(thin).size)
    }


    // ── The ring stack (Procreate's Add / Remove) ───────────────────────────────────────────────

    private fun ring(a: Offset, b: Offset, additive: Boolean = true) =
        SelectionRing(SelectionGeometry.rectangle(a, b), additive)

    @Test
    fun `an additive ring unions its region in`() {
        val two = Selection(
            rings = listOf(ring(Offset(0f, 0f), Offset(20f, 20f)), ring(Offset(40f, 0f), Offset(60f, 20f))),
            canvasSize = canvas,
        )
        assertTrue(SelectionGeometry.contains(two, Offset(10f, 10f)))
        assertTrue(SelectionGeometry.contains(two, Offset(50f, 10f)))
        assertFalse(SelectionGeometry.contains(two, Offset(30f, 10f))) // the gap between them
    }

    @Test
    fun `a subtractive ring cuts a hole`() {
        val donut = Selection(
            rings = listOf(
                ring(Offset(0f, 0f), Offset(60f, 60f)),
                ring(Offset(20f, 20f), Offset(40f, 40f), additive = false),
            ),
            canvasSize = canvas,
        )
        assertTrue(SelectionGeometry.contains(donut, Offset(10f, 10f)))  // the ring
        assertFalse(SelectionGeometry.contains(donut, Offset(30f, 30f))) // the hole
    }

    @Test
    fun `ring order is significant`() {
        val box = ring(Offset(0f, 0f), Offset(40f, 40f))
        val cut = ring(Offset(0f, 0f), Offset(40f, 40f), additive = false)
        // Add then remove leaves nothing; remove then add leaves the box. If the stack were a set
        // of shapes rather than an ordered recipe these two would be indistinguishable.
        val addThenRemove = Selection(listOf(box, cut), canvas)
        val removeThenAdd = Selection(listOf(cut, box), canvas)
        assertFalse(SelectionGeometry.contains(addThenRemove, Offset(20f, 20f)))
        assertTrue(SelectionGeometry.contains(removeThenAdd, Offset(20f, 20f)))
    }

    @Test
    fun `a stack that only subtracts selects nothing`() {
        // Cutting out of an empty region leaves it empty, so there is nothing to clip to and
        // isUsable must say so — otherwise every subsequent stroke clips to nothing, silently.
        val onlyCuts = Selection(listOf(ring(Offset(0f, 0f), Offset(40f, 40f), additive = false)), canvas)
        assertFalse(onlyCuts.isUsable)
        assertFalse(SelectionGeometry.contains(onlyCuts, Offset(20f, 20f)))
    }

    @Test
    fun `inverted applies to the combined region, not to each ring`() {
        val donut = Selection(
            rings = listOf(
                ring(Offset(0f, 0f), Offset(60f, 60f)),
                ring(Offset(20f, 20f), Offset(40f, 40f), additive = false),
            ),
            canvasSize = canvas,
            inverted = true,
        )
        assertFalse(SelectionGeometry.contains(donut, Offset(10f, 10f))) // was the ring
        assertTrue(SelectionGeometry.contains(donut, Offset(30f, 30f)))  // was the hole
        assertTrue(SelectionGeometry.contains(donut, Offset(90f, 90f)))  // was always outside
    }

    @Test
    fun `translated moves every ring together`() {
        val donut = Selection(
            rings = listOf(
                ring(Offset(0f, 0f), Offset(60f, 60f)),
                ring(Offset(20f, 20f), Offset(40f, 40f), additive = false),
            ),
            canvasSize = canvas,
        ).translated(Offset(100f, 0f))
        // The hole travels with the region; moving only the outer ring would fill it in.
        assertTrue(SelectionGeometry.contains(donut, Offset(110f, 10f)))
        assertFalse(SelectionGeometry.contains(donut, Offset(130f, 30f)))
    }

    @Test
    fun `outline is the first additive ring`() {
        val stack = Selection(
            rings = listOf(
                ring(Offset(0f, 0f), Offset(10f, 10f), additive = false),
                ring(Offset(0f, 0f), Offset(60f, 60f)),
                ring(Offset(20f, 20f), Offset(40f, 40f)),
            ),
            canvasSize = canvas,
        )
        assertEquals(SelectionGeometry.rectangle(Offset(0f, 0f), Offset(60f, 60f)), stack.outline)
    }


    // ── compose(): what a completed drag does to what is already there ───────────────────────────

    private val poly = SelectionGeometry.rectangle(Offset(0f, 0f), Offset(40f, 40f))
    private val other = SelectionGeometry.rectangle(Offset(60f, 0f), Offset(90f, 40f))

    @Test
    fun `NEW replaces whatever was selected`() {
        val first = SelectionGeometry.compose(null, poly, canvas, SelectionOp.NEW)!!
        val second = SelectionGeometry.compose(first, other, canvas, SelectionOp.NEW)!!
        assertEquals(1, second.rings.size)
        assertFalse(SelectionGeometry.contains(second, Offset(20f, 20f)))
        assertTrue(SelectionGeometry.contains(second, Offset(70f, 20f)))
    }

    @Test
    fun `NEW does not inherit the previous selection's invert or feather`() {
        // A glee audit found NEW reused the same `fresh` Selection that ADD/REMOVE build to carry a
        // *loaded* selection's invert/feather into a lone new ring -- so an unrelated new NEW-op
        // lasso, drawn after inverting and feathering a previous selection without deselecting
        // first, silently inherited both. This pins the fix: NEW must always be a clean, un-inverted,
        // hard-edged region.
        val invertedFeathered = SelectionGeometry.compose(null, poly, canvas, SelectionOp.NEW)!!
            .copy(inverted = true, featherPx = 20f)
        val second = SelectionGeometry.compose(invertedFeathered, other, canvas, SelectionOp.NEW)!!
        assertFalse("NEW must not inherit the previous selection's inverted flag", second.inverted)
        assertEquals("NEW must not inherit the previous selection's feather radius", 0f, second.featherPx)
    }

    @Test
    fun `ADD with nothing selected is just a new selection`() {
        // So Add works as the first stroke of a multi-part selection without having to start in
        // New and switch modes part-way.
        val made = SelectionGeometry.compose(null, poly, canvas, SelectionOp.ADD)!!
        assertTrue(SelectionGeometry.contains(made, Offset(20f, 20f)))
    }

    @Test
    fun `REMOVE with nothing selected does nothing`() {
        // There is no implicit "everything is selected" to cut out of — with no selection every
        // tool already paints everywhere — so this must not become an inversion.
        assertNull(SelectionGeometry.compose(null, poly, canvas, SelectionOp.REMOVE))
    }

    @Test
    fun `ADD unions and REMOVE cuts`() {
        val one = SelectionGeometry.compose(null, poly, canvas, SelectionOp.NEW)!!
        val both = SelectionGeometry.compose(one, other, canvas, SelectionOp.ADD)!!
        assertTrue(SelectionGeometry.contains(both, Offset(20f, 20f)))
        assertTrue(SelectionGeometry.contains(both, Offset(70f, 20f)))

        val cut = SelectionGeometry.compose(both, poly, canvas, SelectionOp.REMOVE)!!
        assertFalse(SelectionGeometry.contains(cut, Offset(20f, 20f)))
        assertTrue(SelectionGeometry.contains(cut, Offset(70f, 20f)))
    }

    @Test
    fun `composing onto an inverted selection adds to what is visible`() {
        // `inverted` applies to the whole stack, so an additive ring under it would SHRINK the
        // visible region. Add must add to what the user can see or the button is a lie.
        val invertedHole = Selection(listOf(SelectionRing(poly)), canvas, inverted = true)
        assertFalse(SelectionGeometry.contains(invertedHole, Offset(20f, 20f)))

        val added = SelectionGeometry.compose(invertedHole, other, canvas, SelectionOp.ADD)!!
        assertTrue(SelectionGeometry.contains(added, Offset(70f, 20f)))

        val removed = SelectionGeometry.compose(invertedHole, other, canvas, SelectionOp.REMOVE)!!
        assertFalse(SelectionGeometry.contains(removed, Offset(70f, 20f)))
    }

    @Test
    fun `a canvas size change forces a replace`() {
        // The rings are screen-space; folding into a different canvas would put the old region
        // somewhere it was never drawn.
        val one = SelectionGeometry.compose(null, poly, canvas, SelectionOp.NEW)!!
        val rotated = SelectionGeometry.compose(one, other, IntSize(200, 50), SelectionOp.ADD)!!
        assertEquals(1, rotated.rings.size)
        assertEquals(IntSize(200, 50), rotated.canvasSize)
    }

    @Test
    fun `a degenerate drag leaves the selection alone`() {
        // Under Add or Remove a stray tap must not throw away the region being built up.
        val one = SelectionGeometry.compose(null, poly, canvas, SelectionOp.NEW)!!
        val sliver = listOf(Offset(5f, 5f), Offset(6f, 6f))
        assertEquals(one, SelectionGeometry.compose(one, sliver, canvas, SelectionOp.ADD))
        assertEquals(one, SelectionGeometry.compose(one, sliver, canvas, SelectionOp.NEW))
    }

}
