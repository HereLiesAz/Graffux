package com.hereliesaz.graffitixr.common.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Node-level editing of a [ShapeKind.PATH] — Figma's vector pen, minus the UI. Every operation takes
 * a shape and returns a new one, with no Android or Compose types, so the geometry is unit-testable
 * on its own.
 *
 * A path is [VectorShape.points] (interleaved `[x,y]` node positions) plus optional
 * [VectorShape.handlesIn]/[VectorShape.handlesOut] — control offsets **relative to their own node**.
 * Relative offsets are what make a node drag trivial: move the point, and its curvature follows for
 * free. Handle lists are either empty (a straight poly-line, the pre-bezier representation) or
 * exactly as long as [VectorShape.points]; [normalized] enforces that so no operation has to
 * special-case a half-populated path.
 */
object PathEditing {

    /** One node's full state, which is easier to reason about than three parallel float lists. */
    data class Node(
        val x: Float,
        val y: Float,
        val inDx: Float = 0f,
        val inDy: Float = 0f,
        val outDx: Float = 0f,
        val outDy: Float = 0f,
    ) {
        val isCorner: Boolean get() = inDx == 0f && inDy == 0f && outDx == 0f && outDy == 0f

        /**
         * True when the curve passes through this node without a kink — the two handles are
         * collinear and point opposite ways. Their *lengths* need not match: a smooth node with
         * handles scaled to its neighbour distances (what [makeSmooth] produces, and what Figma
         * does) is still smooth. Requiring equal lengths would call that a corner.
         *
         * A corner (no handles at all) is not smooth.
         */
        val isSmooth: Boolean
            get() {
                if (isCorner) return false
                val inLen = hypot(inDx, inDy)
                val outLen = hypot(outDx, outDy)
                if (inLen < EPSILON || outLen < EPSILON) return false
                val ux = inDx / inLen; val uy = inDy / inLen
                val vx = outDx / outLen; val vy = outDy / outLen
                // Collinear (zero cross product) and facing opposite directions (negative dot).
                return abs(ux * vy - uy * vx) < COLLINEAR_EPSILON && (ux * vx + uy * vy) < 0f
            }
    }

    fun nodeCount(shape: VectorShape): Int = shape.points.size / 2

    /** Reads a path out as [Node]s, tolerating absent or short handle lists. */
    fun nodes(shape: VectorShape): List<Node> {
        val count = nodeCount(shape)
        return List(count) { i ->
            Node(
                x = shape.points[2 * i],
                y = shape.points[2 * i + 1],
                inDx = shape.handlesIn.getOrElse(2 * i) { 0f },
                inDy = shape.handlesIn.getOrElse(2 * i + 1) { 0f },
                outDx = shape.handlesOut.getOrElse(2 * i) { 0f },
                outDy = shape.handlesOut.getOrElse(2 * i + 1) { 0f },
            )
        }
    }

    /** Writes [nodes] back, dropping all-zero handle lists so a straight path stays straight. */
    fun withNodes(shape: VectorShape, nodes: List<Node>): VectorShape {
        val points = ArrayList<Float>(nodes.size * 2)
        val handlesIn = ArrayList<Float>(nodes.size * 2)
        val handlesOut = ArrayList<Float>(nodes.size * 2)
        nodes.forEach { n ->
            points.add(n.x); points.add(n.y)
            handlesIn.add(n.inDx); handlesIn.add(n.inDy)
            handlesOut.add(n.outDx); handlesOut.add(n.outDy)
        }
        // A path with no curvature stores no handles at all, so it round-trips identically to one
        // saved before handles existed.
        val anyCurve = handlesIn.any { it != 0f } || handlesOut.any { it != 0f }
        return shape.copy(
            points = points,
            handlesIn = if (anyCurve) handlesIn else emptyList(),
            handlesOut = if (anyCurve) handlesOut else emptyList(),
        )
    }

