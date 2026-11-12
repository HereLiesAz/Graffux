package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import kotlin.math.PI
import kotlin.math.hypot

/**
 * Whether the project has a 3D group, which switches the view gestures to orbit control
 * (spec/3d-space.md, "Planned: view control in a project with 3D groups"). A 3D group is a group
 * with a valid surface profile ([hasSurface]). This is the ONE place that decides it: owner open
 * point 1 (should a lone 3D strokes or model layer count too?) is answered by editing this line.
 */
fun hasThreeDGroup(layers: List<Layer>): Boolean = layers.any { it.hasSurface }

/** What a two-finger drag does to the view. Pinch and twist are the same in both modes. */
enum class TwoFingerViewMode { PAN, ORBIT }

/** Two-finger drag orbits in a project with a 3D group ([hasThreeDGroup]) and pans in any other. */
fun twoFingerViewMode(layers: List<Layer>): TwoFingerViewMode =
    if (hasThreeDGroup(layers)) TwoFingerViewMode.ORBIT else TwoFingerViewMode.PAN

/**
 * The drag-to-angle mapping for orbit: dragging the fingers one full canvas width turns the view
 * this many radians (a half turn). The same pixels-per-radian applies to both axes, so a diagonal
 * drag turns evenly. Pitch is clamped by [SceneCamera.orbit] ([SceneCamera.MAX_PITCH]).
 */
const val ORBIT_FULL_WIDTH_RADIANS: Float = PI.toFloat()

/** Radians of orbit per screen pixel of two-finger drag on a canvas [canvasWidth] pixels wide. */
fun orbitRadiansPerPixel(canvasWidth: Float): Float =
    if (canvasWidth > 0f && canvasWidth.isFinite()) ORBIT_FULL_WIDTH_RADIANS / canvasWidth else 0f

/**
 * (yaw, pitch) radians for a drag of [dx], [dy] pixels. The view follows the fingers like a
 * turntable: dragging right brings the near side of the content right, dragging down brings it down.
 */
fun orbitAnglesForDrag(dx: Float, dy: Float, canvasWidth: Float): Pair<Float, Float> {
    val k = orbitRadiansPerPixel(canvasWidth)
    return Pair(dx * k, dy * k)
}

/**
 * Recognises a double-tap from taps fed in order: a tap within [timeoutMs] and [slopPx] of the
 * previous one is the second of a double-tap (and clears the state, so a third tap starts afresh).
 */
class ViewTapTracker(private val timeoutMs: Long = 300L, private val slopPx: Float = 100f) {
    private var lastTimeMs = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f

    /** Records a tap at ([x], [y]) at [timeMs]; true when it completes a double-tap. */
    fun onTap(x: Float, y: Float, timeMs: Long): Boolean {
        val second = lastTimeMs != Long.MIN_VALUE && timeMs - lastTimeMs in 0..timeoutMs &&
            hypot(x - lastX, y - lastY) <= slopPx
        if (second) {
            lastTimeMs = Long.MIN_VALUE
        } else {
            lastTimeMs = timeMs; lastX = x; lastY = y
        }
        return second
    }
}
