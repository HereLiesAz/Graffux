@file:Suppress("MagicNumber", "ReturnCount", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.BrushSample
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
    /**
     * Absolute models of the layers painted on a flat surface (SurfaceMath.flatSurfaceModels): one
     * the depth pass leaves in the stack (a blend mode or a clip) draws on the surface through it.
     */
    val flatSurfaceModels: Map<String, Affine3> = emptyMap(),
    /** Each such layer's sheet outline in its box (SurfaceMath.flatSurfaceCrops): the draw is cropped to it. */
    val flatSurfaceCrops: Map<String, List<Pair<Float, Float>>> = emptyMap(),
) {
    /** The node's own placement on the scene path, or null for the 2D fast path. */
    fun placementFor(layer: Layer): ScenePlacement? {
        if (layer.id !in sceneIds) return null
        val model = modelFor(layer)
        val homography = SceneMath.homography(model, frame)
        val relative = SceneMath.relativeHomography(parentHomography, homography)
        val front = SceneMath.visiblePolygon(homography, frame, CLIP_EXTENT * maxOf(frame.width, frame.height))
        val crop = flatSurfaceCrops[layer.id]
        val clip = if (crop == null) front else if (front == null) crop else SceneMath.clipConvex(front, crop)
        return ScenePlacement(model, homography, relative, clip)
    }

    /** This layer's box-local to scene map, whichever path it draws through. */
    fun modelFor(layer: Layer): Affine3 = flatSurfaceModels[layer.id]
        ?: (parentModel * SceneMath.model(layer.planePose, frame.width, frame.height))

    fun inside(placement: ScenePlacement): SceneContext = SceneContext(
        camera, frame, sceneIds, placement.model, placement.homography, draft, draftLayerId, showGuideFor, depth,
        flatSurfaceModels, flatSurfaceCrops,
    )

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
@Suppress("LongParameterList", "ComplexCondition", "TooManyFunctions")
internal class ActivePlaneMapping private constructor(
    private val viewportOffset: Offset,
    private val viewportZoom: Float,
    private val viewportRotation: Float,
    private val camera: SceneCamera,
    private val chain: List<com.hereliesaz.graffitixr.common.scene.PlanePose>?,
    private val width: Float,
    private val height: Float,
    /** The active layer's group surface, when it is paint on one (spec/3d-space.md, "Group surfaces"). */
    private val surface: com.hereliesaz.graffitixr.common.scene.SurfaceOwner? = null,
    /** The active layer, the paint layer whose per-view images a hull stroke lands in. */
    val layerId: String? = null,
    /** Every layer, for what the camera sees of the surfaces ([visibility]). */
    private val layers: List<Layer> = emptyList(),
) {
    /**
     * What the camera sees of the surfaces (spec/3d-space.md, "Paint reaches only what the camera sees"), built
     * once per mapping from the camera it holds, which is the one the stroke is mapped with. Null off a group surface.
     */
    private val visibility: com.hereliesaz.graffitixr.common.scene.SurfaceVisibility? by lazy {
        if (surface == null) null else com.hereliesaz.graffitixr.common.scene.SurfaceVisibility.of(layers, camera, width, height)
    }

    private fun sceneSeen(p: FloatArray?): Boolean = com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard.visibleOk(
        p != null && visibility?.visible(p) != false,
    )

    private fun flatSeen(flat: Offset): Boolean {
        val s = surface ?: return true
        return sceneSeen(com.hereliesaz.graffitixr.common.scene.SurfaceMath.flatToScene(flat.x, flat.y, s, width, height))
    }

    /** Whether the point of [view]'s paint image is in the camera's direct view (true off a group surface). */
    internal fun viewFlatSeen(view: com.hereliesaz.graffitixr.common.scene.SurfaceView, flat: Offset): Boolean {
        val s = surface ?: return true
        val h = hull ?: return true
        return sceneSeen(com.hereliesaz.graffitixr.common.scene.SurfaceMath.viewFlatToScene(view, flat.x, flat.y, s, h, width, height))
    }

    /** The intersected surface of a group drawn from more than one view, built once; null otherwise. */
    private val hull: com.hereliesaz.graffitixr.common.scene.SurfaceHull? by lazy {
        surface?.takeIf { it.profile.hasHull }?.let { com.hereliesaz.graffitixr.common.scene.SurfaceHull.of(it.profile) }
    }

    /** True when the active layer is paint on a multi-view surface: a stroke is painted per view image. */
    val onHull: Boolean get() = hull != null

    /**
     * [toFlatByFootprint] for a multi-view surface (spec/3d-space.md, "Paint per view"): the view
     * whose image the touch lands on and the flat point in it; null for a true miss (or a cap,
     * which has no image) and when the layer is not on a multi-view surface.
     */
    fun toViewFlatByFootprint(screen: Offset, footprintRadius: Float): com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat? =
        worldToViewFlatByFootprint(world(screen), footprintRadius / viewportZoom.coerceAtLeast(MIN_ZOOM))

    /** [toViewFlatByFootprint] for a world point and a footprint radius in world units (a finished Ink stroke's samples). */
    fun worldToViewFlatByFootprint(w: Offset, footprint: Float): com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat? {
        val s = surface ?: return null
        val h = hull ?: return null
        return com.hereliesaz.graffitixr.common.scene.SurfaceFootprint.worldToViewFlat(
            w.x, w.y, footprint, s, camera, width, height, h,
        )
    }

    /** [toFlatByFootprint] for a world point and a footprint radius in world units. */
    fun worldToFlatByFootprint(w: Offset, footprint: Float): Offset? = toFlatAt(w, footprint)

    private fun toFlatAt(w: Offset, footprint: Float = 0f): Offset? {
        val s = surface
        val c = chain ?: return w
        val p = if (s != null) {
            // By footprint: any point of the surface under the brush counts, not only the one the
            // touch's own ray meets (spec/3d-space.md, "Painting by footprint").
            com.hereliesaz.graffitixr.common.scene.SurfaceFootprint.worldToFlat(w.x, w.y, footprint, s, camera, width, height)
        } else {
            SceneMath.worldToLayerFlat(w.x, w.y, c, camera, width, height)
        }
        return p?.let { Offset(it.first, it.second) }
    }

    fun world(screen: Offset): Offset =
        CanvasHitTest.screenToWorld(screen, viewportOffset, viewportZoom, viewportRotation)

    /** True when the active layer is drawn through the scene, so a touch has to be mapped onto its plane. */
    val onPlane: Boolean get() = chain != null

    /** The flat point for the world point [w] (the viewport already undone), null when the ray misses. */
    fun worldToFlat(w: Offset): Offset? = toFlatAt(w)

    /** The flat point for [screen], or null when the ray misses the plane. */
    fun toFlat(screen: Offset): Offset? {
        return toFlatAt(world(screen))
    }

    /**
     * [toFlat] for a brush or eraser touch: on a group surface the brush footprint (a circle of
     * [footprintRadius] screen pixels) decides, so a touch whose own ray misses the surface but whose
     * footprint reaches it still lands on it, and only a footprint that touches nothing is a miss.
     * Anywhere else it is [toFlat].
     */
    fun toFlatByFootprint(screen: Offset, footprintRadius: Float): Offset? =
        toFlatAt(world(screen), footprintRadius / viewportZoom.coerceAtLeast(MIN_ZOOM))

    private fun footprintWorld(radiusPx: Float) = radiusPx / viewportZoom.coerceAtLeast(MIN_ZOOM)

    /**
     * The surface-stroke invariant ([com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard]) for a touch
     * at [touch] (screen) accepted as [flat] on a layer drawn in 3D: it shows within the footprint of the touch
     * and in direct view. Always true for a flat layer.
     */
    fun flatOnFinger(touch: Offset, flat: Offset, radiusPx: Float): Boolean =
        flatOnFingerWorld(world(touch), flat, footprintWorld(radiusPx))

    /** [flatOnFinger] for a world-space touch and a footprint in world units (a finished Ink stroke's samples). */
    fun flatOnFingerWorld(t: Offset, flat: Offset, footprint: Float): Boolean {
        if (chain == null) return true
        val b = flatToWorld(flat) ?: return false
        return com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard.sampleOk(t.x, t.y, b.x, b.y, footprint) &&
            flatSeen(flat)
    }

    /** [flatOnFinger] for a multi-view surface's [ViewFlat]. */
    fun viewFlatOnFinger(touch: Offset, f: com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat, radiusPx: Float): Boolean =
        viewFlatOnFingerWorld(world(touch), f, footprintWorld(radiusPx))

    /** [viewFlatOnFinger] in world units. */
    fun viewFlatOnFingerWorld(t: Offset, f: com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat, footprint: Float): Boolean {
        if (f.region != 0) return viewFlatSeen(f.view, Offset(f.x, f.y))
        val b = viewFlatToWorld(f.view, Offset(f.x, f.y)) ?: return false
        return com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard.sampleOk(t.x, t.y, b.x, b.y, footprint) &&
            viewFlatSeen(f.view, Offset(f.x, f.y))
    }

    /** The stroke from ([touchA], [flatA]) to ([touchB], [flatB]) stays on the surface between them, and its middle is seen. */
    fun flatJoinOnFinger(touchA: Offset, flatA: Offset, touchB: Offset, flatB: Offset, radiusPx: Float): Boolean =
        flatJoinOnFingerWorld(world(touchA), flatA, world(touchB), flatB, footprintWorld(radiusPx))

    /** [flatJoinOnFinger] in world units. */
    fun flatJoinOnFingerWorld(a: Offset, flatA: Offset, b: Offset, flatB: Offset, footprint: Float): Boolean {
        if (chain == null) return true
        val mid = Offset((flatA.x + flatB.x) / 2f, (flatA.y + flatB.y) / 2f)
        val m = flatToWorld(mid) ?: return false
        // The stretch between two seen samples must be seen at its middle too: a fast stroke can step over a hidden fold.
        return com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard.joinOk(a.x, a.y, b.x, b.y, m.x, m.y, footprint) &&
            flatSeen(mid)
    }

    /** [flatJoinOnFinger] for two touches on one face of a multi-view surface. */
    fun viewJoinOnFinger(touchA: Offset, a: ViewSample<BrushSample>, touchB: Offset, b: ViewSample<BrushSample>, radiusPx: Float): Boolean =
        viewJoinOnFingerWorld(world(touchA), a.flat.let { Offset(it.x, it.y) }, world(touchB), b.view, b.region, Offset(b.flat.x, b.flat.y), footprintWorld(radiusPx))

    /** [viewJoinOnFinger] in world units, on the flat points of the two samples. */
    fun viewJoinOnFingerWorld(wa: Offset, flatA: Offset, wb: Offset, view: com.hereliesaz.graffitixr.common.scene.SurfaceView, region: Int, flatB: Offset, footprint: Float): Boolean {
        val mid = Offset((flatA.x + flatB.x) / 2f, (flatA.y + flatB.y) / 2f)
        // A cap has no 2D map back, so no finger distance, but what hides its middle still counts.
        if (region != 0) return viewFlatSeen(view, mid)
        val m = viewFlatToWorld(view, mid) ?: return false
        return com.hereliesaz.graffitixr.common.scene.SurfaceStrokeGuard.joinOk(wa.x, wa.y, wb.x, wb.y, m.x, m.y, footprint) &&
            viewFlatSeen(view, mid)
    }

    /** A flat point back to where it shows in world space; null behind the eye. */
    fun flatToWorld(flat: Offset): Offset? {
        val c = chain ?: return flat
        val s = surface
        val p = if (s != null) {
            com.hereliesaz.graffitixr.common.scene.SurfaceMath.flatToWorld(flat.x, flat.y, s, camera, width, height)
        } else {
            SceneMath.layerFlatToWorld(flat.x, flat.y, c, camera, width, height)
        }
        return p?.let { Offset(it.first, it.second) }
    }

    /**
     * [flatToWorld] when the layer is drawn in 3D, null (no mapping) when it is flat. Null on a
     * multi-view surface too: its selection is kept in world space ([toSelectionPoint]).
     */
    fun flatToWorldOrNull(): ((Offset) -> Offset?)? = if (chain == null || onHull) null else ::flatToWorld

    /**
     * Where a point of [view]'s paint image shows in world space (the clone reticle on a multi-view
     * surface); null off the surface or behind the eye.
     */
    fun viewFlatToWorld(view: com.hereliesaz.graffitixr.common.scene.SurfaceView, flat: Offset): Offset? {
        val s = surface ?: return null
        val h = hull ?: return null
        val (ux, uy) = com.hereliesaz.graffitixr.common.scene.SurfaceMath.flatToUnrolled(flat.x, flat.y, s.innerChain, width, height)
        val p = h.pointAt(view, ux, uy) ?: return null
        val model = SceneMath.chainModel(s.ownerChain, width, height)
        val at = model.apply(p[0], p[1], p[2])
        return com.hereliesaz.graffitixr.common.scene.SceneMath.frame(camera, width, height).project(at)?.let { Offset(it.first, it.second) }
    }

    /**
     * A lasso point as the selection stores it: the flat point on the layer's plane, except on a
     * multi-view surface, where each view image has its own flat space, so the lasso stays in world
     * space and is cut to each image when used (`SurfaceViewSelection`, spec/3d-space.md).
     */
    fun toSelectionPoint(screen: Offset): Offset? = if (onHull) world(screen) else toFlat(screen)

    /** A screen drag [delta] in world space, where a multi-view surface keeps its selection. */
    fun worldDelta(delta: Offset): Offset = CanvasHitTest.screenDeltaToWorld(delta, viewportZoom, viewportRotation)

    /**
     * A screen drag [delta] as a flat-space delta, measured at [around] (the selection, whose centroid
     * anchors it; perspective makes a delta depend on where it is applied).
     */
    fun flatDelta(delta: Offset, around: List<Offset>?): Offset {
        val worldDelta = CanvasHitTest.screenDeltaToWorld(delta, viewportZoom, viewportRotation)
        if (chain == null) return worldDelta
        if (around.isNullOrEmpty()) return worldDelta
        val anchor = Offset(around.sumOf { it.x.toDouble() }.toFloat() / around.size, around.sumOf { it.y.toDouble() }.toFloat() / around.size)
        val from = flatToWorld(anchor) ?: return worldDelta
        val to = from + worldDelta
        val moved = toFlatAt(to) ?: return Offset.Zero
        return Offset(moved.x - anchor.x, moved.y - anchor.y)
    }

    companion object {
        private const val MIN_ZOOM = 0.01f

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
            val surface = if (chain != null && id != null &&
                com.hereliesaz.graffitixr.common.scene.SurfaceMath.isOnSurface(state.layers, id)
            ) {
                com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(state.layers, id)
            } else {
                null
            }
            return ActivePlaneMapping(
                state.viewportOffset, state.viewportZoom, state.viewportRotation, state.sceneCamera, chain, w, h, surface, id,
                state.layers,
            )
        }
    }
}