    /** Re-emits [shape] with handle lists either empty or exactly point-length. */
    fun normalized(shape: VectorShape): VectorShape = withNodes(shape, nodes(shape))

    // ── Editing operations ───────────────────────────────────────────────────────────────────

    /** Moves node [index] by ([dx], [dy]). Handles are relative, so they follow automatically. */
    fun moveNode(shape: VectorShape, index: Int, dx: Float, dy: Float): VectorShape {
        val nodes = nodes(shape)
        if (index !in nodes.indices) return shape
        return withNodes(
            shape,
            nodes.mapIndexed { i, n -> if (i == index) n.copy(x = n.x + dx, y = n.y + dy) else n },
        )
    }

    /**
     * Sets one handle of node [index] to an absolute local position. When [mirror] the opposite
     * handle is set to the exact reflection, which is what keeps a curve smooth while dragging —
     * Figma's default for a smooth node, and what holding the modifier turns off to make a corner.
     */
    fun moveHandle(
        shape: VectorShape,
        index: Int,
        outgoing: Boolean,
        x: Float,
        y: Float,
        mirror: Boolean = true,
    ): VectorShape {
        val nodes = nodes(shape)
        if (index !in nodes.indices) return shape
        return withNodes(
            shape,
            nodes.mapIndexed { i, n ->
                if (i != index) return@mapIndexed n
                val dx = x - n.x
                val dy = y - n.y
                when {
                    outgoing && mirror -> n.copy(outDx = dx, outDy = dy, inDx = -dx, inDy = -dy)
                    outgoing -> n.copy(outDx = dx, outDy = dy)
                    mirror -> n.copy(inDx = dx, inDy = dy, outDx = -dx, outDy = -dy)
                    else -> n.copy(inDx = dx, inDy = dy)
                }
            },
        )
    }

    /** Strips a node's handles, turning a smooth point into a hard corner. */
    fun makeCorner(shape: VectorShape, index: Int): VectorShape {
        val nodes = nodes(shape)
        if (index !in nodes.indices) return shape
        return withNodes(shape, nodes.mapIndexed { i, n -> if (i == index) Node(n.x, n.y) else n })
    }

    /**
     * Gives node [index] mirrored handles derived from its neighbours, so a corner rounds off the
     * way Figma's "smooth" does. The handle length is a fraction of the neighbour distance, which
     * keeps the curve proportional to the geometry rather than a fixed size.
     */
    fun makeSmooth(shape: VectorShape, index: Int): VectorShape {
        val nodes = nodes(shape)
        if (index !in nodes.indices) return shape
        val prev = neighbour(nodes, index, -1, shape.closed) ?: return shape
        val next = neighbour(nodes, index, +1, shape.closed) ?: return shape
        // Tangent along the line through the two neighbours — the standard Catmull-Rom style tangent.
        val tx = next.x - prev.x
        val ty = next.y - prev.y
        val length = hypot(tx, ty)
        if (length < EPSILON) return shape
        val n = nodes[index]
        val outLen = hypot(next.x - n.x, next.y - n.y) * SMOOTH_FACTOR
        val inLen = hypot(n.x - prev.x, n.y - prev.y) * SMOOTH_FACTOR
        val ux = tx / length
        val uy = ty / length
        return withNodes(
            shape,
            nodes.mapIndexed { i, node ->
                if (i == index) {
                    node.copy(outDx = ux * outLen, outDy = uy * outLen, inDx = -ux * inLen, inDy = -uy * inLen)
                } else {
                    node
                }
            },
        )
    }

