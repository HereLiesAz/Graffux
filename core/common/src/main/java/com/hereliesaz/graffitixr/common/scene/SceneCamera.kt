@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import kotlinx.serialization.Serializable

/**
 * The camera of the canvas's one 3D space (spec/3d-space.md). Scene coordinates are the canvas
 * box's pixels: x right, y down, z away from the viewer. The default value looks straight down +z
 * at the canvas centre from exactly the distance that projects depth 0 pixel for pixel, so a
 * document seen through it is the 2D canvas, unchanged.
 *
 * [yaw] and [pitch] are radians; [distanceScale] multiplies the default eye distance; [panX],
 * [panY], [panZ] move the target off the canvas centre, in scene pixels.
 */
@Serializable
data class SceneCamera(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val distanceScale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val panZ: Float = 0f,
) {
    /** The identity view: the scene projects onto the canvas exactly as the 2D canvas draws. */
    val isDefault: Boolean
        get() = yaw == 0f && pitch == 0f && distanceScale == 1f && panX == 0f && panY == 0f && panZ == 0f

    /** Turns the camera about its target; pitch stops short of the poles. */
    fun orbit(deltaYaw: Float, deltaPitch: Float): SceneCamera =
        copy(yaw = yaw + deltaYaw, pitch = (pitch + deltaPitch).coerceIn(-MAX_PITCH, MAX_PITCH))

    /** Pinch: a factor above 1 moves the eye closer. */
    fun dolly(factor: Float): SceneCamera =
        copy(distanceScale = (distanceScale / factor.coerceAtLeast(1e-3f)).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    companion object {
        const val MAX_PITCH = 1.5533f
        const val MIN_DISTANCE = 0.05f
        const val MAX_DISTANCE = 40f
        /** Vertical field of view of the scene camera, degrees. */
        const val FOV_Y_DEGREES = 45f
    }
}

/**
 * A 3D strokes layer's content: the editable spatial ink and its drawing guide, formerly the
 * project-level Spatial Sketch. Coordinates are the original spatial units (y up, z toward the
 * viewer); see [SceneMath.strokeToLocal] for how they sit in the layer.
 */
@Serializable
data class SpatialLayerContent(
    val strokes: List<SpatialStroke> = emptyList(),
    val guide: SpatialGuide = SpatialGuide(),
)

/** What a one-finger drag does while 3D is on. */
@Serializable
enum class Scene3DTool { ORBIT, MOVE, DEPTH, TILT, SPIN, DRAW, ERASE }