/**
 * Feeds one brush stroke to the view-model through the active layer's plane (spec/3d-space.md,
 * "Painting on a tilted plane"). A sample whose ray misses the plane (the plane edge-on, parallel
 * to the ray, or behind the eye) has no point on the layer under the finger, so it is skipped
 * rather than painted at an unrelated flat-space point. The stroke starts at its first sample that
 * hits; a stroke that never hits is cancelled, which also puts back an adopted view plane. A
 * stroke that leaves the plane and comes back is broken at the gap (the segment ended through
 * [segmentEnd], then a new stroke started where it comes back) rather than bridged with a straight
 * segment across it. The segments are still one gesture: the view-model keeps them in one undo step
 * and a later cancel (a second finger) takes every one of them back, not only the last.
 */
@Suppress("LongParameterList", "ComplexCondition")
internal class PlaneStrokeFeed<S>(
    private val toLayer: (S) -> S?,
    private val start: (S) -> Unit,
    private val point: (S) -> Unit,
    private val end: () -> Unit,
    private val cancel: () -> Unit,
    /** Ends a segment at a gap without ending the gesture; [end] or [cancel] still closes it. */
    private val segmentEnd: () -> Unit = end,
    /**
     * Whether [toLayer]'s result for a raw sample shows near it and in direct view of the camera (the
     * surface-stroke invariant); false drops it like a miss. Required: there is no permissive default,
     * so a stroke path cannot be built without the finger guard and the visibility check.
     */
    private val sampleOk: (raw: S, mapped: S) -> Boolean,
    /** Whether the stroke between the previous accepted sample and this one stays on the surface; false cuts the stroke there. Required, like [sampleOk]. */
    private val joinOk: (prevRaw: S, prevMapped: S, raw: S, mapped: S) -> Boolean,
) {
    private var started = false
    private var lastRaw: S? = null
    private var lastMapped: S? = null

    /** A sample missed the plane since the last one that hit. */
    private var gap = false

    fun onStart(sample: S) {
        started = false
        gap = false
        feed(sample)
    }

    fun onPoint(sample: S) = feed(sample)

    fun onEnd() = finish(end)

    fun onCancel() = finish(cancel)

    private fun feed(sample: S) {
        val mapped = toLayer(sample)
        if (mapped == null || !sampleOk(sample, mapped)) {
            gap = started
            return
        }
        val pr = lastRaw
        val pm = lastMapped
        if (started && !gap && pr != null && pm != null && !joinOk(pr, pm, sample, mapped)) gap = true
        if (started && gap) {
            segmentEnd()
            started = false
        }
        gap = false
        if (started) {
            point(mapped)
        } else {
            started = true
            start(mapped)
        }
        lastRaw = sample
        lastMapped = mapped
    }

    private fun finish(then: () -> Unit) {
        if (started) then() else cancel()
        started = false
        gap = false
        lastRaw = null
        lastMapped = null
    }
}

