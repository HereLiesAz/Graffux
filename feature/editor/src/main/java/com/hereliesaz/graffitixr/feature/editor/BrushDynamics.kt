// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BrushDynamics.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import kotlin.math.min

/**
 * Velocity-responsive brush width — the dynamics that make a stroke read as painted rather than
 * plotted. A fast flick thins out; a slow, deliberate line stays full; the first few segments ramp
 * up so the stroke starts with a taper instead of a blunt stamp.
 *
 * Everything here is DETERMINISTIC from the stroke's point list: the same recursion runs
 * incrementally during the live stroke (via [State]) and from scratch on commit and on undo/redo
 * replay (via [segmentWidths]), and produces bit-identical widths in all three, so a replayed
 * stroke re-rasterizes exactly as it was painted.
 *
 * The recursion is scale-invariant: speed enters only as the ratio (segment length / brush size),
 * so it can be fed screen-space points with the screen brush size, or bitmap-space points with the
 * bitmap brush size, and yield the same factors.
 */
object BrushDynamics {

    /** Segments over which the start taper ramps from [START_WIDTH_FRACTION] up to full width. */
    private const val START_RAMP_SEGMENTS = 6
    private const val START_WIDTH_FRACTION = 0.35f

    /** EMA weight for the smoothed speed (higher = twitchier response). */
    private const val SPEED_ALPHA = 0.3f

    /** Speed (in brush-diameters per segment) at which thinning saturates, and the max thinning. */
    private const val SPEED_SATURATION = 4f
    private const val MAX_THINNING = 0.6f

    /**
     * The incremental form: feed each new segment's length as it arrives and get that segment's
     * width. Seed one per stroke; the sequence of returned widths equals [segmentWidths] run over
     * the same points.
     */
    class State {
        private var smoothedSpeed = 0f
        private var segment = 0

        /** Width for the segment of length [segmentLength], for a brush of [baseWidth]. Both in the same space. */
        fun next(segmentLength: Float, baseWidth: Float): Float {
            val base = if (baseWidth <= 0f) return 0f else baseWidth
            val speed = segmentLength / base
            smoothedSpeed = SPEED_ALPHA * speed + (1 - SPEED_ALPHA) * smoothedSpeed
            val thin = 1f - min(MAX_THINNING, smoothedSpeed / SPEED_SATURATION * MAX_THINNING)
            val ramp = START_WIDTH_FRACTION +
                (1f - START_WIDTH_FRACTION) * min(1f, (segment + 1).toFloat() / START_RAMP_SEGMENTS)
            segment++
            return base * thin * ramp
        }
    }

    /**
     * The batch form: widths for every segment of [points] (size = points.size - 1), for a brush of
     * [baseWidth] in the same coordinate space as the points.
     */
    fun segmentWidths(points: List<Offset>, baseWidth: Float): FloatArray {
        if (points.size < 2) return FloatArray(0)
        val state = State()
        return FloatArray(points.size - 1) { i ->
            state.next((points[i + 1] - points[i]).getDistance(), baseWidth)
        }
    }
}
