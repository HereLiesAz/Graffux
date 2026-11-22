package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isAdjustment
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.isSpatial

/**
 * The automatic draw plane's depth (spec/3d-space.md, "Draw plane depth"): where a new camera-facing plane meets the
 * NEAREST EDGE, to the eye, of the surfaces of every plane that already has content. Pure Kotlin.
 *
 * An outline is a list of world points: a plane's quad (a closed polygon) or a surface's outline vertices. The
 * nearest edge of a set of outlines is the point with the smallest depth along the view axis across all of them
 * (depth is linear along an edge, so the minimum is at a vertex); the depth is that point's.
 */
object NearestEdge {
    private const val NEAR_MULTIPLE = 2f

    /** A layer's quad: the canvas rectangle in its box, in world space through [model]. */
    fun quad(model: Affine3, width: Float, height: Float): List<FloatArray> =
        listOf(0f to 0f, width to 0f, width to height, 0f to height).map { (x, y) -> model.apply(x, y, 0f) }

    /**
     * The world-space outline vertices of the surface of [owner]: a relief or hull by its clip box's corners, any
     * other profile by its vertices and the same vertices swept as far as the box reaches ([SurfaceMath.localBounds]).
     */
    fun surfaceOutline(owner: SurfaceOwner, width: Float, height: Float): List<FloatArray>? {
        val profile = owner.profile
        val bounds = SurfaceMath.localBounds(profile) ?: return null
        val model = SceneMath.chainModel(owner.ownerChain, width, height)
        val local = if (profile.relief != null || profile.hasHull) {
            val (lo, hi) = bounds
            (0 until CORNERS).map { m -> FloatArray(AXES) { k -> if (m shr k and 1 == 1) hi[k] else lo[k] } }
        } else {
            val reach = (SurfaceMath.gridExtent(profile) - SurfaceMath.GRID_MARGIN).coerceAtLeast(0f)
            val sweep = profile.sweep
            (0 until profile.count).flatMap { i ->
                val p = profile.point(i)
                listOf(p, FloatArray(AXES) { p[it] + sweep[it] * reach })
            }
        }
        return local.map { model.apply(it[0], it[1], it[2]) }
    }

    /**
     * The outlines of every plane that has content, in its current pose: each visible paint plane for which
     * [hasContent] holds (not paint on a surface: its surface stands for it) as its quad, and each visible group with
     * a valid surface as its surface outline.
     */
    fun outlines(
        layers: List<Layer>,
        width: Float,
        height: Float,
        hasContent: (Layer) -> Boolean,
    ): List<List<FloatArray>> {
        val byId = layers.associateBy { it.id }
        fun shown(l: Layer): Boolean = generateSequence(l) { byId[it.parentId] }.all { it.isVisible }
        return layers.filter(::shown).mapNotNull { layer ->
            when {
                layer.isContainer -> SurfaceMath.ownerOf(byId, layer.id)?.takeIf { it.group.id == layer.id }
                    ?.let { surfaceOutline(it, width, height) }
                layer.isAdjustment || layer.isSpatial || layer.model != null || layer.surfaceViewOf != null ||
                    SurfaceMath.ownerOf(byId, layer.id) != null || !hasContent(layer) -> null
                else -> quad(SceneMath.chainModel(planeChain(layers, layer.id), width, height), width, height)
            }
        }
    }

    /**
     * The depth, along the view axis, of the nearest point of [outlines] in front of twice the near plane (a quad is
     * clipped there, so a plane running behind the eye is met where it enters view); null when nothing is in front.
     */
    fun depth(outlines: List<List<FloatArray>>, cam: CameraFrame): Float? {
        val near = SceneMath.nearPlane(cam.focal) * NEAR_MULTIPLE
        return outlines.mapNotNull { outline ->
            val camera = outline.map(cam::toCamera)
            val kept = if (camera.size == QUAD) SurfaceMath.clipNear(camera, near) else camera.filter { it[2] >= near }
            kept.minOfOrNull { it[2] }
        }.minOrNull()?.takeIf { it.isFinite() }
    }

    private const val AXES = 3
    private const val CORNERS = 8
    private const val QUAD = 4
}