/**
 * A finished stroke's world-space [path] cut into the runs that lie on the active layer's plane
 * ([ActivePlaneMapping.worldToFlat]), through the same [PlaneStrokeFeed] the other brushes use: a
 * sample that misses is dropped and a run that leaves the plane and returns is a new segment.
 * Each segment is the original sample indices and their flat points. Empty when nothing hits.
 */
internal fun planeSegments(
    path: List<Offset>,
    /** The surface-stroke guards on (raw, mapped) world points (required); a caller on a surface passes [ActivePlaneMapping]'s. */
    sampleOk: (raw: Offset, mapped: Offset) -> Boolean,
    joinOk: (prevRaw: Offset, prevMapped: Offset, raw: Offset, mapped: Offset) -> Boolean,
    toFlat: (Offset) -> Offset?,
): List<List<Pair<Int, Offset>>> {
    val done = ArrayList<List<Pair<Int, Offset>>>()
    var current = ArrayList<Pair<Int, Offset>>()
    fun close() {
        if (current.isNotEmpty()) done += current
        current = ArrayList()
    }
    val feed = PlaneStrokeFeed<Pair<Int, Offset>>(
        toLayer = { (i, p) -> toFlat(p)?.let { i to it } },
        start = { current += it },
        point = { current += it },
        end = ::close,
        cancel = { current = ArrayList() },
        segmentEnd = ::close,
        sampleOk = { raw, mapped -> sampleOk(raw.second, mapped.second) },
        joinOk = { pr, pm, r, m -> joinOk(pr.second, pm.second, r.second, m.second) },
    )
    path.forEachIndexed { i, p ->
        if (i == 0) feed.onStart(i to p) else feed.onPoint(i to p)
    }
    feed.onEnd()
    return done
}

