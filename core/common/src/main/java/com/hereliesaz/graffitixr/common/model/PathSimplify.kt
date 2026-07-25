// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/PathSimplify.kt
package com.hereliesaz.graffitixr.common.model

import kotlin.math.hypot

/**
 * Ramer–Douglas–Peucker poly-line simplification — drops points that lie within [epsilon] of the line
 * they'd otherwise bend, keeping the overall shape. Used to clean a freeform pen stroke (which
 * captures a point per touch event) into a tidy vector path. Pure and unit-tested; points are
 * interleaved `[x0,y0,x1,y1,…]`.
 */
object PathSimplify {

    /** Returns a simplified copy of [points]; unchanged if fewer than 3 points or [epsilon] <= 0. */
    fun rdp(points: List<Float>, epsilon: Float): List<Float> {
        val n = points.size / 2
        if (n < 3 || epsilon <= 0f) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        simplify(points, 0, n - 1, epsilon, keep)
        val out = ArrayList<Float>(points.size)
        for (i in 0 until n) if (keep[i]) { out.add(points[2 * i]); out.add(points[2 * i + 1]) }
        return out
    }

    /**
     * Explicit-stack (not recursive) so a long, low-curvature stroke — thousands of points where
     * each RDP split only shaves off one or two points, e.g. a near-straight or gently-spiraling
     * drag — can't drive recursion depth anywhere near the point count and risk a StackOverflowError.
     */
    private fun simplify(p: List<Float>, start: Int, end: Int, eps: Float, keep: BooleanArray) {
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(start, end))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e <= s + 1) continue
            val ax = p[2 * s]; val ay = p[2 * s + 1]
            val bx = p[2 * e]; val by = p[2 * e + 1]
            var maxDist = -1f
            var idx = -1
            for (i in s + 1 until e) {
                val d = perpDistance(p[2 * i], p[2 * i + 1], ax, ay, bx, by)
                if (d > maxDist) { maxDist = d; idx = i }
            }
            if (maxDist > eps && idx >= 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
    }

    /** Perpendicular distance from ([px],[py]) to the infinite line through ([ax],[ay])–([bx],[by]). */
    private fun perpDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = ((px - ax) * dx + (py - ay) * dy) / len2
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
