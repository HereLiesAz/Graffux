// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/QuickShape.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Procreate's QuickShape: when a stroke ends in a hold (the finger stops and stays down), the
 * wobbly freehand trace snaps to the ideal shape it approximates. This is the pure recognizer —
 * geometry in, verdict out — so it's unit-testable without any UI.
 *
 * Recognized shapes, tried in order:
 *  - [Line]: the points hug the chord between first and last.
 *  - [Ellipse]: the trace closes on itself and the points hug an axis-aligned ellipse fit.
 *
 * Anything else returns null and the stroke commits as drawn.
 */
object QuickShape {

    sealed interface Shape
    data class Line(val start: Offset, val end: Offset) : Shape
    data class Ellipse(val center: Offset, val radiusX: Float, val radiusY: Float) : Shape

    /** How long the finger must sit still at the end of a stroke to trigger snapping (ms). */
    const val HOLD_MS = 550L

    /** Movement below this (px) counts as "holding still". */
    const val HOLD_SLOP_PX = 9f

    fun recognize(points: List<Offset>): Shape? {
        if (points.size < 8) return null
        return recognizeLine(points) ?: recognizeEllipse(points)
    }

    /** A line if no point strays from the first→last chord by more than ~3% of its length. */
    private fun recognizeLine(points: List<Offset>): Line? {
        val a = points.first()
        val b = points.last()
        val chord = (b - a).getDistance()
        if (chord < 24f) return null
        val tolerance = max(10f, chord * 0.035f)
        val dx = b.x - a.x
        val dy = b.y - a.y
        for (p in points) {
            // Perpendicular distance from p to the infinite line through a-b.
            val dist = abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / chord
            if (dist > tolerance) return null
        }
        return Line(a, b)
    }

    /**
     * An ellipse if the trace closes (ends near its start relative to its length) and every point
     * sits near the axis-aligned ellipse inscribed in the trace's bounding box. Circles are just
     * the rx≈ry case and need no special handling.
     */
    private fun recognizeEllipse(points: List<Offset>): Ellipse? {
        var length = 0f
        for (i in 1 until points.size) length += (points[i] - points[i - 1]).getDistance()
        if (length < 120f) return null
        val closingGap = (points.last() - points.first()).getDistance()
        if (closingGap > length * 0.18f) return null

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val rx = (maxX - minX) / 2f
        val ry = (maxY - minY) / 2f
        if (rx < 16f || ry < 16f) return null
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f

        // Radial fit in the normalized space where the ellipse is the unit circle: for each point,
        // |(x-cx)/rx, (y-cy)/ry| should be ~1. RMS error over all points decides.
        var sumSq = 0f
        for (p in points) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            val r = hypot(nx, ny)
            val e = r - 1f
            sumSq += e * e
        }
        val rms = sqrt(sumSq / points.size)
        if (rms > 0.16f) return null
        return Ellipse(Offset(cx, cy), rx, ry)
    }
}
