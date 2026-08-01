package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.SelectionRing
import kotlin.math.abs

/**
 * Turns a filled region — the output of a magic-wand flood fill — into the closed polygons a
 * [com.hereliesaz.graffitixr.common.model.Selection] is made of.
 *
 * This is what lets Automatic share a representation with every other selection mode instead of
 * bolting a second one on. A flood fill naturally produces a *mask*, and a mask is not something the
 * clip path, the containment test, the marching ants or a selection move know how to read; tracing
 * it back out to polygons means the wand's output is an ordinary ring stack that moves, inverts,
 * feathers and composes with Add/Remove like anything the user drew by hand.
 *
 * Pure integer geometry over a boolean grid — no Android graphics — so it is unit-testable directly.
 */
object ContourTrace {

    /**
     * Every boundary of the true region of [mask], as rings in mask coordinates.
     *
     * A traced region can have holes — tap the background around a doughnut and the doughnut is one
     * — so this returns outer boundaries as additive rings and holes as subtractive ones, in that
     * order. The ring stack already means "union these, cut those", so a holed region needs no
     * special case anywhere downstream.
     *
     * The boundaries follow pixel edges, so the polygons are exact: a ring passes along the outside
     * of the last selected pixel rather than through its centre.
     */
    fun contours(mask: BooleanArray, width: Int, height: Int): List<SelectionRing> {
        if (width <= 0 || height <= 0 || mask.size < width * height) return emptyList()

        fun inside(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && mask[y * width + x]

        // Grid vertices, not pixels: a w×h image has (w+1)×(h+1) corners.
        val stride = width + 1
        fun key(x: Int, y: Int) = y * stride + x

        // One directed edge per pixel side that faces out of the region, wound so the region is
        // always on the same hand. That winding is what makes the shoelace sign below meaningful:
        // outer boundaries come out one way round and holes the other, with no need to test
        // containment of one loop against another.
        val outgoing = HashMap<Int, ArrayDeque<Int>>()
        fun edge(fromX: Int, fromY: Int, toX: Int, toY: Int) {
            outgoing.getOrPut(key(fromX, fromY)) { ArrayDeque() }.addLast(key(toX, toY))
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!inside(x, y)) continue
                if (!inside(x, y - 1)) edge(x, y, x + 1, y)             // top,    →
                if (!inside(x + 1, y)) edge(x + 1, y, x + 1, y + 1)     // right,  ↓
                if (!inside(x, y + 1)) edge(x + 1, y + 1, x, y + 1)     // bottom, ←
                if (!inside(x - 1, y)) edge(x, y + 1, x, y)             // left,   ↑
            }
        }
        if (outgoing.isEmpty()) return emptyList()

        val outer = ArrayList<SelectionRing>()
        val holes = ArrayList<SelectionRing>()
        // Chain the edges into closed loops. Every vertex has as many outgoing edges as incoming, so
        // following one always returns to where it started; a vertex where the region pinches to a
        // point has two of each, and taking them in any order still consumes them all.
        val starts = outgoing.keys.toList()
        for (start in starts) {
            while (true) {
                val first = outgoing[start]?.removeFirstOrNull() ?: break
                val loop = ArrayList<Int>()
                loop.add(start)
                var at = first
                while (at != start) {
                    loop.add(at)
                    at = outgoing[at]?.removeFirstOrNull() ?: break
                }
                if (loop.size < 4) continue
                val points = loop.map { Offset((it % stride).toFloat(), (it / stride).toFloat()) }
                val simplified = dropCollinear(points)
                if (simplified.size < 3) continue
                // Shoelace, in a y-down space: positive is an outer boundary, negative a hole.
                if (signedArea(simplified) > 0f) {
                    outer.add(SelectionRing(simplified, additive = true))
                } else {
                    holes.add(SelectionRing(simplified, additive = false))
                }
            }
        }
        // Outers first: a hole cut before the region that contains it has been added would cut out
        // of nothing, and then be filled back in by that region.
        return outer + holes
    }

    /**
     * Twice the signed area, by the shoelace formula. Only the sign is used, so the factor of two
     * is left in.
     */
    fun signedArea(points: List<Offset>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        var j = points.size - 1
        for (i in points.indices) {
            sum += points[j].x * points[i].y - points[i].x * points[j].y
            j = i
        }
        return sum
    }

    /**
     * Drops vertices that lie on the straight line between their neighbours.
     *
     * A pixel-edge boundary is nothing but axis-aligned unit steps, so a flat run a thousand pixels
     * long arrives as a thousand vertices describing a single line. Every one of them would be
     * re-mapped on every paint operation the selection clips.
     *
     * Unlike [SelectionGeometry.simplify] this is lossless — it only removes points that were
     * already redundant — which is why it runs first and why the wand's output keeps its exact
     * staircase where the boundary actually turns.
     */
    fun dropCollinear(points: List<Offset>): List<Offset> {
        if (points.size < 3) return points
        val out = ArrayList<Offset>(points.size)
        for (i in points.indices) {
            val prev = points[(i - 1 + points.size) % points.size]
            val cur = points[i]
            val next = points[(i + 1) % points.size]
            val cross = (cur.x - prev.x) * (next.y - prev.y) - (cur.y - prev.y) * (next.x - prev.x)
            if (abs(cross) > 1e-3f) out.add(cur)
        }
        return if (out.size >= 3) out else points
    }
}