/** Finds the flat samples either side of a crease between two touches ([ViewStrokeFeed.bridge]). */
internal typealias BridgeFn = (ViewSample<BrushSample>, ViewSample<BrushSample>) -> Pair<BrushSample, BrushSample>?

/** A raw touch with the view image it lands on and its flat sample there ([ViewStrokeFeed]). */
internal class ViewSample<S>(
    val view: com.hereliesaz.graffitixr.common.scene.SurfaceView,
    val raw: S,
    val flat: S,
    /** The face of the view's image the touch is on (0 its sheet, else a cap); a change cuts the stroke like a change of view. */
    val region: Int = 0,
)

/**
 * [PlaneStrokeFeed] for a stroke on a multi-view surface (spec/3d-space.md, "Paint per view"): each
 * view has its own paint image, so a stroke that moves from one view's face onto another's is cut
 * there. The cut is made at the crease ([bridge] brackets it): the first image gets its last point
 * right up to the crease and the next image starts at the other side of it, so nothing between is
 * left unpainted. The segments are one gesture (the view-model chains them into one undo step).
 * A gap (a sample off the surface) breaks the stroke exactly as in [PlaneStrokeFeed].
 */
@Suppress("LongParameterList", "ComplexCondition", "CyclomaticComplexMethod")
internal class ViewStrokeFeed<S>(
    private val toView: (S) -> ViewSample<S>?,
    /** The flat samples either side of the crease between consecutive touches in different views, or null for none. */
    private val bridge: (ViewSample<S>, ViewSample<S>) -> Pair<S, S>?,
    private val start: (com.hereliesaz.graffitixr.common.scene.SurfaceView, S) -> Unit,
    private val point: (S) -> Unit,
    private val end: () -> Unit,
    private val cancel: () -> Unit,
    private val segmentEnd: () -> Unit = end,
    /** The surface-stroke invariant for one touch ([PlaneStrokeFeed.sampleOk]). Required: no permissive default. */
    private val sampleOk: (ViewSample<S>) -> Boolean,
    /** The surface-stroke invariant for the stroke between two touches on one face ([PlaneStrokeFeed.joinOk]). Required: no permissive default. */
    private val joinOk: (ViewSample<S>, ViewSample<S>) -> Boolean,
) {
    private var last: ViewSample<S>? = null
    private var gap = false

    fun onStart(sample: S) {
        last = null
        gap = false
        feed(sample)
    }

    fun onPoint(sample: S) = feed(sample)

    fun onEnd() = finish(end)

    fun onCancel() = finish(cancel)

    private fun feed(raw: S) {
        val mapped = toView(raw)
        if (mapped == null || !sampleOk(mapped)) {
            gap = last != null
            return
        }
        val prev = last
        if (prev != null && !gap && prev.view == mapped.view && prev.region == mapped.region && !joinOk(prev, mapped)) gap = true
        when {
            prev == null -> start(mapped.view, mapped.flat)
            gap -> {
                segmentEnd()
                start(mapped.view, mapped.flat)
            }
            (prev.view != mapped.view || prev.region != mapped.region) -> {
                val cut = bridge(prev, mapped)
                if (cut != null) point(cut.first)
                segmentEnd()
                start(mapped.view, cut?.second ?: mapped.flat)
                if (cut != null) point(mapped.flat)
            }
            else -> point(mapped.flat)
        }
        gap = false
        last = mapped
    }

    private fun finish(then: () -> Unit) {
        if (last != null) then() else cancel()
        last = null
        gap = false
    }
}

