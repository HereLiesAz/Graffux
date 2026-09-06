package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.hypot

/**
 * Stateful arc-length placement for live strokes.
 *
 * Unlike [BrushStamps.place], this never walks the already-consumed stroke again. Each appended
 * point costs O(1) plus the dabs emitted by that segment. The first point is emitted immediately,
 * matching BrushStamps.place(), and subsequent centres are spaced continuously across segment
 * boundaries.
 */
class IncrementalDabPlacer(stepPx: Float) {
    private val step = if (stepPx > 0f) stepPx else 0.01f
    private var started = false
    private var lastX = 0f
    private var lastY = 0f
    private var distanceSinceLastDab = 0f

    /** Total path distance consumed by this placer. */
    var travelledPx: Float = 0f
        private set

    /** Number of centres emitted so far. */
    var emittedCount: Int = 0
        private set

    fun append(x: Float, y: Float): List<Float> {
        if (!started) {
            started = true
            lastX = x
            lastY = y
            emittedCount = 1
            return listOf(x, y)
        }

        val dx = x - lastX
        val dy = y - lastY
        val segmentLength = hypot(dx, dy)
        if (segmentLength == 0f) {
            lastX = x
            lastY = y
            return emptyList()
        }

        val out = ArrayList<Float>()
        var consumedOnSegment = 0f
        var remainingToNext = step - distanceSinceLastDab

        while (remainingToNext <= segmentLength - consumedOnSegment + EPSILON) {
            consumedOnSegment += remainingToNext
            val t = (consumedOnSegment / segmentLength).coerceIn(0f, 1f)
            out.add(lastX + dx * t)
            out.add(lastY + dy * t)
            emittedCount += 1
            distanceSinceLastDab = 0f
            remainingToNext = step
        }

        val tail = segmentLength - consumedOnSegment
        distanceSinceLastDab += tail
        travelledPx += segmentLength
        lastX = x
        lastY = y
        return out
    }

    fun append(points: List<Float>): List<Float> {
        val out = ArrayList<Float>()
        var i = 0
        while (i + 1 < points.size) {
            out.addAll(append(points[i], points[i + 1]))
            i += 2
        }
        return out
    }

    fun reset() {
        started = false
        lastX = 0f
        lastY = 0f
        distanceSinceLastDab = 0f
        travelledPx = 0f
        emittedCount = 0
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
