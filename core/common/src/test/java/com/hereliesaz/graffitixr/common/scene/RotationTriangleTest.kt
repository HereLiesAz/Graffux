@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.RotationAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rotation triangle's maths (spec/3d-space.md, "Rotation triangle"). A 200 x 150 triangle with its
 * right angle at the bottom right: A (0,150) bottom left, B (200,150) the right angle, C (200,0)
 * top right; the bottom A-B is X (length 200), the side B-C is Y (150), the diagonal A-C is Z (250).
 * Screen coordinates, y down. Touch bands are 48 wide, so 24 each side of an edge.
 */
class RotationTriangleTest {
    private val t = RotationTriangle(width = 200f, height = 150f, bandWidth = 48f)

    @Test
    fun `each edge turns its own axis`() {
        assertEquals(RotationAxis.X, TriangleEdge.BOTTOM.axis)
        assertEquals(RotationAxis.Y, TriangleEdge.SIDE.axis)
        assertEquals(RotationAxis.Z, TriangleEdge.DIAGONAL.axis)
        assertEquals(250f, t.length(TriangleEdge.DIAGONAL), 1e-3f)
    }

    @Test
    fun `a touch on an edge picks that edge`() {
        assertEquals(TriangleEdge.BOTTOM, t.edgeAt(100f, 150f))
        assertEquals(TriangleEdge.BOTTOM, t.edgeAt(60f, 160f)) // 10 outside, still in the band
        assertEquals(TriangleEdge.SIDE, t.edgeAt(200f, 70f))
        assertEquals(TriangleEdge.SIDE, t.edgeAt(190f, 70f))
        // The diagonal's midpoint.
        assertEquals(TriangleEdge.DIAGONAL, t.edgeAt(100f, 75f))
    }

    @Test
    fun `the interior and the outside are not on any edge`() {
        // The middle of the triangle is far from all three.
        assertNull(t.edgeAt(-100f, -100f))
        assertNull(t.edgeAt(100f, 10f)) // above the diagonal, outside the triangle
        // Inside the triangle but further than half a band from every edge.
        val inner = t.incentre()
        assertTrue(t.distanceTo(TriangleEdge.BOTTOM, inner.first, inner.second) > 24f)
        assertNull(t.edgeAt(inner.first, inner.second))
        assertTrue(!t.inBand(inner.first, inner.second))
    }

    @Test
    fun `near a vertex the nearest edge by distance to the segment wins`() {
        // Just above the bottom edge, near the bottom-left vertex: nearer the bottom than the diagonal.
        assertEquals(TriangleEdge.BOTTOM, t.edgeAt(30f, 145f))
        // Just under the diagonal near the same vertex: the diagonal is closer.
        // Diagonal through (0,150) and (200,0): at x=30 it is at y=127.5; (30,131) is 3.5 below it.
        assertEquals(TriangleEdge.DIAGONAL, t.edgeAt(30f, 125f))
        // The right angle: a touch left of the side and above the bottom picks whichever is nearer.
        assertEquals(TriangleEdge.SIDE, t.edgeAt(195f, 120f))
        assertEquals(TriangleEdge.BOTTOM, t.edgeAt(170f, 148f))
        // Past the end of an edge the distance is to its end point, not the infinite line.
        assertNull(t.edgeAt(-40f, 150f))
        assertNotNull(t.edgeAt(-20f, 150f))
    }

    @Test
    fun `the full length of an edge is 180 degrees, signed by direction`() {
        // Bottom: right is positive X.
        assertEquals(180f, t.angle(TriangleEdge.BOTTOM, 0f, 0f, 200f, 0f), 1e-3f)
        assertEquals(-180f, t.angle(TriangleEdge.BOTTOM, 0f, 0f, -200f, 0f), 1e-3f)
        // Side: up (negative screen y) is positive Y.
        assertEquals(180f, t.angle(TriangleEdge.SIDE, 0f, 0f, 0f, -150f), 1e-3f)
        assertEquals(-90f, t.angle(TriangleEdge.SIDE, 0f, 0f, 0f, 75f), 1e-3f)
        // Diagonal: up and to the right is positive Z.
        assertEquals(180f, t.angle(TriangleEdge.DIAGONAL, 0f, 0f, 200f, -150f), 1e-3f)
        assertEquals(-180f, t.angle(TriangleEdge.DIAGONAL, 0f, 0f, -200f, 150f), 1e-3f)
    }