/**
 * The flat samples either side of the crease between two touches that land in different views
 * ([ViewStrokeFeed.bridge]): the crease is found along the straight screen step between them, with
 * the nib telemetry of the touch each side belongs to. Null when the step does not cross cleanly.
 */
internal fun ActivePlaneMapping.bridgeViews(
    prev: ViewSample<BrushSample>,
    next: ViewSample<BrushSample>,
): Pair<BrushSample, BrushSample>? {
    val sm = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(prev.view, prev.flat.x, prev.flat.y, prev.region)
    val tm = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(next.view, next.flat.x, next.flat.y, next.region)
    val c = com.hereliesaz.graffitixr.common.scene.SurfaceViewStroke.crossing(
        prev.raw.x, prev.raw.y, sm, next.raw.x, next.raw.y, tm,
    ) { x, y -> toViewFlatByFootprint(Offset(x, y), 0f) }
    if (c.last === sm && c.first === tm) return null
    // A crease another surface hides is not bridged: the stroke is cut there instead (a null bridge).
    if (!viewFlatSeen(prev.view, Offset(c.last.x, c.last.y)) || !viewFlatSeen(next.view, Offset(c.first.x, c.first.y))) return null
    return prev.flat.copy(x = c.last.x, y = c.last.y) to next.flat.copy(x = c.first.x, y = c.first.y)
}

