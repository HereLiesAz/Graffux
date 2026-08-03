package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The vector pen's geometry, independent of any UI. */
class PathEditingTest {

    /** A straight three-node path, the shape every pre-bezier path in the repo already has. */
    private fun straight() = VectorShape(
        kind = ShapeKind.PATH,
        points = listOf(0f, 0f, 100f, 0f, 100f, 100f),
    )

    private fun curved() = VectorShape(
        kind = ShapeKind.PATH,
        points = listOf(0f, 0f, 100f, 0f),
        handlesIn = listOf(0f, 0f, -30f, 0f),
        handlesOut = listOf(30f, 0f, 0f, 0f),
    )

    // ── Representation ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a path with no handle lists reads as all corners`() {
        val nodes = PathEditing.nodes(straight())
        assertEquals(3, nodes.size)
        assertTrue(nodes.all { it.isCorner })
        assertFalse(straight().hasCurves)
    }

    @Test
    fun `a straight path round-trips without gaining handle lists`() {
        // Storing zeroed handles on every straight path would bloat every saved project and make a
        // pre-bezier path compare unequal to the same path after a no-op edit.
        val out = PathEditing.normalized(straight())
        assertEquals(emptyList<Float>(), out.handlesIn)
        assertEquals(emptyList<Float>(), out.handlesOut)
        assertEquals(straight(), out)
    }

    @Test
    fun `a curved path round-trips through nodes unchanged`() {
        assertEquals(curved(), PathEditing.withNodes(curved(), PathEditing.nodes(curved())))
    }

    @Test
    fun `short handle lists are tolerated rather than throwing`() {
        // Defensive: a hand-edited or partially-migrated project must not crash the editor.
        val ragged = VectorShape(
            kind = ShapeKind.PATH,
            points = listOf(0f, 0f, 10f, 10f),
            handlesOut = listOf(5f),
        )
        val nodes = PathEditing.nodes(ragged)
        assertEquals(2, nodes.size)
        assertEquals(5f, nodes[0].outDx, 0f)
        assertEquals(0f, nodes[0].outDy, 0f)
    }

    // ── Moving ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `moving a node carries its handles`() {
        // This is the whole reason handles are stored relative to their node.
        val moved = PathEditing.moveNode(curved(), 0, dx = 10f, dy = 5f)
        val n = PathEditing.nodes(moved)[0]
        assertEquals(10f, n.x, 0f)
        assertEquals(5f, n.y, 0f)
        assertEquals(30f, n.outDx, 0f)
        assertEquals(0f, n.outDy, 0f)
    }

    @Test
    fun `moving a node out of range is a no-op`() {
        assertEquals(curved(), PathEditing.moveNode(curved(), 99, 10f, 10f))
        assertEquals(curved(), PathEditing.moveNode(curved(), -1, 10f, 10f))
    }

    @Test
    fun `dragging a handle mirrors the opposite one so the curve stays smooth`() {
        val out = PathEditing.moveHandle(straight(), 1, outgoing = true, x = 140f, y = 40f)
        val n = PathEditing.nodes(out)[1]
        assertEquals(40f, n.outDx, 1e-4f)
        assertEquals(40f, n.outDy, 1e-4f)
        assertEquals(-40f, n.inDx, 1e-4f)
        assertEquals(-40f, n.inDy, 1e-4f)
        assertTrue(n.isSmooth)
    }

    @Test
    fun `dragging a handle without mirroring makes a corner point`() {
        val out = PathEditing.moveHandle(straight(), 1, outgoing = true, x = 140f, y = 40f, mirror = false)
        val n = PathEditing.nodes(out)[1]
        assertEquals(40f, n.outDx, 1e-4f)
        assertEquals(0f, n.inDx, 0f)
        assertFalse(n.isSmooth)
    }

    // ── Corner / smooth ──────────────────────────────────────────────────────────────────────

    @Test
    fun `makeCorner strips a node's handles`() {
        val smooth = PathEditing.moveHandle(straight(), 1, outgoing = true, x = 140f, y = 40f)
        val corner = PathEditing.makeCorner(smooth, 1)
        assertTrue(PathEditing.nodes(corner)[1].isCorner)
        // The node itself must not move.
        assertEquals(100f, PathEditing.nodes(corner)[1].x, 0f)
    }

    @Test
    fun `makeSmooth gives an interior node mirrored handles along its neighbours`() {
        val out = PathEditing.makeSmooth(straight(), 1)
        val n = PathEditing.nodes(out)[1]
        assertTrue(n.isSmooth)
        // Neighbours are (0,0) and (100,100); the tangent runs down-right, normalised.
        assertTrue("outgoing should point toward the next node", n.outDx > 0f && n.outDy > 0f)
        assertTrue("incoming should point back toward the previous node", n.inDx < 0f && n.inDy < 0f)
    }

    @Test
    fun `makeSmooth cannot act on an endpoint of an open path`() {
        // No neighbour on one side, so there's no tangent to derive.
        assertEquals(straight(), PathEditing.makeSmooth(straight(), 0))
        assertEquals(straight(), PathEditing.makeSmooth(straight(), 2))
    }

    @Test
    fun `makeSmooth works on an endpoint once the path is closed`() {
        val closed = straight().copy(closed = true)
        assertTrue(PathEditing.nodes(PathEditing.makeSmooth(closed, 0))[0].isSmooth)
    }

    // ── Insert / delete ──────────────────────────────────────────────────────────────────────

    @Test
    fun `inserting a node does not change the curve's shape`() {
        // The point of de Casteljau subdivision: adding a point is non-destructive.
        val original = curved()
        val split = PathEditing.insertNode(original, segmentIndex = 0, t = 0.5f)
        assertEquals(3, PathEditing.nodeCount(split))

        val before = PathEditing.nodes(original)
        val after = PathEditing.nodes(split)
        // Sample the original single segment against the two new ones and compare positions.
        for (step in 0..10) {
            val t = step / 10f
            val (ex, ey) = PathEditing.pointOnSegment(before[0], before[1], t)
            val (ax, ay) = if (t <= 0.5f) {
                PathEditing.pointOnSegment(after[0], after[1], t / 0.5f)
            } else {
                PathEditing.pointOnSegment(after[1], after[2], (t - 0.5f) / 0.5f)
            }
            assertTrue("x at t=$t: expected $ex got $ax", abs(ex - ax) < 0.01f)
            assertTrue("y at t=$t: expected $ey got $ay", abs(ey - ay) < 0.01f)
        }
    }

    @Test
    fun `inserting on a straight segment lands on the line`() {
        val split = PathEditing.insertNode(straight(), segmentIndex = 0, t = 0.5f)
        val n = PathEditing.nodes(split)[1]
        assertEquals(50f, n.x, 1e-3f)
        assertEquals(0f, n.y, 1e-3f)
        // A subdivided straight segment must stay straight, not sprout curvature.
        assertFalse(split.hasCurves)
    }

    @Test
    fun `inserting on a segment that does not exist is a no-op`() {
        assertEquals(straight(), PathEditing.insertNode(straight(), segmentIndex = 9, t = 0.5f))
        assertEquals(straight(), PathEditing.insertNode(straight(), segmentIndex = -1, t = 0.5f))
        // An open 3-node path has 2 segments, so index 2 is out of range.
        assertEquals(straight(), PathEditing.insertNode(straight(), segmentIndex = 2, t = 0.5f))
    }

    @Test
    fun `a closed path has a segment joining its last node back to the first`() {
        val closed = straight().copy(closed = true)
        assertEquals(3, PathEditing.segmentCount(3, closed = true))
        val split = PathEditing.insertNode(closed, segmentIndex = 2, t = 0.5f)
        assertEquals(4, PathEditing.nodeCount(split))
        // Midpoint of (100,100)→(0,0).
        val n = PathEditing.nodes(split)[3]
        assertEquals(50f, n.x, 1e-3f)
        assertEquals(50f, n.y, 1e-3f)
    }

    @Test
    fun `deleting removes the node`() {
        val out = PathEditing.deleteNode(straight(), 1)
        assertEquals(2, PathEditing.nodeCount(out))
        assertEquals(listOf(0f, 0f, 100f, 100f), out.points)
    }

    @Test
    fun `deleting refuses to leave fewer than two nodes`() {
        // Below two nodes it isn't a path any more; removing the shape is a layer-level action.
        val two = VectorShape(kind = ShapeKind.PATH, points = listOf(0f, 0f, 10f, 10f))
        assertEquals(two, PathEditing.deleteNode(two, 0))
    }

    @Test
    fun `toggleClosed flips both ways`() {
        assertTrue(PathEditing.toggleClosed(straight()).closed)
        assertFalse(PathEditing.toggleClosed(straight().copy(closed = true)).closed)
    }

    // ── Hit testing ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on a node selects that node`() {
        val hit = PathEditing.hitTest(straight(), 100f, 2f, radius = 10f)
        assertEquals(PathEditing.Hit.NodeHit(1), hit)
    }

    @Test
    fun `a handle wins over the node it belongs to`() {
        // A handle sits near its node; testing nodes first would make handles unreachable.
        val shape = PathEditing.moveHandle(straight(), 1, outgoing = true, x = 120f, y = 0f)
        val hit = PathEditing.hitTest(shape, 120f, 0f, radius = 10f)
        assertEquals(PathEditing.Hit.HandleHit(1, outgoing = true), hit)
    }

    @Test
    fun `a tap directly on a half-smooth node selects NodeHit, not a zero-length handle`() {
        // Node 1 has a non-zero incoming handle, but a zero outgoing handle.
        val shape = PathEditing.moveHandle(straight(), 1, outgoing = false, x = 80f, y = 0f)
        // Tapping directly on Node 1 (100f, 0f) where outDx=0 must return NodeHit(1), not HandleHit(1, outgoing=true).
        val hit = PathEditing.hitTest(shape, 100f, 0f, radius = 5f)
        assertEquals(PathEditing.Hit.NodeHit(1), hit)
    }

    @Test
    fun `a tap on the line between nodes reports the segment and position along it`() {
        val hit = PathEditing.hitTest(straight(), 50f, 0f, radius = 10f)
        assertTrue("expected a segment hit, got $hit", hit is PathEditing.Hit.SegmentHit)
        val seg = hit as PathEditing.Hit.SegmentHit
        assertEquals(0, seg.segmentIndex)
        assertEquals(0.5f, seg.t, 0.1f)
    }

    @Test
    fun `a tap in empty space hits nothing`() {
        assertNull(PathEditing.hitTest(straight(), 500f, 500f, radius = 10f))
    }

    @Test
    fun `hit testing an empty path is safe`() {
        assertNull(PathEditing.hitTest(VectorShape(kind = ShapeKind.PATH), 0f, 0f, radius = 10f))
    }

    // ── Bounds ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `bounds of a straight path is the node bounding box`() {
        val b = PathEditing.bounds(straight())!!
        assertEquals(0f, b[0], 0f)
        assertEquals(0f, b[1], 0f)
        assertEquals(100f, b[2], 0f)
        assertEquals(100f, b[3], 0f)
    }

    @Test
    fun `bounds includes a curve that bulges past its end points`() {
        // Both handles pull downward, so the drawn curve dips below y=0 even though no node does.
        val bulging = VectorShape(
            kind = ShapeKind.PATH,
            points = listOf(0f, 0f, 100f, 0f),
            handlesIn = listOf(0f, 0f, 0f, 80f),
            handlesOut = listOf(0f, 80f, 0f, 0f),
        )
        val b = PathEditing.bounds(bulging)!!
        assertTrue("expected the box to reach past the nodes, got maxY=${b[3]}", b[3] > 10f)
    }

    @Test
    fun `bounds of an empty path is null rather than a degenerate box`() {
        assertNull(PathEditing.bounds(VectorShape(kind = ShapeKind.PATH)))
        assertNotNull(PathEditing.bounds(straight()))
    }
}