    @Test
    fun `only the part of the movement along the edge counts`() {
        // Half way along the bottom with a lot of sideways wander: 90.
        assertEquals(90f, t.angle(TriangleEdge.BOTTOM, 10f, 10f, 110f, 400f), 1e-3f)
        // Straight across the diagonal's normal: nothing.
        assertEquals(0f, t.angle(TriangleEdge.DIAGONAL, 0f, 0f, 150f, 200f), 1e-3f)
        // The diagonal reads a right-and-down drag as backwards, an up one as forwards.
        assertTrue(t.angle(TriangleEdge.DIAGONAL, 0f, 0f, 50f, 0f) > 0f)
        assertTrue(t.angle(TriangleEdge.DIAGONAL, 0f, 0f, 0f, 50f) < 0f)
    }

    @Test
    fun `a drag locks to the edge it started on, even when the finger leaves its band`() {
        val drag = t.beginDrag(100f, 150f)!! // on the bottom
        assertEquals(TriangleEdge.BOTTOM, drag.edge)
        // The finger wanders up to lie on the diagonal; the drag stays on the bottom.
        drag.moveTo(150f, 112f)
        assertEquals(TriangleEdge.BOTTOM, drag.edge)
        assertEquals(50f / 200f * 180f, drag.total, 1e-3f)
    }

    @Test
    fun `a drag that starts off the bands does not begin`() {
        assertNull(t.beginDrag(-100f, -100f))
        val inner = t.incentre()
        assertNull(t.beginDrag(inner.first, inner.second))
    }

    @Test
    fun `moveTo returns the change since the last move, so deltas add up to the total`() {
        val drag = t.beginDrag(20f, 150f)!!
        val d1 = drag.moveTo(60f, 150f) // +40 px = 36 degrees
        val d2 = drag.moveTo(120f, 150f) // +60 px = 54
        val d3 = drag.moveTo(100f, 150f) // -20 px = -18: a backward drag reduces the angle
        assertEquals(36f, d1, 1e-3f)
        assertEquals(54f, d2, 1e-3f)
        assertEquals(-18f, d3, 1e-3f)
        assertEquals(72f, d1 + d2 + d3, 1e-3f)
        assertEquals(72f, drag.total, 1e-3f)
    }

    @Test
    fun `two swipes of an edge make a full turn, with no end stop`() {
        var sum = 0f
        repeat(2) {
            val drag = t.beginDrag(0f, 150f)!!
            sum += drag.moveTo(100f, 150f) + drag.moveTo(200f, 150f)
        }
        assertEquals(360f, sum, 1e-2f)
        // Three swipes of three quarters of the edge: 405, past a full turn.
        var more = 0f
        repeat(3) {
            val drag = t.beginDrag(0f, 150f)!!
            more += drag.moveTo(150f, 150f)
        }
        assertEquals(405f, more, 1e-2f)
    }

    @Test
    fun `the readout names the axis and the angle of the drag so far`() {
        val drag = t.beginDrag(0f, 150f)!!
        drag.moveTo(41f, 150f)
        assertEquals("X 37°", drag.readout())
        val back = t.beginDrag(200f, 80f)!! // on the side
        back.moveTo(200f, 110f) // 30 px down = -36 degrees
        assertEquals("Y -36°", back.readout())
    }

    @Test
    fun `the touch band outline covers every point within half a band of an edge`() {
        // The polygon the app clips to must agree with edgeAt about where the bands are.
        val outline = t.bandOutline()
        assertTrue(outline.isNotEmpty())
        for (x in -40..240 step 5) for (y in -40..190 step 5) {
            val d = TriangleEdge.entries.minOf { t.distanceTo(it, x.toFloat(), y.toFloat()) }
            if (kotlin.math.abs(d - 24f) < 0.6f) continue // on the rim: polygon rounding decides
            val inside = outline.any { poly -> RotationTriangle.polygonContains(poly, x.toFloat(), y.toFloat()) }
            assertEquals("($x,$y)", t.inBand(x.toFloat(), y.toFloat()), inside)
        }
    }

    @Test
    fun `accessibility steps are 15 degrees on the edge's own axis`() {
        assertEquals(15f, RotationTriangle.ACCESSIBILITY_STEP_DEGREES, 0f)
        assertEquals(180f, RotationTriangle.FULL_EDGE_DEGREES, 0f)
    }
}
