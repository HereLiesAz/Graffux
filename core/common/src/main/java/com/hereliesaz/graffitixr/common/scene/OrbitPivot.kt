@file:Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isAdjustment
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlin.math.max
import kotlin.math.min

/**
 * The one point everything that revolves revolves about (spec/3d-space.md, "The orbit point"): the
 * rotation slider on every axis, the axis cycler's two-finger turn, camera orbit, and the point a
 * newly adopted view plane passes through.
 *
 * It is the centre of the box that holds the content, measured on REST geometry: every surface's own
 * box and every content plane exactly where it sits with no pose at all (the group chain taken as the
 * identity). A pose is an edit the user makes after the content exists (the rotation slider, the Move
 * tool's twist, translate and scale, a layer handle), so none of them can move the point. Only a
 * structural change to what the box holds does: a surface added, removed or redrawn, or the first or
 * last content layer beside the surfaces added or removed. Nothing is stored; see [ofSurfaces].
 *
 * With no surface the box is layer 1's assumed box: the canvas W wide, H tall and [assumedDepth] deep,
 * so the point is W/2 across, H/2 down and half that depth BEHIND the canvas plane (away from the
 * viewer), whatever the layers' poses.
 */
object OrbitPivot {
    /**
     * The assumed depth D of layer 1's box, for a layer [width] by [height]: its longer side, so the
     * assumed box is a cube on that side. The owner did not state D; this is the choice, kept in
     * one place so it can be changed in one place.
     */
    fun assumedDepth(width: Float, height: Float): Float = max(width, height)

    /** Layers that hold content: not groups and not adjustments. */
    private fun contentLayers(layers: List<Layer>): List<Layer> =
        layers.filter { !it.isContainer && !it.isAdjustment }

    /** The pivot to use: derived only, from the layers ([of]); nothing is stored. */
    fun resolve(layers: List<Layer>, width: Float, height: Float): FloatArray = of(layers, width, height)

    /**
     * The pivot for [layers] on a [width] by [height] canvas, in scene pixels (x, y, z): the centre of
     * the box that holds every surface defined now, at rest (see [ofSurfaces]) when there is one, else
     * the assumed box of the canvas ([assumedDepth]): `(width / 2, height / 2, assumedDepth / 2)`. No
     * pose enters either way.
     */
    fun of(layers: List<Layer>, width: Float, height: Float): FloatArray =
        ofSurfaces(layers, width, height)
            ?: floatArrayOf(width / 2f, height / 2f, assumedDepth(width, height) / 2f)

    /**
     * The extra depth a flat, bounded sheet ([SurfaceProfile.isFlat] with a [SurfaceProfile.sweepLimit]) is given
     * behind its face, as a vector in the group's box space, or null for any other surface. It is
     * `2n` along the sheet's own normal (the profile direction crossed with the sweep: +z, away from the viewer, for a
     * sheet in the plane z = 0), where `n = ((w + h) / 2) / 2` and w, h are the sheet's width and height
     * ([ArtSheet.pivotDepth]): the box that holds the sheet then reaches `2n` behind it, so the centre of the box is `n`
     * behind the sheet's centre. This applies to every such sheet, the predefined one included; no field marks one.
     */
    private fun sheetDepth(profile: SurfaceProfile): FloatArray? {
        val limit = profile.sweepLimit
        if (!profile.isFlat || limit == null) return null
        val a = profile.point(0)
        val b = profile.point(profile.count - 1)
        val e = SceneMath.normalize(floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
        val normal = SceneMath.normalize(SceneMath.cross(e, profile.sweep))
        val d = 2f * ArtSheet.pivotDepth(profile.length, limit)
        return floatArrayOf(normal[0] * d, normal[1] * d, normal[2] * d)
    }

    /**
     * The pivot of the surfaces: the centre of the axis-aligned box that holds the REST geometry of
     * EVERY surface defined at this moment (each profiled group's [SurfaceMath.localBounds], in the
     * group's own box space, taken as scene space) together with the canvas plane when any content
     * layer is not paint on a surface. Null when no group has a surface, which leaves the assumed box
     * of [of].
     *
     * **Rest means no pose.** A group's surface profile is stored in its box-local space, and a group
     * is made with no pose, so its box space IS scene space until the user turns, moves or scales it;
     * a content layer's plane is the canvas rectangle at depth 0 until the user does the same. Those
     * edits are saved as poses ([Layer.rotationX] and friends), but the pivot never reads a pose, so no
     * slider, Move-tool or handle edit can move it, in any order, and a saved project has the same
     * pivot after any number of them. For a project in which no pose has been edited this is exactly
     * the box the posed derivation measured (an identity pose is the identity map). A group posed
     * before its surface was captured (its profile is then stored through the inverse of that pose) is
     * measured unposed too; that is the one place a loaded project's pivot can differ from before.
     *
     * The box's centre is used rather than a vertex or area centroid on purpose: it depends only on
     * the extent of the geometry, so it does not shift when a stroke is simplified, a relief's grid is
     * rebuilt or one surface is tessellated more finely than another. What moves it is structural: a
     * surface is added, removed or redrawn, or the first or last content layer beside the surfaces is
     * added or removed (the canvas plane then joins or leaves the box). Painting never does: a sheet's
     * size is fixed when it is made, from the art then, and later strokes do not re-measure it. Paint
     * layers inside a surface group are skipped, because their own plane is the flat paint space, not
     * where the paint is drawn. An axis the box is flat on keeps that flat position. A flat, bounded
     * sheet (the predefined one and the art-sized one) counts as a slab reaching behind it
     * ([sheetDepth]), so the point sits `n = ((w + h) / 2) / 2` behind its centre.
     */
    fun ofSurfaces(layers: List<Layer>, width: Float, height: Float): FloatArray? {
        val surfaces = layers.filter { it.isContainer && it.surface?.isValid == true }
        if (surfaces.isEmpty()) return null
        val byId = layers.associateBy { it.id }
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun grow(x: Float, y: Float, z: Float) {
            val p = floatArrayOf(x, y, z)
            for (i in 0..2) { lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i]) }
        }
        for (group in surfaces) {
            val (a, b) = SurfaceMath.localBounds(group.surface!!) ?: continue
            val depth = sheetDepth(group.surface!!)
            for (mask in 0 until 8) {
                val x = if (mask and 1 == 0) a[0] else b[0]
                val y = if (mask and 2 == 0) a[1] else b[1]
                val z = if (mask and 4 == 0) a[2] else b[2]
                grow(x, y, z)
                // A flat sheet is a slab of depth 2n behind its face, so the box's centre is n behind it.
                depth?.let { d -> grow(x + d[0], y + d[1], z + d[2]) }
            }
        }
        // Planes that are not paint on a surface (a layer beside the surfaces) are part of the content too:
        // at rest each is the canvas rectangle at depth 0, so one such layer adds exactly that and more add nothing.
        if (contentLayers(layers).any { SurfaceMath.ownerOf(byId, it.id) == null }) {
            for ((u, v) in listOf(0f to 0f, width to 0f, 0f to height, width to height)) grow(u, v, 0f)
        }
        if (lo[0] > hi[0]) return null
        return FloatArray(3) { (lo[it] + hi[it]) / 2f }
    }
}
