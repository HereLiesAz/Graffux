package com.hereliesaz.graffitixr.feature.editor.ink

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.common.util.StrokeStabilizer

/**
 * The app's stabilizer, applied to Jetpack Ink input before it reaches InProgressStrokesView.
 *
 * The same [StrokeStabilizer] the editor's own Brush path uses, fed the same thing: world-space
 * points (screen with the viewport camera taken out), reset at every stroke start, with pressure
 * smoothed alongside. Level 0 is a pass-through there too, but [isActive] lets the Ink host skip the
 * StrokeInput route entirely at level 0 and hand Ink the raw MotionEvents, which is the only route
 * on which Ink measures the OS event time for its latency data.
 *
 * Pure Kotlin (no Ink, no MotionEvent) so it is JVM-testable.
 */
internal class InkStabilizer(private val stabilizer: StrokeStabilizer = StrokeStabilizer()) {

    /** One stabilized sample, and how far it trails the raw point (the feel report's "lag"). */
    data class Sample(val x: Float, val y: Float, val pressure: Float, val lagPx: Float)

    fun isActive(level: Int): Boolean = level > 0

    /** A new stroke: forget the previous one's history. */
    fun reset() = stabilizer.reset()

    fun apply(x: Float, y: Float, pressure: Float, level: Int, algorithm: StabilizerAlgorithm): Sample {
        val raw = Offset(x, y)
        val point = stabilizer.stabilize(raw, level, algorithm)
        val p = stabilizer.stabilizePressure(pressure, level, algorithm)
        return Sample(point.x, point.y, p, (raw - point).getDistance())
    }
}

/**
 * Touch-to-paint for one Jetpack Ink input, from the fields of Ink's `LatencyData` (all
 * `System.nanoTime` / CLOCK_MONOTONIC nanoseconds, the clock MotionEvent times are on).
 *
 * The start is the OS's event time when Ink has it — the MotionEvent route. On the StrokeInput
 * route (the stabilizer is on) Ink has no OS time and records only when the view got the input, so
 * that is used instead; it leaves out delivery, which the feel report's "input delivery" line
 * measures separately. Null when neither start is set or the presentation estimate is missing.
 */
internal object InkLatency {
    private const val NANOS_PER_MS = 1_000_000.0

    fun latencyMs(
        osDetectsEventNs: Long,
        osDetectsEventSet: Boolean,
        viewGetsActionNs: Long,
        viewGetsActionSet: Boolean,
        presentedNs: Long,
    ): Double? {
        val start = when {
            osDetectsEventSet && osDetectsEventNs > 0L -> osDetectsEventNs
            viewGetsActionSet && viewGetsActionNs > 0L -> viewGetsActionNs
            else -> null
        }
        return if (start == null || presentedNs <= 0L) null else (presentedNs - start) / NANOS_PER_MS
    }
}
