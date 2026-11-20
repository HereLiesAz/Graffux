@file:Suppress("LongParameterList")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.RotationAxis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ONE way the view turns about the orbit pivot (spec/3d-space.md, "The orbit point", "One orbit, two inputs").
 *
 * Two inputs turn the view: the two-finger drag and the rotation triangle. They differ only in how they make an
 * angle: [angles] is the two-finger drag's mapping and [gizmoAngles] is the triangle's. Every angle then goes
 * through [step], and a turn of the canvas (the triangle's Z edge) through [twistFocus]; the pivot is resolved
 * once per gesture by the caller ([OrbitPivot.resolve]) and handed in, never measured here and never from content
 * that a turn moved, because a turn moves the camera only: no layer is touched, so the pivot is the same point
 * before, during and after, whichever input made the turn.
 */
object OrbitRig {
    /**
     * The orbit step: [camera] turned by [yaw] and [pitch] radians about [pivot] on a [width] x [height] canvas, the
     * pivot staying at the same place on screen and the same distance from the eye. Pitch stops short of the poles.
     */
    fun step(
        camera: SceneCamera,
        pivot: FloatArray,
        yaw: Float,
        pitch: Float,
        width: Float,
        height: Float,
    ): SceneCamera =
        SceneMath.orbitAbout(camera, pivot, yaw, pitch, width, height)

    /** (yaw, pitch) radians for a two-finger drag of [dx], [dy] pixels on a canvas [canvasWidth] wide. */
    fun angles(dx: Float, dy: Float, canvasWidth: Float): Pair<Float, Float> = orbitAnglesForDrag(dx, dy, canvasWidth)

    /**
     * (yaw, pitch) radians for a triangle turn of [degrees] about [axis]: the bottom leg (X) pitches the view and the
     * side leg (Y) yaws it, each by exactly the angle shown, with the sense the old object turn had on screen
     * (a positive Y turn brings the near side left, as a leftward drag does). Null for Z, which turns the canvas
     * (the triangle's degrees, as they are) rather than orbiting.
     */
    fun gizmoAngles(axis: RotationAxis, degrees: Float): Pair<Float, Float>? {
        val rad = (degrees * PI / HALF_TURN_DEGREES).toFloat()
        return when (axis) {
            RotationAxis.X -> Pair(0f, rad)
            RotationAxis.Y -> Pair(-rad, 0f)
            RotationAxis.Z -> null
        }
    }

    /**
     * Where [pivot] is on screen (the same space as the viewport's offset), for the view [camera] under the viewport
     * (`screen = offset + zoom * R(rotation) * canvasPoint`); null when it is behind the eye. A canvas twist about
     * this point leaves the pivot where it is.
     */
    fun pivotOnScreen(
        camera: SceneCamera,
        pivot: FloatArray,
        width: Float,
        height: Float,
        offsetX: Float,
        offsetY: Float,
        zoom: Float,
        rotationDegrees: Float,
    ): Pair<Float, Float>? {
        val (px, py) = SceneMath.frame(camera, width, height).project(pivot) ?: return null
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return Pair(offsetX + zoom * (px * c - py * s), offsetY + zoom * (px * s + py * c))
    }

    /** A half turn, in degrees. */
    private const val HALF_TURN_DEGREES = 180.0
}
