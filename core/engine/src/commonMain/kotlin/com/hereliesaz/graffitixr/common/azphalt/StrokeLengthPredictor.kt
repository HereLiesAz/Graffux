package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.max

/**
 * Estimates total stroke length incrementally during a live stroke.
 *
 * Fed each new [BrushSample] as it arrives via [record]; [predictedTotal] returns the current
 * best estimate of final stroke length. Used by the live-paint path to apply pressure-fade
 * approximations before the stroke commits with its true total.
 *
 * Model: kinematic deceleration. If the recent speed window shows the stroke decelerating,
 * remaining distance ≈ v²/(2·a). Fallback while speed is still rising or stable: assume the
 * stroke will travel [FALLBACK_MULTIPLIER]× the current distance. The two estimates are
 * blended by deceleration confidence — no hard switchover.
 */
class StrokeLengthPredictor {

    private val speeds = FloatArray(WINDOW)
    private var head = 0
    private var count = 0
    private var currentDistance = 0f

    /** Current best estimate of the stroke's eventual total arc length in pixels. */
    val predictedTotal: Float
        get() {
            if (count < MIN_SAMPLES) return currentDistance * FALLBACK_MULTIPLIER
            val v = speeds[(head + WINDOW - 1) % WINDOW]
            val decel = estimatedDeceleration()
            val kinematicRemaining = if (decel > 1e-6f) (v * v) / (2f * decel) else 0f
            // Blend: full kinematic when decel is high, full fallback when decel ≈ 0.
            val decelConfidence = (decel / (decel + DECEL_BLEND_SCALE)).coerceIn(0f, 1f)
            val fallbackRemaining = currentDistance * (FALLBACK_MULTIPLIER - 1f)
            val remaining = kinematicRemaining * decelConfidence + fallbackRemaining * (1f - decelConfidence)
            return (currentDistance + max(0f, remaining)).coerceAtLeast(currentDistance)
        }

    fun record(sample: BrushSample) {
        if (sample.predicted) return
        currentDistance = sample.distancePx
        speeds[head % WINDOW] = sample.speedPxPerMs
        head = (head + 1) % WINDOW
        if (count < WINDOW) count++
    }

    fun reset() {
        head = 0
        count = 0
        currentDistance = 0f
    }

    /**
     * Mean deceleration (px/ms²) across the current speed window. Negative values (acceleration)
     * are clamped to zero — only positive deceleration contributes to a finite remaining estimate.
     */
    private fun estimatedDeceleration(): Float {
        if (count < 2) return 0f
        // Compare oldest and newest readings in the circular buffer.
        val oldest = speeds[(head + WINDOW - count) % WINDOW]
        val newest = speeds[(head + WINDOW - 1) % WINDOW]
        // Time approximation: assume uniform sampling at WINDOW_DURATION_MS across the window.
        val dt = WINDOW_DURATION_MS.toFloat()
        return max(0f, (oldest - newest) / dt)
    }

    private companion object {
        const val WINDOW = 8
        const val MIN_SAMPLES = 4
        // When decelerating, samples in the window span roughly this many ms.
        const val WINDOW_DURATION_MS = 80
        // Deceleration magnitude (px/ms²) at which the kinematic estimate gets ~50% weight.
        const val DECEL_BLEND_SCALE = 0.005f
        // Fallback: assume the stroke is 1.6× the current distance when no deceleration is detected.
        const val FALLBACK_MULTIPLIER = 1.6f
    }
}
