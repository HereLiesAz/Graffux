@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "TooManyFunctions", "MaxLineLength", "ComplexCondition", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isContainer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A layer group's surface (spec/3d-space.md, "Group surfaces"): a profile curve swept without bound
 * along a direction. The layers inside the group are paint on it.
 *
 * Everything is in the group's own box-local 3D coordinates (the space its children's boxes sit
 * in), so the group's plane pose and every enclosing group move the surface with it. [points] are
 * the profile's vertices, x, y, z interleaved; [sweepX], [sweepY], [sweepZ] the unit direction the
 * profile is swept along. A point of the surface is `P(u) + t * sweep`, `u` the arc length along
 * the profile from its first vertex and `t >= 0`. Paint maps by arc length: the surface's unrolled
 * coordinates `(u, t)` are the group's flat box coordinates, so a stroke does not stretch however
 * the profile bends.
 */
@Serializable
data class SurfaceProfile(
    val points: List<Float>,
    val sweepX: Float = 0f,
    val sweepY: Float = 0f,
    val sweepZ: Float = 1f,
) {
    val count: Int get() = points.size / 3

    fun point(i: Int): FloatArray = floatArrayOf(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])

    val sweep: FloatArray get() = floatArrayOf(sweepX, sweepY, sweepZ)

    /** The arc length at each vertex: 0 at the first, [length] at the last. */
    @Transient
    val arcLengths: FloatArray = FloatArray(count).also { a ->
        for (i in 1 until a.size) {
            val dx = points[i * 3] - points[i * 3 - 3]
            val dy = points[i * 3 + 1] - points[i * 3 - 2]
            val dz = points[i * 3 + 2] - points[i * 3 - 1]
            a[i] = a[i - 1] + sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    val length: Float get() = if (arcLengths.isEmpty()) 0f else arcLengths.last()

    /** A straight profile: the surface is one plane. */
    val isFlat: Boolean get() = count <= 2

    val isValid: Boolean
        get() = count >= 2 && points.size % 3 == 0 && points.all { it.isFinite() } && length > 0f &&
            sweepX.isFinite() && sweepY.isFinite() && sweepZ.isFinite()

    /** The surface point at arc length [u] (clamped to the profile) and sweep distance [t]. */
    fun at(u: Float, t: Float): FloatArray {
        val i = segmentAt(u)
        val a = arcLengths[i]
        val seg = (arcLengths[i + 1] - a).coerceAtLeast(1e-9f)
        val f = ((u - a) / seg).coerceIn(0f, 1f)
        return FloatArray(3) { k ->
            points[i * 3 + k] + (points[(i + 1) * 3 + k] - points[i * 3 + k]) * f + sweep[k] * t
        }
    }

    /** The segment holding arc length [u]: index of its first vertex. */
    fun segmentAt(u: Float): Int {
        var i = 0
        while (i < count - 2 && arcLengths[i + 1] < u) i++
        return i
    }
}

/** The ray hit on a surface: unrolled coordinates and the ray distance (scene pixels). */
data class SurfaceHit(val u: Float, val t: Float, val distance: Float)

/** The group that owns [layerId]'s surface and the chains either side of it. */
class SurfaceOwner(
    val group: Layer,
    val profile: SurfaceProfile,
    /** The group's poses, outermost group first, the group itself last. */
    val ownerChain: List<PlanePose>,
    /** The poses below the group down to the layer itself (the layer last); empty for the group. */
    val innerChain: List<PlanePose>,
)

/**
 * The maths of group surfaces (spec/3d-space.md, "Group surfaces"): capturing a profile from a
 * stroke, casting a touch onto the surface, the unrolled (arc-length) paint mapping, the mesh the
 * depth pass draws and the guide grid. Pure Kotlin.
 */
object SurfaceMath {
    /** At most this many profile vertices; a longer stroke is simplified harder. */
    const val MAX_POINTS = 256

    /** Profile simplification tolerance, in scene pixels. */
    const val SIMPLIFY_TOLERANCE = 1.5f

    /** Guide grid spacing, in the group's box units. */
    const val GRID_SPACING = 64f

    /** The most guide lines drawn in either direction. */
    const val GRID_MAX_LINES = 96

    /**
     * The nearest group owning a surface for [layerId]: the layer itself when it is a group with a
     * profile, else its nearest enclosing group with one. Null when there is none (a flat layer).
     */
    fun ownerOf(layers: List<Layer>, layerId: String?): SurfaceOwner? =
        if (layerId == null) null else ownerOf(layers.associateBy { it.id }, layerId)

    /** [ownerOf] over the layers by id, for a caller asking about many layers of one document. */
    fun ownerOf(byId: Map<String, Layer>, layerId: String?): SurfaceOwner? {
        if (layerId == null) return null
        val path = ArrayList<Layer>()
        var current = byId[layerId]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            path += current
            current = current.parentId?.let(byId::get)
        }
        // path: the layer first, outermost group last.
        val ownerIndex = path.indexOfFirst { it.isContainer && it.surface?.isValid == true }
        if (ownerIndex < 0) return null
        val owner = path[ownerIndex]
        val ownerChain = path.subList(ownerIndex, path.size).asReversed().map { it.planePose }
        val inner = path.subList(0, ownerIndex).asReversed().map { it.planePose }
        return SurfaceOwner(owner, owner.surface!!, ownerChain, inner)
    }

    /** Whether [layerId] is paint on a group surface (a non-group layer inside a profiled group). */
    fun isOnSurface(layers: List<Layer>, layerId: String): Boolean {
        val layer = layers.firstOrNull { it.id == layerId } ?: return false
        return !layer.isContainer && ownerOf(layers, layerId) != null
    }

    /**
     * The group whose guide grid shows: the active layer's surface group, while [visible] (the
     * toggle) is on; null otherwise. At most one grid, and never another group's.
     */
    fun gridGroupId(layers: List<Layer>, activeId: String?, visible: Boolean): String? =
        if (visible) ownerOf(layers, activeId)?.group?.id else null

    /** Every layer drawn on a group surface. */
    fun surfaceLayerIds(layers: List<Layer>): Set<String> {
        if (layers.none { it.isContainer && it.surface != null }) return emptySet()
        return layers.filter { !it.isContainer && ownerOf(layers, it.id) != null }.mapTo(HashSet()) { it.id }
    }

    /**
     * The profile for a stroke drawn in the current view ([world] canvas points, in order), for the
     * group whose chain is [groupChain] (outermost first, the group itself last). Each point is cast
     * from the eye onto the view plane (facing the camera through its orbit target), so at the
     * default view the profile lies on the picture plane exactly under the stroke; the sweep is the
     * view direction, away from the viewer. Null for a stroke too short to be a curve.
     */
    fun capture(
        world: List<Pair<Float, Float>>,
        camera: SceneCamera,
        width: Float,
        height: Float,
        groupChain: List<PlanePose>,
    ): SurfaceProfile? {
        if (world.size < 2 || width <= 0f || height <= 0f) return null
        val cam = SceneMath.frame(camera, width, height)
        val toLocal = SceneMath.chainModel(groupChain, width, height).inverse() ?: return null
        val planeD = SceneMath.dot(cam.forward, cam.target)
        val scene = world.mapNotNull { (x, y) ->
            val dir = cam.rayDirection(x, y)
            val denom = SceneMath.dot(cam.forward, dir)
            if (denom < 1e-6f) return@mapNotNull null
            val s = (planeD - SceneMath.dot(cam.forward, cam.eye)) / denom
            FloatArray(3) { cam.eye[it] + dir[it] * s }
        }
        if (scene.size < 2) return null
        var tolerance = SIMPLIFY_TOLERANCE
        var simplified = simplify(scene, tolerance)
        while (simplified.size > MAX_POINTS) {
            tolerance *= 2f
            simplified = simplify(scene, tolerance)
        }
        val local = simplified.map { toLocal.apply(it[0], it[1], it[2]) }
        val sweep = SceneMath.normalize(toLocal.applyVector(cam.forward[0], cam.forward[1], cam.forward[2]))
        val profile = SurfaceProfile(local.flatMap { it.toList() }, sweep[0], sweep[1], sweep[2])
        return profile.takeIf { it.isValid && it.length >= MIN_LENGTH }
    }

    /** Ramer-Douglas-Peucker: the vertices that keep the polyline within [tolerance]. */
    fun simplify(points: List<FloatArray>, tolerance: Float): List<FloatArray> {
        val deduped = ArrayList<FloatArray>(points.size)
        for (p in points) if (deduped.isEmpty() || dist(deduped.last(), p) > 1e-3f) deduped += p
        if (deduped.size <= 2) return deduped
        val keep = BooleanArray(deduped.size)
        keep[0] = true
        keep[deduped.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to deduped.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1
            var worstD = tolerance
            for (i in a + 1 until b) {
                val d = segmentDistance(deduped[i], deduped[a], deduped[b])
                if (d > worstD) {
                    worst = i
                    worstD = d
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(a to worst)
                stack.addLast(worst to b)
            }
        }
        return deduped.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * The ray from the eye through canvas point ([x], [y]) against the surface placed by [model]
     * (the owner's box-local to scene map): the nearest hit in front of the near plane, or null
     * when the ray misses every strip.
     */
    fun hit(x: Float, y: Float, profile: SurfaceProfile, model: Affine3, cam: CameraFrame): SurfaceHit? {
        val inv = model.inverse() ?: return null
        val dirW = cam.rayDirection(x, y)
        val eye = inv.apply(cam.eye[0], cam.eye[1], cam.eye[2])
        val dir = inv.applyVector(dirW[0], dirW[1], dirW[2])
        val d = profile.sweep
        val near = SceneMath.nearPlane(cam.focal)
        var best: SurfaceHit? = null
        for (i in 0 until profile.count - 1) {
            val p = profile.point(i)
            val q = profile.point(i + 1)
            val e = FloatArray(3) { q[it] - p[it] }
            // eye + s * dir = p + a * e + t * d, solved for (s, a, t).
            val rhs = FloatArray(3) { p[it] - eye[it] }
            val ne = FloatArray(3) { -e[it] }
            val nd = FloatArray(3) { -d[it] }
            val det = det3(dir, ne, nd)
            if (abs(det) < 1e-9f) continue
            val s = det3(rhs, ne, nd) / det
            val a = det3(dir, rhs, nd) / det
            val t = det3(dir, ne, rhs) / det
            if (!s.isFinite() || s <= near || a < -EDGE || a > 1f + EDGE || t < -T_EDGE) continue
            if (best == null || s < best.distance) {
                val seg = profile.arcLengths[i + 1] - profile.arcLengths[i]
                best = SurfaceHit(profile.arcLengths[i] + a.coerceIn(0f, 1f) * seg, t.coerceAtLeast(0f), s)
            }
        }
        return best
    }

    /**
     * Unrolled surface coordinates to the flat point the 2D paint pipeline expects for the leaf of
     * [innerChain] (the poses below the owning group, the leaf last): the groups between the owner
     * and the leaf are undone flat, and the leaf's own 2D transform is left for `mapScreenToBitmap`.
     */
    fun unrolledToFlat(u: Float, t: Float, innerChain: List<PlanePose>, width: Float, height: Float): Pair<Float, Float>? {
        if (innerChain.size <= 1) return u to t
        val between = SceneMath.chainModel(innerChain.dropLast(1).map(::flatPose), width, height).inverse() ?: return null
        val p = between.apply(u, t, 0f)
        return p[0] to p[1]
    }

    /** The inverse of [unrolledToFlat]. */
    fun flatToUnrolled(x: Float, y: Float, innerChain: List<PlanePose>, width: Float, height: Float): Pair<Float, Float> {
        if (innerChain.size <= 1) return x to y
        val p = SceneMath.chainModel(innerChain.dropLast(1).map(::flatPose), width, height).apply(x, y, 0f)
        return p[0] to p[1]
    }

    /** A leaf's box coordinates to unrolled coordinates: its whole inner chain, flat. */
    fun boxToUnrolled(innerChain: List<PlanePose>, width: Float, height: Float): Affine3 =
        SceneMath.chainModel(innerChain.map(::flatPose), width, height)

    private fun flatPose(p: PlanePose) = p.copy(positionZ = 0f, rotationX = 0f, rotationY = 0f)

    /**
     * Painting on a surface (spec/3d-space.md, "Group surfaces"): the flat point for a touch at
     * canvas point ([x], [y]) on [owner]'s leaf, or null when the ray misses the surface.
     */
    fun worldToFlat(x: Float, y: Float, owner: SurfaceOwner, camera: SceneCamera, width: Float, height: Float): Pair<Float, Float>? {
        if (width <= 0f || height <= 0f) return null
        val cam = SceneMath.frame(camera, width, height)
        val model = SceneMath.chainModel(owner.ownerChain, width, height)
        val h = hit(x, y, owner.profile, model, cam) ?: return null
        return unrolledToFlat(h.u, h.t, owner.innerChain, width, height)
    }

    /** Where a flat point of [owner]'s leaf shows on the canvas; null behind the eye. */
    fun flatToWorld(x: Float, y: Float, owner: SurfaceOwner, camera: SceneCamera, width: Float, height: Float): Pair<Float, Float>? {
        if (width <= 0f || height <= 0f) return null
        val (u, t) = flatToUnrolled(x, y, owner.innerChain, width, height)
        val cam = SceneMath.frame(camera, width, height)
        val local = owner.profile.at(u, t.coerceAtLeast(0f))
        val p = SceneMath.chainModel(owner.ownerChain, width, height).apply(local[0], local[1], local[2])
        return cam.project(p)
    }

    /** Camera depth of the surface hit for ([x], [y]); null on a miss. */
    fun hitDepth(x: Float, y: Float, owner: SurfaceOwner, camera: SceneCamera, width: Float, height: Float): Float? {
        if (width <= 0f || height <= 0f) return null
        val cam = SceneMath.frame(camera, width, height)
        val model = SceneMath.chainModel(owner.ownerChain, width, height)
        val h = hit(x, y, owner.profile, model, cam) ?: return null
        val local = owner.profile.at(h.u, h.t)
        return cam.toCamera(model.apply(local[0], local[1], local[2]))[2]
    }

    /**
     * The triangles that carry a layer's image on the surface: one strip per profile segment along
     * the sweep, covering the unrolled rectangle [uMin]..[uMax] x [tMin]..[tMax] (clamped to the
     * profile and to `t >= 0`). Each vertex is x, y, z (owner box-local) then u, t. Strips are
     * planar quads, so the texture is mapped exactly. Empty when the rectangle misses the surface.
     */
    fun mesh(profile: SurfaceProfile, uMin: Float, uMax: Float, tMin: Float, tMax: Float): FloatArray {
        val u0 = uMin.coerceAtLeast(0f)
        val u1 = uMax.coerceAtMost(profile.length)
        val t0 = tMin.coerceAtLeast(0f)
        val t1 = tMax
        if (u1 <= u0 || t1 <= t0) return FloatArray(0)
        val cuts = ArrayList<Float>()
        cuts += u0
        for (a in profile.arcLengths) if (a > u0 && a < u1) cuts += a
        cuts += u1
        val out = FloatArray((cuts.size - 1) * 6 * 5)
        var k = 0
        fun put(u: Float, t: Float) {
            val p = profile.at(u, t)
            out[k++] = p[0]; out[k++] = p[1]; out[k++] = p[2]; out[k++] = u; out[k++] = t
        }
        for (i in 0 until cuts.size - 1) {
            val a = cuts[i]
            val b = cuts[i + 1]
            put(a, t0); put(b, t0); put(b, t1)
            put(a, t0); put(b, t1); put(a, t1)
        }
        return out
    }

    /**
     * The guide grid (spec/3d-space.md, "Guide grid"): canvas-space segments `x0, y0, x1, y1` of
     * lines along the profile (at sweep distances 0, [spacing], 2 [spacing]...) and lines along the
     * sweep (at arc lengths 0, [spacing]...,  and the profile's end), projected through [cam] and
     * clipped to the near plane, so they curve and foreshorten with the view.
     */
    fun gridLines(profile: SurfaceProfile, model: Affine3, cam: CameraFrame, spacing: Float = GRID_SPACING): List<FloatArray> {
        if (!profile.isValid || spacing <= 0f) return emptyList()
        val out = ArrayList<FloatArray>()
        val far = SceneMath.farPlane(cam.focal) * 0.5f
        val scale = sqrt(SceneMath.dot(model.column(0), model.column(0))).coerceAtLeast(1e-6f)
        val reach = far / scale
        fun scene(u: Float, t: Float): FloatArray {
            val p = profile.at(u, t)
            return model.apply(p[0], p[1], p[2])
        }
        // Lines along the profile.
        var t = 0f
        var n = 0
        while (t <= reach && n < GRID_MAX_LINES) {
            for (i in 0 until profile.count - 1) {
                segment(scene(profile.arcLengths[i], t), scene(profile.arcLengths[i + 1], t), cam)?.let(out::add)
            }
            n++
            t = n * spacing
        }
        // Lines along the sweep.
        val us = ArrayList<Float>()
        var u = 0f
        while (u < profile.length && us.size < GRID_MAX_LINES) {
            us += u
            u += spacing
        }
        us += profile.length
        for (a in us) segment(scene(a, 0f), scene(a, reach), cam)?.let(out::add)
        return out
    }

    /** A scene segment on the canvas, clipped to the near plane; null when wholly behind it. */
    fun segment(a: FloatArray, b: FloatArray, cam: CameraFrame): FloatArray? {
        val near = SceneMath.nearPlane(cam.focal) * 2f
        var ca = cam.toCamera(a)
        var cb = cam.toCamera(b)
        if (ca[2] < near && cb[2] < near) return null
        if (ca[2] < near) ca = lerpTo(cb, ca, near)
        if (cb[2] < near) cb = lerpTo(ca, cb, near)
        fun px(c: FloatArray) = floatArrayOf(cam.width / 2f + cam.focal * c[0] / c[2], cam.height / 2f + cam.focal * c[1] / c[2])
        val p = px(ca)
        val q = px(cb)
        return floatArrayOf(p[0], p[1], q[0], q[1])
    }

    private fun lerpTo(inside: FloatArray, outside: FloatArray, z: Float): FloatArray {
        val f = (inside[2] - z) / (inside[2] - outside[2])
        return FloatArray(3) { inside[it] + (outside[it] - inside[it]) * f }
    }

    private fun det3(a: FloatArray, b: FloatArray, c: FloatArray): Float =
        a[0] * (b[1] * c[2] - b[2] * c[1]) - b[0] * (a[1] * c[2] - a[2] * c[1]) + c[0] * (a[1] * b[2] - a[2] * b[1])

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun segmentDistance(p: FloatArray, a: FloatArray, b: FloatArray): Float {
        val ab = FloatArray(3) { b[it] - a[it] }
        val ap = FloatArray(3) { p[it] - a[it] }
        val len2 = SceneMath.dot(ab, ab)
        if (len2 < 1e-12f) return dist(p, a)
        val f = (SceneMath.dot(ap, ab) / len2).coerceIn(0f, 1f)
        return dist(p, FloatArray(3) { a[it] + ab[it] * f })
    }

    /** Shortest profile kept, scene pixels: anything shorter is a tap, not a curve. */
    private const val MIN_LENGTH = 4f

    /** A hit this close past a segment's end still counts, so a ray never slips between strips. */
    private const val EDGE = 1e-4f

    /** Likewise at the profile's own edge (t = 0), in box units: rounding never loses the curve. */
    private const val T_EDGE = 1e-2f
}