    /**
     * Inserts a node on the segment starting at [segmentIndex], at parameter [t] along it, splitting
     * the curve so the path's shape is **unchanged** — de Casteljau subdivision, which is what makes
     * "add a point" feel non-destructive. The two neighbouring nodes' facing handles are shortened
     * accordingly, exactly as the algorithm requires.
     */
    fun insertNode(shape: VectorShape, segmentIndex: Int, t: Float): VectorShape {
        val nodes = nodes(shape)
        val segments = segmentCount(nodes.size, shape.closed)
        if (segmentIndex !in 0 until segments) return shape
        val clampedT = t.coerceIn(0f, 1f)

        val a = nodes[segmentIndex]
        val bIndex = (segmentIndex + 1) % nodes.size
        val b = nodes[bIndex]

        // A segment with no control offsets on either facing side is a straight line. Subdividing it
        // with the general formula below would be geometrically correct but would store collinear,
        // non-zero handles — turning a straight path into a "curved" one that merely happens to look
        // straight. Splitting the line directly keeps it genuinely straight.
        if (a.outDx == 0f && a.outDy == 0f && b.inDx == 0f && b.inDy == 0f) {
            val out = nodes.toMutableList()
            out.add(
                segmentIndex + 1,
                Node(x = lerp(a.x, b.x, clampedT), y = lerp(a.y, b.y, clampedT)),
            )
            return withNodes(shape, out)
        }

        // Cubic control points for this segment.
        val p0x = a.x; val p0y = a.y
        val p1x = a.x + a.outDx; val p1y = a.y + a.outDy
        val p2x = b.x + b.inDx; val p2y = b.y + b.inDy
        val p3x = b.x; val p3y = b.y

        // de Casteljau at t.
        val q0x = lerp(p0x, p1x, clampedT); val q0y = lerp(p0y, p1y, clampedT)
        val q1x = lerp(p1x, p2x, clampedT); val q1y = lerp(p1y, p2y, clampedT)
        val q2x = lerp(p2x, p3x, clampedT); val q2y = lerp(p2y, p3y, clampedT)
        val r0x = lerp(q0x, q1x, clampedT); val r0y = lerp(q0y, q1y, clampedT)
        val r1x = lerp(q1x, q2x, clampedT); val r1y = lerp(q1y, q2y, clampedT)
        val sx = lerp(r0x, r1x, clampedT); val sy = lerp(r0y, r1y, clampedT)

        val newA = a.copy(outDx = q0x - a.x, outDy = q0y - a.y)
        val newB = b.copy(inDx = q2x - b.x, inDy = q2y - b.y)
        val inserted = Node(
            x = sx, y = sy,
            inDx = r0x - sx, inDy = r0y - sy,
            outDx = r1x - sx, outDy = r1y - sy,
        )

        val out = nodes.toMutableList()
        out[segmentIndex] = newA
        out[bIndex] = newB
        out.add(segmentIndex + 1, inserted)
        return withNodes(shape, out)
    }

    /**
     * Deletes node [index]. A path needs two nodes to be a path at all, so the last two are kept —
     * deleting the shape itself is a layer operation, not a node one.
     */
    fun deleteNode(shape: VectorShape, index: Int): VectorShape {
        val nodes = nodes(shape)
        if (index !in nodes.indices || nodes.size <= 2) return shape
        return withNodes(shape, nodes.filterIndexed { i, _ -> i != index })
    }

    fun toggleClosed(shape: VectorShape): VectorShape = shape.copy(closed = !shape.closed)

    // ── Hit testing ──────────────────────────────────────────────────────────────────────────

    /** What a tap landed on, in the shape's local space. */
    sealed interface Hit {
        data class NodeHit(val index: Int) : Hit
        data class HandleHit(val index: Int, val outgoing: Boolean) : Hit
        /** A point on a segment, with the parameter along it — where an insert would go. */
        data class SegmentHit(val segmentIndex: Int, val t: Float) : Hit
    }