/** A run of an Ink stroke's samples (original index, flat point) on one surface image: [view] on a hull, else null. */
internal class InkSegment(
    val view: com.hereliesaz.graffitixr.common.scene.SurfaceView?,
    val samples: List<Pair<Int, Offset>>,
)

/**
 * A finished Ink stroke's world-space [path] mapped as the Brush maps its touches (spec/3d-space.md,
 * "Painting by footprint"): each sample by the touch ray first, else the nearest point of the surface
 * its [footprint] (the brush radius, world units) reaches, so a stroke that just misses the surface
 * is not dropped sample by sample. On a multi-view surface the runs are per view image, cut at the
 * crease exactly as [ViewStrokeFeed] cuts a Brush stroke; elsewhere [planeSegments] with the footprint.
 * Empty when nothing lands.
 */
internal fun ActivePlaneMapping.inkSegments(path: List<Offset>, footprint: Float): List<InkSegment> {
    if (!onHull) {
        return planeSegments(
            path,
            sampleOk = { raw, mapped -> flatOnFingerWorld(raw, mapped, footprint) },
            joinOk = { pr, pm, r, m -> flatJoinOnFingerWorld(pr, pm, r, m, footprint) },
            toFlat = { worldToFlatByFootprint(it, footprint) },
        ).map { InkSegment(null, it) }
    }
    val done = ArrayList<InkSegment>()
    var current = ArrayList<Pair<Int, Offset>>()
    var currentView: com.hereliesaz.graffitixr.common.scene.SurfaceView? = null
    fun close() {
        if (current.isNotEmpty()) done += InkSegment(currentView, current)
        current = ArrayList()
    }
    val feed = ViewStrokeFeed<Pair<Int, Offset>>(
        toView = { raw ->
            worldToViewFlatByFootprint(raw.second, footprint)?.let { ViewSample(it.view, raw, raw.first to Offset(it.x, it.y), it.region) }
        },
        bridge = { a, b ->
            val sm = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(a.view, a.flat.second.x, a.flat.second.y)
            val tm = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(b.view, b.flat.second.x, b.flat.second.y)
            val c = com.hereliesaz.graffitixr.common.scene.SurfaceViewStroke.crossing(
                a.raw.second.x, a.raw.second.y, sm, b.raw.second.x, b.raw.second.y, tm,
            ) { x, y -> worldToViewFlatByFootprint(Offset(x, y), 0f) }
            if (c.last === sm && c.first === tm) {
                null
            } else if (!viewFlatSeen(a.view, Offset(c.last.x, c.last.y)) || !viewFlatSeen(b.view, Offset(c.first.x, c.first.y))) {
                // A crease another surface hides is not bridged: the stroke is cut there.
                null
            } else {
                (a.flat.first to Offset(c.last.x, c.last.y)) to (b.flat.first to Offset(c.first.x, c.first.y))
            }
        },
        start = { view, sample -> currentView = view; current = arrayListOf(sample) },
        point = { current += it },
        end = ::close,
        cancel = { current = ArrayList() },
        segmentEnd = ::close,
        sampleOk = { v ->
            viewFlatOnFingerWorld(
                v.raw.second, com.hereliesaz.graffitixr.common.scene.SurfaceMath.ViewFlat(v.view, v.flat.second.x, v.flat.second.y, v.region), footprint,
            )
        },
        joinOk = { a, b ->
            viewJoinOnFingerWorld(a.raw.second, a.flat.second, b.raw.second, b.view, b.region, b.flat.second, footprint)
        },
    )
    path.forEachIndexed { i, p -> if (i == 0) feed.onStart(i to p) else feed.onPoint(i to p) }
    feed.onEnd()
    return done
}
