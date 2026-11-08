@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.RotationAxis
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The edges of the rotation triangle (spec/3d-space.md, "Rotation triangle"), each turning one axis of the
 * object's own body axes: the bottom leg X, the vertical leg Y, the hypotenuse Z.
 */
enum class TriangleEdge(val axis: RotationAxis) {
    BOTTOM(RotationAxis.X),
    SIDE(RotationAxis.Y),
    DIAGONAL(RotationAxis.Z),
}

/**
 * The right-triangle rotation gizmo's geometry, in the gizmo's own pixels (y down, origin the top
 * left of the triangle's box): A (0,h) bottom left, B (w,h) the right angle at the bottom right,
 * C (w,0) top right. Each edge has a touch band [bandWidth] wide centred on it.
 *
 * Sign convention (positive angle = the same positive turn about the object's own axis the old
 * rotation slider made for positive values): right along the bottom (A to B) is +X, up the side
 * (B to C) is +Y, up and right along the diagonal (A to C) is +Z. Dragging the full length of an
 * edge is [FULL_EDGE_DEGREES]; there is no end stop, so turns accumulate across swipes.
 */
class RotationTriangle(val width: Float, val height: Float, val bandWidth: Float) {
    private val half = bandWidth / 2f

    private fun start(e: TriangleEdge): Pair<Float, Float> = when (e) {
        TriangleEdge.BOTTOM -> 0f to height
        TriangleEdge.SIDE -> width to height
        TriangleEdge.DIAGONAL -> 0f to height
    }

    private fun end(e: TriangleEdge): Pair<Float, Float> = when (e) {
        TriangleEdge.BOTTOM -> width to height
        TriangleEdge.SIDE -> width to 0f
        TriangleEdge.DIAGONAL -> width to 0f
    }

    /** The length of [edge]. */
    fun length(edge: TriangleEdge): Float {
        val (sx, sy) = start(edge)
        val (ex, ey) = end(edge)
        return sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
    }

    /** Distance from (x, y) to the segment that is [edge]. */
    fun distanceTo(edge: TriangleEdge, x: Float, y: Float): Float {
        val (sx, sy) = start(edge)
        val (ex, ey) = end(edge)
        val dx = ex - sx
        val dy = ey - sy
        val t = (((x - sx) * dx + (y - sy) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val px = sx + t * dx - x
        val py = sy + t * dy - y
        return sqrt(px * px + py * py)
    }

    /**
     * The edge whose touch band holds (x, y): the nearest by distance to the edge's segment (ties go
     * bottom, side, diagonal), or null when the point is outside every band (the interior of the
     * triangle included).
     */
    fun edgeAt(x: Float, y: Float): TriangleEdge? =
        TriangleEdge.entries.minByOrNull { distanceTo(it, x, y) }?.takeIf { distanceTo(it, x, y) <= half }

    /** Whether (x, y) is in any touch band. */
    fun inBand(x: Float, y: Float): Boolean = edgeAt(x, y) != null

    /** The centre of the circle inside the triangle, as far from every edge as it can be. */
    fun incentre(): Pair<Float, Float> {
        val r = (width + height - sqrt(width * width + height * height)) / 2f
        return (width - r) to (height - r)
    }

    /**
     * The signed angle for a drag along [edge] from (sx, sy) to (cx, cy): the movement projected onto the
     * edge's direction over the edge's length, times [FULL_EDGE_DEGREES].
     */
    fun angle(edge: TriangleEdge, sx: Float, sy: Float, cx: Float, cy: Float): Float {
        val (ax, ay) = start(edge)
        val (bx, by) = end(edge)
        val len = length(edge)
        val along = ((cx - sx) * (bx - ax) + (cy - sy) * (by - ay)) / len
        return along / len * FULL_EDGE_DEGREES
    }

    /** A drag that begins at (x, y), locked to the edge there; null when the point is in no band. */
    fun beginDrag(x: Float, y: Float): Drag? = edgeAt(x, y)?.let { Drag(it, x, y) }

    /** One drag, locked to [edge] from where it began. */
    inner class Drag internal constructor(val edge: TriangleEdge, private val sx: Float, private val sy: Float) {
        /** The angle of the whole drag so far. */
        var total: Float = 0f
            private set

        /** Moves the finger to (x, y); returns the angle gained since the last move (negative going back). */
        fun moveTo(x: Float, y: Float): Float {
            val now = angle(edge, sx, sy, x, y)
            val delta = now - total
            total = now
            return delta
        }

        /** The live readout, e.g. "X 37°". */
        fun readout(): String = "${edge.axis.name} ${total.roundToInt()}°"
    }

    /**
     * The touch bands as polygons (each edge's band is a capsule: a rectangle with half-circle ends),
     * the same region [inBand] tests, for clipping the gizmo's input to the bands.
     */
    fun bandOutline(): List<List<Pair<Float, Float>>> = TriangleEdge.entries.map { capsule(it) }

    private fun capsule(e: TriangleEdge): List<Pair<Float, Float>> {
        val (ax, ay) = start(e)
        val (bx, by) = end(e)
        val len = length(e)
        val dx = (bx - ax) / len
        val dy = (by - ay) / len
        val nx = -dy
        val ny = dx
        val phi = atan2(ny, nx)
        val pts = ArrayList<Pair<Float, Float>>()
        pts += (ax + nx * half) to (ay + ny * half)
        pts += (bx + nx * half) to (by + ny * half)
        for (i in 1 until CAP_STEPS) {
            val a = phi - PI * i / CAP_STEPS
            pts += (bx + half * cos(a).toFloat()) to (by + half * sin(a).toFloat())
        }
        pts += (bx - nx * half) to (by - ny * half)
        pts += (ax - nx * half) to (ay - ny * half)
        for (i in 1 until CAP_STEPS) {
            val a = phi - PI - PI * i / CAP_STEPS
            pts += (ax + half * cos(a).toFloat()) to (ay + half * sin(a).toFloat())
        }
        return pts
    }

    companion object {
        /** A drag the full length of an edge turns this far. */
        const val FULL_EDGE_DEGREES = 180f

        /** The accessibility actions' step. */
        const val ACCESSIBILITY_STEP_DEGREES = 15f

        private const val CAP_STEPS = 24

        /** Even-odd point-in-polygon. */
        fun polygonContains(poly: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val (xi, yi) = poly[i]
                val (xj, yj) = poly[j]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
