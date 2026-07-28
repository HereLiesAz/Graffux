package com.hereliesaz.graffitixr.common.mesh

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A turntable camera: it looks at a [target] from a [distance], rotated by [yaw] and [pitch].
 *
 * The orbit model is what makes a model inspectable with two fingers — drag rotates, pinch dollies,
 * two-finger drag pans the target. Because the camera is described by four numbers rather than a
 * free matrix, it can never end up rolled, inside the model, or gimbal-locked, which is exactly the
 * set of states a free-flying camera strands people in.
 *
 * Matrices are column-major `FloatArray(16)`, the layout OpenGL and `android.opengl.Matrix` use, so
 * the renderer can hand them straight to `glUniformMatrix4fv`. Pure maths — no Android, so all of
 * it is unit-testable.
 */
data class OrbitCamera(
    val target: FloatArray = floatArrayOf(0f, 0f, 0f),
    val distance: Float = 3f,
    /** Rotation about the world Y axis, radians. */
    val yaw: Float = 0f,
    /** Elevation, radians. Clamped shy of the poles — see [orbit]. */
    val pitch: Float = 0.3f,
    val fovYDegrees: Float = 45f,
    val near: Float = 0.01f,
    val far: Float = 1000f,
) {
    /** The camera's world-space position, derived from the orbit parameters. */
    fun eye(): FloatArray {
        val cp = cos(pitch)
        return floatArrayOf(
            target[0] + distance * cp * sin(yaw),
            target[1] + distance * sin(pitch),
            target[2] + distance * cp * cos(yaw),
        )
    }

    /**
     * Rotates the camera. Pitch is clamped just short of straight up/down: at exactly the pole the
     * view direction becomes parallel to the up vector and the look-at basis collapses, so the
     * clamp is what keeps the camera from flipping over at the top of a drag.
     */
    fun orbit(deltaYaw: Float, deltaPitch: Float): OrbitCamera = copy(
        yaw = yaw + deltaYaw,
        pitch = (pitch + deltaPitch).coerceIn(-MAX_PITCH, MAX_PITCH),
    )

    /**
     * Dollies in or out by a pinch [factor]. Multiplicative so each pinch feels the same at any
     * distance, and floored above zero so the camera can't land on the target and invert.
     */
    fun dolly(factor: Float): OrbitCamera =
        copy(distance = (distance / factor.coerceAtLeast(1e-3f)).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /**
     * Slides the target across the view plane. The pan is expressed in the camera's own right/up
     * axes and scaled by distance, so dragging moves the model the same apparent amount whether
     * you're up close or far out.
     */
    fun pan(dx: Float, dy: Float): OrbitCamera {
        val forward = normalize(sub(target, eye()))
        val right = normalize(cross(forward, WORLD_UP))
        val up = cross(right, forward)
        val scale = distance * PAN_SENSITIVITY
        return copy(
            target = floatArrayOf(
                target[0] + (right[0] * -dx + up[0] * dy) * scale,
                target[1] + (right[1] * -dx + up[1] * dy) * scale,
                target[2] + (right[2] * -dx + up[2] * dy) * scale,
            ),
        )
    }

    /** Column-major view matrix. */
    fun viewMatrix(): FloatArray {
        val eye = eye()
        val forward = normalize(sub(target, eye))
        val right = normalize(cross(forward, WORLD_UP))
        val up = cross(right, forward)
        return floatArrayOf(
            right[0], up[0], -forward[0], 0f,
            right[1], up[1], -forward[1], 0f,
            right[2], up[2], -forward[2], 0f,
            -dot(right, eye), -dot(up, eye), dot(forward, eye), 1f,
        )
    }

    /** Column-major perspective projection for the given viewport aspect (width / height). */
    fun projectionMatrix(aspect: Float): FloatArray {
        val a = if (aspect > 0f) aspect else 1f
        val f = 1f / tan(fovYDegrees * PI.toFloat() / 360f)
        val range = near - far
        return floatArrayOf(
            f / a, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / range, -1f,
            0f, 0f, 2f * far * near / range, 0f,
        )
    }

    companion object {
        /**
         * A camera framing [mesh]: aimed at its centre, far enough out that it fits the vertical
         * field of view with a margin. Framing on load is what makes a model of any authored scale —
         * millimetres or metres — open at a sensible size instead of filling the screen or vanishing.
         */
        fun framing(mesh: Mesh, fovYDegrees: Float = 45f): OrbitCamera {
            val radius = mesh.radius()
            val halfFov = fovYDegrees * PI.toFloat() / 360f
            val distance = (radius / tan(halfFov)) * FRAMING_MARGIN
            return OrbitCamera(
                target = mesh.center(),
                // Deliberately NOT clamped to a framing-sized floor: the distance a model needs is
                // proportional to its own radius, so any absolute minimum would push the camera too
                // far out for a small model and frame it wrongly. MIN_DISTANCE exists to stop
                // interactive dolly reaching the target, which is a different concern.
                distance = distance.coerceAtMost(MAX_DISTANCE),
                fovYDegrees = fovYDegrees,
                // Near/far bracket the model rather than using fixed values: a huge model would
                // clip through a 1000-unit far plane, and a tiny one would z-fight against a
                // near plane that's too far forward.
                near = (distance - radius * 2f).coerceAtLeast(radius * 0.01f),
                far = distance + radius * 4f,
            )
        }

        private const val MAX_PITCH = 1.5533f          // ~89°, shy of the pole
        // A safety floor for dolly only — small enough never to interfere with framing a model
        // authored at any scale, large enough that the camera can't land on the target and invert.
        private const val MIN_DISTANCE = 1e-4f
        private const val MAX_DISTANCE = 10_000f
        private const val PAN_SENSITIVITY = 0.002f
        private const val FRAMING_MARGIN = 1.4f
        private val WORLD_UP = floatArrayOf(0f, 1f, 0f)

        private fun sub(a: FloatArray, b: FloatArray) =
            floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

        private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        private fun normalize(v: FloatArray): FloatArray {
            val len = sqrt(dot(v, v))
            return if (len < 1e-6f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrbitCamera) return false
        return target.contentEquals(other.target) && distance == other.distance &&
            yaw == other.yaw && pitch == other.pitch && fovYDegrees == other.fovYDegrees &&
            near == other.near && far == other.far
    }

    override fun hashCode(): Int {
        var result = target.contentHashCode()
        result = 31 * result + distance.hashCode()
        result = 31 * result + yaw.hashCode()
        result = 31 * result + pitch.hashCode()
        return result
    }
}
