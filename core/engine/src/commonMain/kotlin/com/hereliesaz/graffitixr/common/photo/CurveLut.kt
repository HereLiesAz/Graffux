package com.hereliesaz.graffitixr.common.photo

import kotlin.math.sqrt

/**
 * A curve's 256-entry lookup table: a monotone cubic (Fritsch–Carlson) through its control points,
 * flat beyond the first and last. The same spline as the editor's Curves dialog
 * (`CurvesUtil.calculateAdjustmentCurve`, feature/editor), which is Android-only and bakes an
 * Android bitmap; this one only builds the table, so the GPU and the desktop can share it.
 */
object CurveLut {
    fun build(points: List<CurvePoint>): FloatArray {
        val pts = points.sortedBy { it.x }.distinctBy { it.x }
        val out = FloatArray(256)
        if (pts.size < 2) {
            for (i in 0 until 256) out[i] = pts.firstOrNull()?.y?.coerceIn(0f, 1f) ?: (i / 255f)
            return out
        }
        val n = pts.size
        val dx = FloatArray(n - 1) { pts[it + 1].x - pts[it].x }
        val slope = FloatArray(n - 1) { (pts[it + 1].y - pts[it].y) / dx[it] }
        val m = FloatArray(n)
        m[0] = slope[0]
        m[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (slope[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / slope[i]
            val b = m[i + 1] / slope[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / sqrt(h)
                m[i] = t * a * slope[i]
                m[i + 1] = t * b * slope[i]
            }
        }
        var seg = 0
        for (i in 0 until 256) {
            val x = i / 255f
            out[i] = when {
                x <= pts[0].x -> pts[0].y
                x >= pts[n - 1].x -> pts[n - 1].y
                else -> {
                    while (seg < n - 2 && x > pts[seg + 1].x) seg++
                    val h = dx[seg]
                    val t = (x - pts[seg].x) / h
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * pts[seg].y + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * pts[seg + 1].y + (t3 - t2) * h * m[seg + 1]
                }
            }.coerceIn(0f, 1f)
        }
        return out
    }
}