    /**
     * Finds what's under ([x], [y]) within [radius]. Handles are tested before nodes and nodes
     * before segments: a handle sits near its node and a node sits on its segment, so the reverse
     * order would make the finer targets unreachable.
     */
    fun hitTest(shape: VectorShape, x: Float, y: Float, radius: Float): Hit? {
        val nodes = nodes(shape)
        if (nodes.isEmpty()) return null
        val r2 = radius * radius

        nodes.forEachIndexed { i, n ->
            if (!n.isCorner) {
                if (dist2(x, y, n.x + n.outDx, n.y + n.outDy) <= r2) return Hit.HandleHit(i, outgoing = true)
                if (dist2(x, y, n.x + n.inDx, n.y + n.inDy) <= r2) return Hit.HandleHit(i, outgoing = false)
            }
        }
        nodes.forEachIndexed { i, n ->
            if (dist2(x, y, n.x, n.y) <= r2) return Hit.NodeHit(i)
        }

        var best: Hit.SegmentHit? = null
        var bestDist = r2
        for (s in 0 until segmentCount(nodes.size, shape.closed)) {
            // Flatten the segment and take the closest sample; exact cubic-to-point distance has no
            // closed form, and at tap resolution a fine sampling is indistinguishable from it.
            val a = nodes[s]
            val b = nodes[(s + 1) % nodes.size]
            for (step in 0..SEGMENT_SAMPLES) {
                val t = step / SEGMENT_SAMPLES.toFloat()
                val (px, py) = pointOnSegment(a, b, t)
                val d = dist2(x, y, px, py)
                if (d < bestDist) {
                    bestDist = d
                    best = Hit.SegmentHit(s, t)
                }
            }
        }
        return best
    }

    /** The point at parameter [t] along the cubic segment from [a] to [b]. */
    fun pointOnSegment(a: Node, b: Node, t: Float): Pair<Float, Float> {
        val p0x = a.x; val p0y = a.y
        val p1x = a.x + a.outDx; val p1y = a.y + a.outDy
        val p2x = b.x + b.inDx; val p2y = b.y + b.inDy
        val p3x = b.x; val p3y = b.y
        val u = 1f - t
        val w0 = u * u * u
        val w1 = 3f * u * u * t
        val w2 = 3f * u * t * t
        val w3 = t * t * t
        return (w0 * p0x + w1 * p1x + w2 * p2x + w3 * p3x) to (w0 * p0y + w1 * p1y + w2 * p2y + w3 * p3y)
    }

    /** How many drawn segments a path of [nodeCount] nodes has; a closed path has one more. */
    fun segmentCount(nodeCount: Int, closed: Boolean): Int = when {
        nodeCount < 2 -> 0
        closed -> nodeCount
        else -> nodeCount - 1
    }

    /** The path's bounding box as `[minX, minY, maxX, maxY]`, or null when it has no nodes. */
    fun bounds(shape: VectorShape): FloatArray? {
        val nodes = nodes(shape)
        if (nodes.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun include(x: Float, y: Float) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        // Sampling the curve rather than only the nodes: a curve can bulge well outside its
        // end points, and a box that clips the drawn shape is worse than a slightly loose one.
        nodes.forEach { include(it.x, it.y) }
        for (s in 0 until segmentCount(nodes.size, shape.closed)) {
            val a = nodes[s]
            val b = nodes[(s + 1) % nodes.size]
            if (a.isCorner && b.isCorner) continue
            for (step in 1 until SEGMENT_SAMPLES) {
                val (px, py) = pointOnSegment(a, b, step / SEGMENT_SAMPLES.toFloat())
                include(px, py)
            }
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun neighbour(nodes: List<Node>, index: Int, delta: Int, closed: Boolean): Node? {
        val target = index + delta
        return when {
            target in nodes.indices -> nodes[target]
            closed && nodes.isNotEmpty() -> nodes[(target + nodes.size) % nodes.size]
            else -> null
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private const val EPSILON = 1e-4f
    /** Tolerance on the unit-vector cross product, i.e. roughly how many degrees still count as straight. */
    private const val COLLINEAR_EPSILON = 1e-3f
    private const val SMOOTH_FACTOR = 0.33f
    private const val SEGMENT_SAMPLES = 16
}
