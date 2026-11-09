@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "MaxLineLength", "NestedBlockDepth", "ComplexCondition")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the camera sees of the surfaces (spec/3d-space.md, "Paint reaches only what the camera sees"): the
 * nearest surface along an eye ray over every drawn group's hull, relief or sheet, and from it whether a
 * given surface point is the first thing its eye ray meets. A group that is hidden, under a hidden group or
 * at zero opacity is not drawn, so it neither takes paint nor hides any. This is the machinery the tap pick
 * ([OrbitPivot.pick]) uses, built once per camera: a stroke makes one per stroke and asks per sample.
 *
 * Cost per query: one box-slab test per drawn group (a group whose box the ray misses, or enters beyond the
 * point asked about, is skipped), then the group's own ray cast for the groups that remain (hull intervals,
 * the sheet's strips, the relief's cell walk) plus a few small float arrays: the same work as one touch-ray
 * mapping per drawn group.
 */
class SurfaceVisibility private constructor(
    private val cam: CameraFrame,
    private val groups: List<Group>,
) {
    private class Group(
        val profile: SurfaceProfile,
        val model: Affine3,
        val inverse: Affine3,
        val hull: SurfaceHull?,
        val lo: FloatArray,
        val hi: FloatArray,
        /** How far along the sweep the sheet is drawn (box units): the rendered images', else the guide grid's. */
        val gridReach: Float,
    )

    /** True when no drawn group has a surface: nothing can hide anything. */
    val isEmpty: Boolean get() = groups.isEmpty()

    /**
     * Distance along the unit scene direction [dir] from the eye to the nearest surface, or null when the ray
     * meets none. Groups whose box the ray enters beyond [limit] are not cast (a caller who only needs to
     * know whether something is nearer than [limit]); the result may then be null or beyond [limit].
     */
    fun nearest(dir: FloatArray, limit: Float = Float.POSITIVE_INFINITY): Float? {
        var best = Float.MAX_VALUE
        // The depth pass clips by camera-space depth, so the near plane is a depth: along this ray it is farther.
        val near = SceneMath.nearPlane(cam.focal) / SceneMath.dot(dir, cam.forward).coerceAtLeast(1e-6f)
        for (g in groups) {
            val eye = g.inverse.apply(cam.eye[0], cam.eye[1], cam.eye[2])
            val d = g.inverse.applyVector(dir[0], dir[1], dir[2])
            // Coarse: the ray against the group's box (scaled for a model that is not rigid, by the
            // ray parameter being unchanged by an affine map).
            val entry = boxEntry(g, eye, d)
            if (entry == null || entry > limit || entry > best) continue
            val s: Float
            if (g.hull != null) {
                s = (g.hull.hit(eye, d, near) ?: continue).distance
            } else {
                val hit = SurfaceMath.hitRay(dir, g.profile, g.model, cam, near) ?: continue
                // The sheet is swept without bound but drawn only as far as the guide grid reaches.
                if (g.profile.relief == null && hit.t > g.gridReach) continue
                s = hit.distance
            }
            if (s < best) best = s
        }
        return if (best == Float.MAX_VALUE) null else best
    }

    /**
     * Whether the scene point [p], a point of some drawn surface, is in direct view: the ray from the eye to
     * it first meets a surface at [p] itself (within [tolerance]), not at something nearer, and [p] is in
     * front of the eye. A point behind a fold, behind another group, or on the far side of a solid fails.
     */
    fun visible(p: FloatArray, tolerance: Float = toleranceAt(distanceTo(p))): Boolean {
        val d = distanceTo(p)
        if (!(d > 1e-6f) || !d.isFinite()) return false
        if (cam.toCamera(p)[2] <= SceneMath.nearPlane(cam.focal)) return false
        if (groups.isEmpty()) return true
        val dir = FloatArray(3) { (p[it] - cam.eye[it]) / d }
        val s = nearest(dir, d + tolerance) ?: return true
        return s >= d - tolerance
    }

    private fun distanceTo(p: FloatArray): Float {
        val dx = p[0] - cam.eye[0]
        val dy = p[1] - cam.eye[1]
        val dz = p[2] - cam.eye[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Slack at distance [d]: float error in the surface mapping, which grows with distance, not with the grazing angle (the ray is cast at the point itself). */
    fun toleranceAt(d: Float): Float = d * REL_TOLERANCE + ABS_TOLERANCE

    private fun boxEntry(g: Group, o: FloatArray, d: FloatArray): Float? {
        var s0 = 0f
        var s1 = Float.POSITIVE_INFINITY
        for (k in 0..2) {
            val lo = g.lo[k]
            val hi = g.hi[k]
            if (abs(d[k]) < 1e-12f) {
                if (o[k] < lo || o[k] > hi) return null
            } else {
                val a = (lo - o[k]) / d[k]
                val b = (hi - o[k]) / d[k]
                s0 = max(s0, min(a, b))
                s1 = min(s1, max(a, b))
            }
        }
        return if (s0 <= s1) s0 else null
    }

    companion object {
        /** Relative slack: 0.2% of the eye distance. */
        const val REL_TOLERANCE = 2e-3f

        /** Absolute slack, scene pixels. */
        const val ABS_TOLERANCE = 0.5f

        /** Whether [layer] is drawn: not hidden itself or under a hidden group, and not at zero opacity (own times every group's). */
        fun drawn(byId: Map<String, Layer>, layer: Layer): Boolean {
            var opacity = 1f
            var current: Layer? = layer
            val seen = HashSet<String>()
            while (current != null && seen.add(current.id)) {
                if (!current.isVisible) return false
                opacity *= current.opacity
                current = current.parentId?.let(byId::get)
            }
            return opacity > 0f
        }

        private fun defaultImageRect(layer: Layer, width: Float, height: Float): FloatArray? {
            val bmp = layer.bitmap
            return when {
                bmp != null -> DepthComposite.imageRect(layer, bmp.width, bmp.height, width, height)
                layer.shapes.isNotEmpty() -> floatArrayOf(0f, 0f, width, height)
                else -> null
            }
        }

        /**
         * How far along the sweep the images painted on the sheet [group] are drawn (the farthest unrolled `t` of
         * any drawn child's image rectangle, as `DepthScene.surfaceMesh` meshes it); null when none draws an image.
         */
        private fun renderedReach(
            layers: List<Layer>,
            byId: Map<String, Layer>,
            group: Layer,
            width: Float,
            height: Float,
            imageRect: (Layer) -> FloatArray?,
        ): Float? {
            var reach: Float? = null
            for (child in layers) {
                if (child.isContainer || child.spatial != null || child.model != null || child.surfaceViewOf != null) continue
                if (!drawn(byId, child)) continue
                val owner = SurfaceMath.ownerOf(byId, child.id)?.takeIf { it.group.id == group.id } ?: continue
                val r = imageRect(child) ?: continue
                val toUnrolled = SurfaceMath.boxToUnrolled(owner.innerChain, width, height)
                val t = maxOf(
                    toUnrolled.apply(r[0], r[1], 0f)[1], toUnrolled.apply(r[2], r[1], 0f)[1],
                    toUnrolled.apply(r[2], r[3], 0f)[1], toUnrolled.apply(r[0], r[3], 0f)[1],
                )
                reach = maxOf(reach ?: 0f, t)
            }
            return reach
        }

        /** The visibility of every drawn surface group of [layers] from [camera] on a [width] by [height] canvas. */
        fun of(
            layers: List<Layer>,
            camera: SceneCamera,
            width: Float,
            height: Float,
            /**
             * Cast sheets as far as their child images are drawn (what the depth pass meshes), not only as far as the
             * guide grid; the tap pick passes false and keeps the guide extent.
             */
            rendered: Boolean = true,
            /** A paint layer's drawn image rectangle in its box (left, top, right, bottom); null when it draws nothing. */
            imageRect: (Layer) -> FloatArray? = { defaultImageRect(it, width, height) },
        ): SurfaceVisibility {
            val cam = SceneMath.frame(camera, width, height)
            val byId = layers.associateBy { it.id }
            val groups = ArrayList<Group>()
            for (layer in layers) {
                if (!layer.isContainer || layer.surface?.isValid != true || !drawn(byId, layer)) continue
                val profile = layer.surface!!
                val model = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
                val inv = model.inverse() ?: continue
                var (lo, hi) = SurfaceMath.localBounds(profile) ?: continue
                var reach = SurfaceMath.gridExtent(profile)
                var margin = if (profile.relief == null && !profile.hasHull) SurfaceMath.GRID_MARGIN else 0f
                if (rendered && profile.relief == null && !profile.hasHull) {
                    renderedReach(layers, byId, layer, width, height, imageRect)?.let { r ->
                        val l = FloatArray(3) { Float.MAX_VALUE }
                        val h2 = FloatArray(3) { -Float.MAX_VALUE }
                        for (i in 0 until profile.count) {
                            val q = profile.point(i)
                            for (k in 0..2) {
                                val far = q[k] + profile.sweep[k] * r
                                l[k] = min(l[k], min(q[k], far))
                                h2[k] = max(h2[k], max(q[k], far))
                            }
                        }
                        lo = l
                        hi = h2
                        reach = r
                        margin = 0f
                    }
                }
                val pad = max(hi[0] - lo[0], max(hi[1] - lo[1], hi[2] - lo[2])) * 1e-3f + 1e-2f +
                    margin
                val paddedLo = FloatArray(3) { lo[it] - pad }
                val paddedHi = FloatArray(3) { hi[it] + pad }
                val hull = if (profile.hasHull) SurfaceHull.of(profile) else null
                groups += Group(profile, model, inv, hull, paddedLo, paddedHi, reach)
            }
            return SurfaceVisibility(cam, groups)
        }
    }
}
