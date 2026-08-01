package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.ELLIPSE_VERTICES
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.SelectionShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure polygon maths for [Selection] — no Android graphics, so it is unit-testable directly and
 * usable from gesture code that must decide *now*, on the main thread, whether a finger landed
 * inside the selection (a move) or outside it (a new lasso).
 *
 * The Android-side rasterisation of the same region (clip paths, flood-fill masks) lives in the
 * editor's `SelectionMask`; both derive from the same closed polygon, so they agree.
 */
object SelectionGeometry {

    /**
     * True when [point] (screen space) lies in the selected region, honouring
     * [Selection.inverted].
     *
     * The rings are replayed in order — an additive ring ors its interior in, a subtractive one ands
     * it out — which is exactly what `Path.op(Union)`/`op(Difference)` does to the same rings in the
     * rasteriser. The two must agree: this decides whether a finger landed on the selection (a move)
     * while that decides which pixels get painted, and a disagreement is a drag that grabs a region
     * the paint won't honour.
     *
     * [featherPx] is deliberately ignored. A feathered edge is a gradient, and a gesture needs a
     * yes or a no; the hard boundary is the honest midpoint of the ramp.
     */
    fun contains(selection: Selection, point: Offset): Boolean {
        if (!selection.isUsable) return false
        var inside = false
        for (ring in selection.rings) {
            if (!ring.isUsable) continue
            // Short-circuit: an additive ring can't change an already-inside point, and a
            // subtractive one can't change an already-outside one. Worth having because a magic-wand
            // contour runs to hundreds of vertices and this is called per pointer event.
            if (ring.additive == inside) continue
            if (insidePolygon(ring.path, point)) inside = ring.additive
        }
        return if (selection.inverted) !inside else inside
    }

    /** Even-odd containment for the raw polygon, ignoring [Selection.inverted]. */
    fun insidePolygon(path: List<Offset>, point: Offset): Boolean {
        if (path.size < 3) return false
        var inside = false
        var j = path.size - 1
        for (i in path.indices) {
            val a = path[i]
            val b = path[j]
            // Does the edge b→a straddle the ray's y? Half-open on purpose ([min, max)) so a
            // vertex shared by two edges is counted once, not twice or zero times.
            if ((a.y > point.y) != (b.y > point.y)) {
                val t = (point.y - a.y) / (b.y - a.y)
                if (point.x < a.x + t * (b.x - a.x)) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Drops points closer than [minSpacing] to the previously kept one. A lasso traced with a
     * finger arrives at touch-event rate and can run to thousands of points; the clip path and the
     * marquee only need enough to be smooth, and every retained point is re-mapped on every paint
     * operation the selection clips.
     */
    fun simplify(path: List<Offset>, minSpacing: Float = 3f): List<Offset> {
        if (path.size < 3) return path
        val out = ArrayList<Offset>(path.size)
        out.add(path.first())
        for (p in path) {
            if ((p - out.last()).getDistance() >= minSpacing) out.add(p)
        }
        // A closed polygon gains nothing from a final point sitting on top of the first.
        if (out.size > 2 && (out.last() - out.first()).getDistance() < minSpacing) {
            out.removeAt(out.size - 1)
        }
        return if (out.size >= 3) out else path
    }

    /**
     * The rectangle spanned by two opposite corners, as a closed polygon wound consistently
     * regardless of which way the drag went. [insidePolygon] is even-odd, so winding doesn't change
     * containment — but the marquee is drawn edge by edge, and a rectangle whose corners arrive in
     * the order top-left, top-right, bottom-left, bottom-right draws a bow tie.
     */
    fun rectangle(a: Offset, b: Offset): List<Offset> {
        val left = minOf(a.x, b.x)
        val right = maxOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        val bottom = maxOf(a.y, b.y)
        return listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(right, bottom),
            Offset(left, bottom),
        )
    }

    /**
     * The ellipse inscribed in the box spanned by two opposite corners, sampled into
     * [ELLIPSE_VERTICES] points.
     *
     * A polygon rather than a curve because that is what a [Selection] is: containment, clipping and
     * the marching ants all walk the vertex list, so an ellipse that stayed parametric would need
     * every one of them taught a second representation.
     */
    fun ellipse(a: Offset, b: Offset, vertices: Int = ELLIPSE_VERTICES): List<Offset> {
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val rx = abs(a.x - b.x) / 2f
        val ry = abs(a.y - b.y) / 2f
        // The last vertex is not the first: Selection's path is implicitly closed, and repeating the
        // start point would put a zero-length edge in every consumer that walks the list.
        return List(vertices) { i ->
            val t = (2.0 * PI * i) / vertices
            Offset(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
        }
    }

    /**
     * Folds a freshly drawn [polygon] into the selection already on the canvas under [op].
     *
     * Three things this handles that a naive append would not:
     *
     *  - **Removing from nothing** leaves nothing. There is no implicit "everything is selected"
     *    state to cut out of — with no selection every tool already paints everywhere — so a Remove
     *    with nothing selected is a no-op rather than an inversion.
     *  - **Adding to nothing** is just a new selection, so Add works as the first stroke of a
     *    multi-part selection without the user having to start in New and switch.
     *  - **Composing onto an inverted selection flips the ring's sense.** [Selection.inverted]
     *    applies to the whole stack, so an additive ring under it would *shrink* what the user can
     *    see. The flag is flipped so Add always adds to the visible region, which is the only
     *    reading of the button that isn't a lie.
     *
     * A [canvasSize] that differs from the current selection's forces a replace: the rings are
     * screen-space and a different canvas is a different space, so folding into it would put the
     * old region somewhere it was never drawn.
     */
    fun compose(
        current: Selection?,
        polygon: List<Offset>,
        canvasSize: IntSize,
        op: SelectionOp,
    ): Selection? {
        val ring = SelectionRing(polygon, additive = true)
        if (!ring.isUsable) return current
        val fresh = Selection(listOf(ring), canvasSize)
        val base = current?.takeIf { it.isUsable && it.canvasSize == canvasSize }
        return when (op) {
            SelectionOp.NEW -> fresh
            SelectionOp.ADD -> base?.let {
                it.copy(rings = it.rings + ring.copy(additive = !it.inverted))
            } ?: fresh
            SelectionOp.REMOVE -> base?.let {
                it.copy(rings = it.rings + ring.copy(additive = it.inverted))
            }
        }
    }

    /** Builds the polygon a completed drag from [start] to [end] means, under [shape]. */
    fun polygonFor(shape: SelectionShape, start: Offset, end: Offset, traced: List<Offset>): List<Offset> =
        when (shape) {
            SelectionShape.FREEHAND -> traced
            SelectionShape.RECTANGLE -> rectangle(start, end)
            SelectionShape.ELLIPSE -> ellipse(start, end)
        }
}
