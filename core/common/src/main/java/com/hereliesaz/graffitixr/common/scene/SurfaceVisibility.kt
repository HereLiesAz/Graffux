@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "MaxLineLength")

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
        val near = SceneMath.nearPlane(cam.focal)
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
                val hit = SurfaceMath.hitRay(dir, g.profile, g.model, cam) ?: continue
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

        /** The visibility of every drawn surface group of [layers] from [camera] on a [width] by [height] canvas. */
        fun of(layers: List<Layer>, camera: SceneCamera, width: Float, height: Float): SurfaceVisibility {
            val cam = SceneMath.frame(camera, width, height)
            val byId = layers.associateBy { it.id }
            val groups = ArrayList<Group>()
            for (layer in layers) {
                if (!layer.isContainer || layer.surface?.isValid != true || !drawn(byId, layer)) continue
                val profile = layer.surface!!
                val model = SceneMath.chainModel(planeChain(layers, layer.id), width, height)
                val inv = model.inverse() ?: continue
                val (lo, hi) = SurfaceMath.localBounds(profile) ?: continue
                val pad = max(hi[0] - lo[0], max(hi[1] - lo[1], hi[2] - lo[2])) * 1e-3f + 1e-2f +
                    // A sheet is cast as far as the guide grid reaches, margin included (as the tap pick always did).
                    if (profile.relief == null && !profile.hasHull) SurfaceMath.GRID_MARGIN else 0f
                val paddedLo = FloatArray(3) { lo[it] - pad }
                val paddedHi = FloatArray(3) { hi[it] + pad }
                val hull = if (profile.hasHull) SurfaceHull.of(profile) else null
                groups += Group(profile, model, inv, hull, paddedLo, paddedHi, SurfaceMath.gridExtent(profile))
            }
            return SurfaceVisibility(cam, groups)
        }
    }
}
