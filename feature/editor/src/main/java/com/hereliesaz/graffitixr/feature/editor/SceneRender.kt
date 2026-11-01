@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.Affine3
import com.hereliesaz.graffitixr.common.scene.CameraFrame
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planePose

/**
 * What the layer stack needs to draw the 3D space (spec/3d-space.md, "Render path"): the camera
 * resolved for the canvas box, which layers take the scene path, and the enclosing group's plane.
 */
internal class SceneContext(
    val camera: SceneCamera,
    val frame: CameraFrame,
    val sceneIds: Set<String>,
    /** The enclosing scene-path group's box-local to scene map (identity at the top). */
    val parentModel: Affine3 = Affine3.IDENTITY,
    /** The enclosing scene-path group's absolute homography, null at the top. */
    val parentHomography: FloatArray? = null,
    /** The curve being drawn right now, on the layer [draftLayerId]. */
    val draft: SpatialStroke? = null,
    val draftLayerId: String? = null,
    /** The 3D strokes layer whose guide is shown (active, 3D on, drawing). */
    val showGuideFor: String? = null,
) {
    /** The node's own placement on the scene path, or null for the 2D fast path. */
    fun placementFor(layer: Layer): ScenePlacement? {
        if (layer.id !in sceneIds) return null
        val model = parentModel * SceneMath.model(layer.planePose, frame.width, frame.height)
        val homography = SceneMath.homography(model, frame)
        val relative = SceneMath.relativeHomography(parentHomography, homography)
        val clip = SceneMath.visiblePolygon(homography, frame, CLIP_EXTENT * maxOf(frame.width, frame.height))
        return ScenePlacement(model, homography, relative, clip)
    }

    /** This layer's box-local to scene map, whichever path it draws through. */
    fun modelFor(layer: Layer): Affine3 =
        parentModel * SceneMath.model(layer.planePose, frame.width, frame.height)

    fun inside(placement: ScenePlacement): SceneContext =
        SceneContext(camera, frame, sceneIds, placement.model, placement.homography, draft, draftLayerId, showGuideFor)

    private companion object {
        /** How far past the canvas a plane's visible part is drawn when it crosses the eye. */
        const val CLIP_EXTENT = 8f
    }
}

internal class ScenePlacement(
    val model: Affine3,
    val homography: FloatArray,
    /** Drawn inside the parent's homography; null when degenerate (nothing to draw). */
    val relative: FloatArray?,
    /** The part in front of the eye, box-local; null = all of it. */
    val clip: List<Pair<Float, Float>>?,
)

/** Null outside the canvas stack (previews, tests): every layer then draws on the 2D path. */
internal val LocalScene = staticCompositionLocalOf<SceneContext?> { null }

/**
 * Draws the content through [placement]'s homography on the hardware canvas: a GPU perspective
 * draw of the textures HWUI already holds for the layer (spec/3d-space.md, "Why HWUI").
 * A null [placement] is the 2D fast path and adds nothing.
 */
internal fun Modifier.scenePlane(placement: ScenePlacement?): Modifier {
    if (placement == null) return this
    val relative = placement.relative
    val clip = placement.clip
    return drawWithContent {
        if (relative == null || clip?.isEmpty() == true) return@drawWithContent
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        canvas.concat(android.graphics.Matrix().apply { setValues(relative) })
        if (clip != null) {
            val path = android.graphics.Path()
            clip.forEachIndexed { i, (u, v) -> if (i == 0) path.moveTo(u, v) else path.lineTo(u, v) }
            path.close()
            canvas.clipPath(path)
        }
        drawContent()
        canvas.restoreToCount(save)
    }
}
