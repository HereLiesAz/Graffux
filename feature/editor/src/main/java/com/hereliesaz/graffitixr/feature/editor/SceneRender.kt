@file:Suppress("MagicNumber", "ReturnCount", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
@Suppress("LongParameterList")
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
    /** What the GPU depth pass draws (spec/3d-space.md, "Per-pixel depth"). */
    val depth: com.hereliesaz.graffitixr.common.scene.DepthComposite =
        com.hereliesaz.graffitixr.common.scene.DepthComposite.EMPTY,
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
        SceneContext(camera, frame, sceneIds, placement.model, placement.homography, draft, draftLayerId, showGuideFor, depth)

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
 * How a scene-path node composites: its opacity and blend mode, and a frame's clip box. On the 2D
 * path these are the `graphicsLayer`'s; on the scene path they are applied here, after the plane's
 * homography, so a blend-mode layer's offscreen buffer and a frame's clip follow its 3D placement
 * instead of being cut to the flat canvas box (spec/3d-space.md, "Render path").
 */
internal class ScenePaint(
    val alpha: Float = 1f,
    val blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
    /** The frame's clip in the node's own box coordinates, for a size; null = no clip. */
    val clip: ((androidx.compose.ui.geometry.Size) -> androidx.compose.ui.graphics.Path?)? = null,
) {
    val needsLayer: Boolean
        get() = alpha < 1f || blendMode != androidx.compose.ui.graphics.BlendMode.SrcOver
}

/**
 * Draws the content through [placement]'s homography on the hardware canvas: a GPU perspective
 * draw of the textures HWUI already holds for the layer (spec/3d-space.md, "Why HWUI").
 * A null [placement] is the 2D fast path and adds nothing.
 */
internal fun Modifier.scenePlane(placement: ScenePlacement?, paint: ScenePaint = ScenePaint()): Modifier {
    if (placement == null) return this
    val relative = placement.relative
    val clip = placement.clip
    return drawWithContent {
        if (relative == null || clip?.isEmpty() == true) return@drawWithContent
        val canvas = drawContext.canvas
        val native = canvas.nativeCanvas
        val save = native.save()
        native.concat(android.graphics.Matrix().apply { setValues(relative) })
        if (clip != null) {
            val path = android.graphics.Path()
            clip.forEachIndexed { i, (u, v) -> if (i == 0) path.moveTo(u, v) else path.lineTo(u, v) }
            path.close()
            native.clipPath(path)
        }
        paint.clip?.invoke(size)?.let { canvas.clipPath(it) }
        if (paint.needsLayer) {
            // In the plane's own coordinates, so the buffer covers wherever the plane is projected.
            val reach = maxOf(size.width, size.height) * LAYER_REACH
            canvas.saveLayer(
                androidx.compose.ui.geometry.Rect(-reach, -reach, size.width + reach, size.height + reach),
                androidx.compose.ui.graphics.Paint().apply {
                    alpha = paint.alpha.coerceIn(0f, 1f)
                    blendMode = paint.blendMode
                },
            )
            drawContent()
            canvas.restore()
        } else {
            drawContent()
        }
        native.restoreToCount(save)
    }
}

/** How far past its box a scene-path layer's offscreen buffer reaches, in box sizes. */
private const val LAYER_REACH = 4f

/**
 * Screen touches meant for the active layer's pixels, through its plane when it is drawn in 3D:
 * the brush, fill, clone and lasso hand the view-model the flat-space point the layer's own 2D
 * transform maps to, exactly what a flat layer would have received. With the layer on the 2D path
 * every map is the plain screen-to-world conversion.
 */
@Suppress("LongParameterList", "ComplexCondition")
internal class ActivePlaneMapping private constructor(
    private val viewportOffset: Offset,
    private val viewportZoom: Float,
    private val viewportRotation: Float,
    private val camera: SceneCamera,
    private val chain: List<com.hereliesaz.graffitixr.common.scene.PlanePose>?,
    private val width: Float,
    private val height: Float,
) {
    fun world(screen: Offset): Offset =
        CanvasHitTest.screenToWorld(screen, viewportOffset, viewportZoom, viewportRotation)

    /** The flat point for [screen], or null when the ray misses the plane. */
    fun toFlat(screen: Offset): Offset? {
        val w = world(screen)
        val c = chain ?: return w
        return SceneMath.worldToLayerFlat(w.x, w.y, c, camera, width, height)?.let { Offset(it.first, it.second) }
    }

    /** A flat point back to where it shows in world space; null behind the eye. */
    fun flatToWorld(flat: Offset): Offset? {
        val c = chain ?: return flat
        return SceneMath.layerFlatToWorld(flat.x, flat.y, c, camera, width, height)?.let { Offset(it.first, it.second) }
    }

    /** [flatToWorld] when the layer is drawn in 3D, null (no mapping) when it is flat. */
    fun flatToWorldOrNull(): ((Offset) -> Offset?)? = if (chain == null) null else ::flatToWorld

    /**
     * A screen drag [delta] as a flat-space delta, measured at [around] (the selection, whose centroid
     * anchors it; perspective makes a delta depend on where it is applied).
     */
    fun flatDelta(delta: Offset, around: List<Offset>?): Offset {
        val worldDelta = CanvasHitTest.screenDeltaToWorld(delta, viewportZoom, viewportRotation)
        val c = chain ?: return worldDelta
        if (around.isNullOrEmpty()) return worldDelta
        val anchor = Offset(around.sumOf { it.x.toDouble() }.toFloat() / around.size, around.sumOf { it.y.toDouble() }.toFloat() / around.size)
        val from = flatToWorld(anchor) ?: return worldDelta
        val to = from + worldDelta
        val moved = SceneMath.worldToLayerFlat(to.x, to.y, c, camera, width, height) ?: return Offset.Zero
        return Offset(moved.first - anchor.x, moved.second - anchor.y)
    }

    companion object {
        fun of(state: com.hereliesaz.graffitixr.common.model.EditorUiState): ActivePlaneMapping {
            val id = state.activeLayerId
            val w = state.canvasSize.width.toFloat()
            val h = state.canvasSize.height.toFloat()
            val chain = if (id == null || w <= 0f || h <= 0f ||
                com.hereliesaz.graffitixr.common.scene.SceneRenderPath.isLayerFlat(state.layers, id, state.sceneCamera)
            ) {
                null
            } else {
                com.hereliesaz.graffitixr.common.scene.planeChain(state.layers, id).takeIf { it.isNotEmpty() }
            }
            return ActivePlaneMapping(
                state.viewportOffset, state.viewportZoom, state.viewportRotation, state.sceneCamera, chain, w, h,
            )
        }
    }
}

/**
 * Feeds one brush stroke to the view-model through the active layer's plane (spec/3d-space.md,
 * "Painting on a tilted plane"). A sample whose ray misses the plane (the plane edge-on, parallel
 * to the ray, or behind the eye) has no point on the layer under the finger, so it is skipped
 * rather than painted at an unrelated flat-space point. The stroke starts at its first sample that
 * hits; a stroke that never hits is cancelled, which also puts back an adopted view plane.
 */
internal class PlaneStrokeFeed<S>(
    private val toLayer: (S) -> S?,
    private val start: (S) -> Unit,
    private val point: (S) -> Unit,
    private val end: () -> Unit,
    private val cancel: () -> Unit,
) {
    private var started = false

    fun onStart(sample: S) {
        started = false
        feed(sample)
    }

    fun onPoint(sample: S) = feed(sample)

    fun onEnd() = finish(end)

    fun onCancel() = finish(cancel)

    private fun feed(sample: S) {
        val mapped = toLayer(sample) ?: return
        if (started) {
            point(mapped)
        } else {
            started = true
            start(mapped)
        }
    }

    private fun finish(then: () -> Unit) {
        if (started) then() else cancel()
        started = false
    }
}
