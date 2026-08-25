package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset

/**
 * Which of Phase 2's (`docs/Native Rendering Engine Design.md` §6) three stroke-filtration
 * algorithms is active. All three read the same `0..100` level; only the maths differ.
 */
enum class StabilizerAlgorithm(val label: String) {
    /** Weighted moving average over a level-sized window of recent raw points — the original
     *  (and still default) algorithm. Velocity-dependent: a fast stroke's window covers more
     *  physical distance for the same sample count, so it smooths more the faster it moves. */
    STABILIZATION("Stabilization"),
    /** Kinematic damping: the drawn point trails the raw one like a pulled string, tension set by
     *  level. Also damps pressure change rate, so a heavy-handed jolt tapers instead of snapping. */
    STREAMLINE("StreamLine"),
    /** Single-pole low-pass filter, same weight per SAMPLE regardless of the distance it covers —
     *  velocity-INdependent, unlike STABILIZATION. Re-injects a fraction of the raw signal
     *  ("Expression") so heavy filtering doesn't read as geometrically sterile. */
    MOTION_FILTERING("Motion Filtering"),
}

/**
 * Smooths raw touch input into the point actually drawn — Phase 2 of
 * `docs/Native Rendering Engine Design.md` §6. Three algorithms ([StabilizerAlgorithm]), one
 * `0..100` level control shared across all of them.
 */
class StrokeStabilizer {

    private val history = mutableListOf<Offset>()
    // StreamLine / Motion Filtering carry a single lagged/filtered point across calls instead of a
    // history window — the previous call's OUTPUT is this call's starting point, which is what
    // makes both of them local (O(1) per point) rather than windowed.
    private var lagged: Offset? = null
    private var laggedPressure: Float = 1f

    /**
     * Stabilizes a raw input point.
     * @param rawPoint The raw input point from the touch/pointer event.
     * @param level The stabilization level (0 = disabled, 1-100 = active).
     * @param algorithm Which of [StabilizerAlgorithm]'s three approaches to apply.
     * @return The stabilized point to draw.
     */
    fun stabilize(
        rawPoint: Offset,
        level: Int,
        algorithm: StabilizerAlgorithm = StabilizerAlgorithm.STABILIZATION,
    ): Offset {
        if (level <= 0) {
            lagged = rawPoint
            return rawPoint
        }
        return when (algorithm) {
            StabilizerAlgorithm.STABILIZATION -> movingAverage(rawPoint, level)
            StabilizerAlgorithm.STREAMLINE -> streamLine(rawPoint, level)
            StabilizerAlgorithm.MOTION_FILTERING -> motionFilter(rawPoint, level)
        }
    }

    /**
     * StreamLine's own sub-parameter (companion doc §"StreamLine": "The 'Pressure' parameter...
     * smoothing out sudden heavy-handed jolts into graceful, elongated tapers") — damps how fast
     * PRESSURE is allowed to change, the same tension curve [stabilize] uses for position. A no-op
     * (returns [rawPressure] unchanged) for the other two algorithms, which the companion doc
     * doesn't describe as touching pressure at all.
     */
    fun stabilizePressure(rawPressure: Float, level: Int, algorithm: StabilizerAlgorithm): Float {
        if (level <= 0 || algorithm != StabilizerAlgorithm.STREAMLINE) {
            laggedPressure = rawPressure
            return rawPressure
        }
        laggedPressure += (rawPressure - laggedPressure) * tensionAlpha(level)
        return laggedPressure
    }

    private fun movingAverage(rawPoint: Offset, level: Int): Offset {
        // History capacity scales with level, e.g., max 20 points
        val capacity = (level / 100f * 20).toInt().coerceAtLeast(1)

        history.add(rawPoint)
        if (history.size > capacity) {
            history.removeAt(0)
        }

        var sumX = 0f
        var sumY = 0f
        var totalWeight = 0f

        // Exponential weighting: recent points matter more
        for (i in history.indices) {
            val weight = (i + 1).toFloat()
            sumX += history[i].x * weight
            sumY += history[i].y * weight
            totalWeight += weight
        }

        return Offset(sumX / totalWeight, sumY / totalWeight)
    }

    /** `level 0` → alpha 1 (raw pass-through, no lag); `level 100` → [MIN_ALPHA] (heavy lag, but
     *  never frozen solid — the ink must eventually catch up, or a held pencil would never finish
     *  its line). Shared by StreamLine and Motion Filtering; the DIFFERENCE between the two is not
     *  this curve, it's whether the smoothing is windowed (Stabilization) or a running single-pole
     *  filter (both of these) plus Motion Filtering's Expression re-injection below. */
    private fun tensionAlpha(level: Int): Float {
        val t = level.coerceIn(0, 100) / 100f
        return 1f - t * (1f - MIN_ALPHA)
    }

    private fun streamLine(rawPoint: Offset, level: Int): Offset {
        val prev = lagged ?: rawPoint
        val alpha = tensionAlpha(level)
        val next = Offset(
            prev.x + (rawPoint.x - prev.x) * alpha,
            prev.y + (rawPoint.y - prev.y) * alpha,
        )
        lagged = next
        return next
    }

    private fun motionFilter(rawPoint: Offset, level: Int): Offset {
        val prev = lagged ?: rawPoint
        val alpha = tensionAlpha(level)
        val filtered = Offset(
            prev.x + (rawPoint.x - prev.x) * alpha,
            prev.y + (rawPoint.y - prev.y) * alpha,
        )
        lagged = filtered
        // Expression: blend back some of the raw signal so heavy filtering doesn't sterilize the
        // line. Scales with how much smoothing is actually in effect (low level → negligible
        // injection, since there's barely anything to counteract yet).
        val expression = (1f - alpha) * EXPRESSION_FRACTION
        return Offset(
            filtered.x + (rawPoint.x - filtered.x) * expression,
            filtered.y + (rawPoint.y - filtered.y) * expression,
        )
    }

    /** Resets the stabilizer's history/lag state, called on onStrokeStart. */
    fun reset() {
        history.clear()
        lagged = null
        laggedPressure = 1f
    }

    private companion object {
        const val MIN_ALPHA = 0.04f
        const val EXPRESSION_FRACTION = 0.35f
    }
}
