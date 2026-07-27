package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGeometryTest {

    private val canvas = IntSize(100, 100)

    /** A 40×40 square from (10,10) to (50,50). */
    private fun square(inverted: Boolean = false) = Selection(
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
        val c = Selection(
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
        val degenerate = Selection(listOf(Offset(1f, 1f), Offset(2f, 2f)), canvas)
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
}
