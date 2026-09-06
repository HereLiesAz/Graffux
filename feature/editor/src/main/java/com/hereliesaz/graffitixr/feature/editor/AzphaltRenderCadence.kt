package com.hereliesaz.graffitixr.feature.editor

/**
 * Presentation cadence gate for live brush rendering.
 *
 * Input samples must be recorded before this gate is consulted. A false return means only
 * "don't render a new preview yet"; it must never mean "drop this physical input sample".
 */
class AzphaltRenderCadence {
    private var lastPresentedMs: Long = 0L

    fun reset() {
        lastPresentedMs = 0L
    }

    fun shouldRender(nowMs: Long, rateHz: Int): Boolean {
        if (rateHz <= 0) {
            lastPresentedMs = nowMs
            return true
        }
        if (lastPresentedMs == 0L) {
            lastPresentedMs = nowMs
            return true
        }
        val minGapMs = (1000L / rateHz).coerceAtLeast(1L)
        if (nowMs - lastPresentedMs < minGapMs) return false
        lastPresentedMs = nowMs
        return true
    }
}
