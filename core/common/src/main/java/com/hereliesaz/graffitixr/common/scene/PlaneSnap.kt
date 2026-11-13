package com.hereliesaz.graffitixr.common.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A depth the draw plane snapped to: the [depth] itself and the [index] of the candidate it came from. */
data class DepthSnap(val depth: Float, val index: Int)

/** A view an orbit snapped to: the camera's [yaw] and [pitch] (radians), the candidate [index] and the [angle] left. */
data class OrbitSnap(val yaw: Float, val pitch: Float, val index: Int, val angle: Float)

/**
 * Snapping to planes that carry brush strokes (spec/3d-space.md, "Snap to stroked planes"). Pure maths; it never
 * moves a stroke or a layer, it only chooses a draw depth or a camera angle. The snap is magnetic and has no memory:
 * it is a function of the raw (unsnapped) value alone, so a value outside the capture range is left as it is, and
 * there is no hysteresis (the view-model feeds each step the raw value, not the snapped one).
 */
@Suppress("MagicNumber", "ReturnCount")
object PlaneSnap {
    /** The depth capture range: a candidate within this fraction of the effective distance pulls the depth in. */
    const val DEPTH_CAPTURE = 0.06f

    /**
     * The orbit capture range, radians: a stroked plane seen within this angle of face-on pulls the view in.
     * An assumption (the owner said "about 5 degrees"), kept as the one constant to tune.
     */
    const val ORBIT_CAPTURE_DEGREES = 5f
    const val ORBIT_CAPTURE_RADIANS: Float = (ORBIT_CAPTURE_DEGREES * PI / 180.0).toFloat()

    /**
     * The eye-to-plane distance of the plane through scene point [centre], parallel to the draw plane's reference
     * (its normal is the axis the draw plane slides along), measured as the draw plane's own depth is: the camera
     * depth of its box centre. With no usable [reference] the axis is the view direction and this is [centre]'s own
     * camera depth. Null when it is not in front of the eye.
     */
    fun planeDepth(reference: Affine3?, cam: CameraFrame, pivot: FloatArray?, centre: FloatArray): Float? {
        val usable = reference?.takeIf { DrawPlane.isUsable(cam, it) }
        val depth = if (usable == null) {
            cam.toCamera(centre)[2]
        } else {
            val raw = SceneMath.normalize(usable.column(2))
            val toward = if (SceneMath.dot(raw, cam.forward) > 0f) FloatArray(3) { -raw[it] } else raw
            val facing = -SceneMath.dot(toward, cam.forward)
            val at = DrawPlane.anchor(usable, cam, pivot)
            val along = SceneMath.dot(toward, FloatArray(3) { centre[it] - at[it] })
            cam.toCamera(at)[2] - facing * along
        }
        return depth.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * The candidate depth nearest [effective], when it lies within [capture] of it (a fraction of [effective]).
     * Ties go to the nearer plane (smaller depth), then to the lower index. Null when none is in range.
     */
    fun snapDepth(effective: Float, candidates: FloatArray, capture: Float = DEPTH_CAPTURE): DepthSnap? {
        if (!effective.isFinite() || effective <= 0f) return null
        val reach = capture * effective
        var best = -1
        for (i in candidates.indices) {
            if (beats(candidates, i, best, effective, reach)) best = i
        }
        return if (best < 0) null else DepthSnap(candidates[best], best)
    }

    /** Whether candidate [i] is in [reach] of [effective] and nearer to it than [best] (ties: the nearer plane). */
    private fun beats(candidates: FloatArray, i: Int, best: Int, effective: Float, reach: Float): Boolean {
        val c = candidates[i]
        val gap = abs(c - effective)
        if (!c.isFinite() || gap > reach) return false
        if (best < 0) return true
        val bestGap = abs(candidates[best] - effective)
        return gap < bestGap || (gap == bestGap && c < candidates[best])
    }

    /** The camera's view direction for [yaw] and [pitch] (the one [SceneMath.frame] builds). */
    fun forwardOf(yaw: Float, pitch: Float): FloatArray =
        floatArrayOf(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw))

    /** [angle] wrapped to (-PI, PI]. */
    fun wrap(angle: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var a = angle % twoPi
        if (a > PI) a -= twoPi
        if (a <= -PI) a += twoPi
        return a
    }

    /**
     * The face-on view nearest the one at [yaw] and [pitch], when within [range] of it: the camera looking straight
     * along one of the plane [normals] (either way: a plane is seen face-on from both sides). Ties go to the lower
     * index. A face-on view the pitch limit forbids (a plane facing straight up or down) is not offered.
     */
    fun snapOrbit(
        yaw: Float,
        pitch: Float,
        normals: List<FloatArray>,
        range: Float = ORBIT_CAPTURE_RADIANS,
    ): OrbitSnap? {
        val f = forwardOf(yaw, pitch)
        var best: OrbitSnap? = null
        for (i in normals.indices) {
            val hit = orbitCandidate(f, yaw, normals[i], i, range) ?: continue
            if (best == null || hit.angle < best.angle) best = hit
        }
        return best
    }

    /** The face-on view for plane normal [raw] (candidate [index]) seen along [f], or null when out of [range]. */
    private fun orbitCandidate(f: FloatArray, yaw: Float, raw: FloatArray, index: Int, range: Float): OrbitSnap? {
        val length = sqrt(SceneMath.dot(raw, raw))
        if (!length.isFinite() || length < 1e-6f) return null
        val n = FloatArray(3) { raw[it] / length }
        val cosine = SceneMath.dot(f, n)
        val dir = if (cosine >= 0f) n else FloatArray(3) { -n[it] }
        val angle = acos(abs(cosine).coerceIn(0f, 1f))
        val targetPitch = asin(dir[1].coerceIn(-1f, 1f))
        if (angle > range || abs(targetPitch) > SceneCamera.MAX_PITCH) return null
        // Straight up or down has no yaw of its own: keep the one the view has.
        val flat = dir[0] * dir[0] + dir[2] * dir[2]
        val targetYaw = if (flat < 1e-6f) yaw else yaw + wrap(atan2(dir[0], dir[2]) - yaw)
        return OrbitSnap(targetYaw, targetPitch, index, angle)
    }
}
