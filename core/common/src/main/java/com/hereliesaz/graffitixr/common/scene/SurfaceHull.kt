@file:Suppress("MagicNumber", "ReturnCount", "LongParameterList", "TooManyFunctions", "MaxLineLength", "ComplexCondition", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The three views a group's surface can be drawn from (spec/3d-space.md, "Group surfaces"). */
enum class SurfaceView {
    FRONT,
    SIDE,
    TOP,
    ;

    /**
     * The scene camera that looks at the group along this view's axis, keeping [base]'s distance and
     * pan: the front is the default view, the side turns a quarter about the vertical, the top
     * tilts to the camera's pitch limit (a degree and a half short of straight down, which
     * [SurfaceHull] does not mind: a view's direction is whatever it was drawn along).
     */
    fun camera(base: SceneCamera): SceneCamera = when (this) {
        FRONT -> base.copy(yaw = 0f, pitch = 0f)
        SIDE -> base.copy(yaw = (Math.PI / 2).toFloat(), pitch = 0f)
        TOP -> base.copy(yaw = 0f, pitch = SceneCamera.MAX_PITCH)
    }
}

/**
 * Where a ray meets the intersected surface ([SurfaceHull.hit]): the point in the hull's (the
 * group's box-local) space, the surface normal facing the ray, and the ray parameter. [view] is
 * the drawn outline whose swept sheet carries the point (null on a cap, see [cap]); [u] and [t] are
 * that sheet's unrolled coordinates, as for [SurfaceHit]: arc length along the closed outline, and
 * the signed distance along its sweep from the outline's plane (negative behind it, unlike a
 * front-only surface, which starts at 0).
 */
class HullHit(
    val x: Float,
    val y: Float,
    val z: Float,
    val nx: Float,
    val ny: Float,
    val nz: Float,
    val distance: Float,
    val view: SurfaceView?,
    val edge: Int,
    val u: Float,
    val t: Float,
    /** The ray started inside the solid, so this is where it leaves, seen from the inside. */
    val fromInside: Boolean,
    /** For a [cap], the box face it lies on: `axis * 2 + side` (side 0 the low end); -1 on a drawn outline's sheet. */
    val face: Int = -1,
) {
    /** The face is the box that closes an axis no pair of drawn views bounds, not a drawn outline. */
    val cap: Boolean get() = view == null
}

/**
 * Where a touch lands in the paint images ([SurfaceHull.paintTarget]): the [view] whose image, the
 * point ([x], [y]) in it, and the [region] of that image: 0 for the view's own sheet, `1 + face`
 * for a cap. Two touches in different regions are on different faces, so a stroke between them is
 * cut at the crease even though both paint the same image.
 */
class PaintTarget(val view: SurfaceView, val x: Float, val y: Float, val region: Int)

/**
 * Two paint points that are one point of the surface: [at] in the hull's box space, as [from] on one face
 * and [to] on another (or on the far end of the same face's unrolled outline). See [SurfaceHull.creaseLinks].
 */
class CreaseLink(val at: FloatArray, val from: PaintTarget, val to: PaintTarget)

/**
 * The surface of a group drawn from more than one view (spec/3d-space.md, "Group surfaces"),
 * combined by intersection, like silhouette carving: each drawn view's outline, closed by joining
 * its last vertex to its first, is swept through space along that view's direction into an infinite
 * prism, and the solid is every point all the prisms hold. A view left undrawn, or drawn as no area
 * (fewer than three vertices, or all on one line), does not constrain. The surface is that solid's
 * boundary, so it is made of patches of the drawn outlines' swept sheets.
 *
 * An axis that only one prism bounds would run forever, so the solid is also clipped to the box
 * of every drawn outline's vertices (padded to a cube's depth where an outline has none): the
 * solid spans a view's axis only as far as the drawing does.
 *
 * Pure Kotlin, in the group's box-local space. [of] builds it from a [SurfaceProfile]; [hit] casts a
 * ray at it (the front-most surface point, for painting); [meshByView] tessellates it (for drawing).
 */
class SurfaceHull private constructor(
    private val prisms: List<Prism>,
    /** The one open outline kept as a thin wall (see [of]), or -1. It is in [prisms] but never constrains. */
    private val wallIndex: Int,
    private val lo: FloatArray,
    private val hi: FloatArray,
) {
    /** The views that constrain the solid (drawn, with area), in front, side, top order. */
    val views: List<SurfaceView> get() = prisms.map { it.view }

    /**
     * The view drawn as an open outline, kept as a thin wall: when set, the surface is that outline's
     * swept sheet, trimmed to what the other views' outlines and the box hold, and the closed views
     * only bound it. Null when every constraining view is closed (a solid).
     */
    val wall: SurfaceView? get() = prisms.getOrNull(wallIndex)?.view

    /** The box every drawn outline's vertices fit in (min x, y, z), the solid's clip. */
    val boundsMin: FloatArray get() = lo.copyOf()

    /** The box every drawn outline's vertices fit in (max x, y, z). */
    val boundsMax: FloatArray get() = hi.copyOf()

    /** Whether [x], [y], [z] is in the solid or on its surface (within [TIE_EPS]). */
    fun contains(x: Float, y: Float, z: Float): Boolean {
        val p = floatArrayOf(x, y, z)
        for (k in 0..2) if (p[k] < lo[k] - TIE_EPS || p[k] > hi[k] + TIE_EPS) return false
        return prisms.withIndex().all { (j, prism) -> j == wallIndex || prism.contains(p, strict = false) }
    }

    /**
     * The front-most surface point along the ray `origin + s * dir` past `s = near`: where it
     * enters the solid, or, when it starts inside, where it leaves. Null when it misses. [dir]
     * need not be a unit vector; [HullHit.distance] is in the same units as `s`.
     */
    fun hit(origin: FloatArray, dir: FloatArray, near: Float = 0f): HullHit? {
        if (wallIndex >= 0) return wallHit(origin, dir, near)
        for (iv in lineIntervals(origin, dir, skip = NO_SKIP)) {
            if (iv.hi <= near) continue
            val entering = iv.lo > near
            val s = if (entering) iv.lo else iv.hi
            val src = if (entering) iv.loSrc else iv.hiSrc
            if (!s.isFinite()) return null
            return build(origin, dir, s, src, fromInside = !entering)
        }
        return null
    }

    /**
     * The surface as triangles, per drawn view: each vertex is x, y, z (box-local) then u, t (that
     * view's unrolled coordinates, as in [HullHit]), so a renderer can map a texture per view. A
     * face the other views cut away is absent; where two views' sheets lie in the same plane it is
     * emitted once (by the earlier of front, side, top). Curved outlines are polylines, so the
     * mesh is exact for them; only a face's trimming against another view's outline is found by
     * sampling, which is exact wherever the trim is straight.
     */
    fun meshByView(): Map<SurfaceView, FloatArray> {
        val out = LinkedHashMap<SurfaceView, FloatArray>()
        for ((k, prism) in prisms.withIndex()) {
            if (wallIndex >= 0 && k != wallIndex) continue
            val tris = ArrayList<Float>()
            for (i in 0 until prism.edges) tessellateFace(k, prism, i, tris)
            if (tris.isNotEmpty()) out[prism.view] = tris.toFloatArray()
        }
        return out
    }

    /**
     * The size of [view]'s paint image (project format 10: one image per view, the views painted
     * independently): width the arc length round its outline, height the solid's reach along its
     * sweep. Null for a view that does not carry surface (undrawn, or not the wall of a wall hull).
     */
    fun paintSize(view: SurfaceView): Pair<Float, Float>? {
        val prism = carrying(view) ?: return null
        val (lo, hi) = sweepRange(prism)
        return prism.cum[prism.edges] to (hi - lo)
    }

    /**
     * Where [hit] lands in its view's paint image: x the arc length round the outline, y the sweep
     * distance measured from the nearest end of the solid so that it is never negative. Null for a cap.
     */
    fun paintPoint(hit: HullHit): Pair<Float, Float>? {
        val view = hit.view ?: return null
        return paintPointAt(view, hit.u, hit.t)
    }

    /** [paintPoint] for unrolled coordinates ([u], [t]) of [view]'s sheet, as [meshByView] gives them. */
    fun paintPointAt(view: SurfaceView, u: Float, t: Float): Pair<Float, Float>? {
        val prism = carrying(view) ?: return null
        return u to t - sweepRange(prism).first
    }

    /**
     * [paintPoint] for any hit: a sheet hit lands in its view's image as [paintPoint] says (region 0); a
     * cap hit lands in its host view's cap region ([capMeshByView]). Null only for a thin-wall hull's
     * stray box hit, which does not occur.
     */
    fun paintTarget(hit: HullHit): PaintTarget? {
        hit.view?.let { v -> return paintPointAt(v, hit.u, hit.t)?.let { PaintTarget(v, it.first, it.second, 0) } }
        if (wallIndex >= 0 || hit.face < 0) return null
        return capPaint(hit.face, hit.x, hit.y, hit.z)
    }

    /**
     * The faces no drawn view bounds, as triangles per host view (spec/3d-space.md, "Caps"): where the
     * solid is closed by the clip box instead of by a drawn outline's sheet (a tilted sweep leaves a
     * sliver of box face; a lone closed outline leaves its two ends). Each vertex is x, y, z (box-local)
     * then the point in the host view's paint image, already in [paintPoint]'s space (the cap's own
     * region, right of the view's sheet). A face that coincides with a drawn sheet is that sheet's,
     * not a cap. Empty for a thin wall.
     */
    fun capMeshByView(): Map<SurfaceView, FloatArray> = caps

    private val caps: Map<SurfaceView, FloatArray> by lazy { buildCaps() }

    /** One drawn triangle with the paint point of each corner: [pts] x, y, z per corner, [paint] x, y per corner. */
    private class FaceTri(val view: SurfaceView, val region: Int, val pts: FloatArray, val paint: FloatArray)

    private val faceTris: List<FaceTri> by lazy {
        val out = ArrayList<FaceTri>()
        for ((view, mesh) in meshByView()) {
            var i = 0
            while (i + 15 <= mesh.size) {
                val pts = FloatArray(9)
                val paint = FloatArray(6)
                for (k in 0..2) {
                    val o = i + k * 5
                    for (c in 0..2) pts[k * 3 + c] = mesh[o + c]
                    val pp = paintPointAt(view, mesh[o + 3], mesh[o + 4]) ?: break
                    paint[k * 2] = pp.first
                    paint[k * 2 + 1] = pp.second
                }
                out += FaceTri(view, 0, pts, paint)
                i += 15
            }
        }
        for ((view, mesh) in caps) {
            var i = 0
            while (i + 15 <= mesh.size) {
                val pts = FloatArray(9)
                val paint = FloatArray(6)
                for (k in 0..2) {
                    val o = i + k * 5
                    for (c in 0..2) pts[k * 3 + c] = mesh[o + c]
                    paint[k * 2] = mesh[o + 3]
                    paint[k * 2 + 1] = mesh[o + 4]
                }
                out += FaceTri(view, 1 + capFaceOf(pts), pts, paint)
                i += 15
            }
        }
        out
    }

    /** The box face (`axis * 2 + side`) all three corners of a cap triangle lie on. */
    private fun capFaceOf(pts: FloatArray): Int {
        for (axis in 0..2) for (side in 0..1) {
            val at = if (side == 0) lo[axis] else hi[axis]
            if ((0..2).all { abs(pts[it * 3 + axis] - at) < TIE_EPS }) return axis * 2 + side
        }
        return 0
    }

    /** Barycentric coordinates of the point on triangle [t] nearest [p], and how far [p] is from the triangle's plane or edge. */
    private fun nearestOn(t: FaceTri, p: FloatArray): Pair<FloatArray, Float> {
        val a = t.pts.copyOfRange(0, 3)
        val e1 = FloatArray(3) { t.pts[3 + it] - a[it] }
        val e2 = FloatArray(3) { t.pts[6 + it] - a[it] }
        val d11 = dot(e1, e1); val d12 = dot(e1, e2); val d22 = dot(e2, e2)
        val ap = FloatArray(3) { p[it] - a[it] }
        val det = d11 * d22 - d12 * d12
        if (abs(det) < 1e-12f) return floatArrayOf(1f, 0f, 0f) to Float.MAX_VALUE
        val v = (d22 * dot(ap, e1) - d12 * dot(ap, e2)) / det
        val w = (d11 * dot(ap, e2) - d12 * dot(ap, e1)) / det
        val cv = v.coerceIn(0f, 1f)
        val cw = w.coerceIn(0f, 1f - cv)
        val q = FloatArray(3) { a[it] + e1[it] * cv + e2[it] * cw }
        val dist = sqrt(dot(FloatArray(3) { p[it] - q[it] }, FloatArray(3) { p[it] - q[it] }))
        return floatArrayOf(1f - cv - cw, cv, cw) to dist
    }

    /**
     * Every face (view image region) the surface point [p] (box space) is on, with its paint point there:
     * one for a point inside a patch, two or more on a crease. A point near no drawn triangle is on none.
     */
    fun facesAt(p: FloatArray): List<PaintTarget> {
        val out = ArrayList<PaintTarget>()
        for (t in faceTris) {
            val (b, dist) = nearestOn(t, p)
            if (dist > TIE_EPS * 1.5f) continue
            out += PaintTarget(
                t.view,
                b[0] * t.paint[0] + b[1] * t.paint[2] + b[2] * t.paint[4],
                b[0] * t.paint[1] + b[1] * t.paint[3] + b[2] * t.paint[5],
                t.region,
            )
        }
        return out
    }

    /**
     * Where one face of the surface meets another (or the far end of its own unrolled outline): points
     * along every boundary edge of every face (an edge no triangle of the same face continues past, in the
     * image), sampled about every [spacing] box units, each with its paint point on the face it is on and on
     * each other place it is (a flood fill, which spreads in images, follows these across a crease).
     */
    fun creaseLinks(spacing: Float = max(hi[0] - lo[0], max(hi[1] - lo[1], hi[2] - lo[2])) / 100f): List<CreaseLink> {
        val out = ArrayList<CreaseLink>()
        val byFace = faceTris.groupBy { it.view to it.region }
        for (t in faceTris) {
            val same = byFace.getValue(t.view to t.region)
            for (e in 0..2) {
                val j = (e + 1) % 3
                val k = (e + 2) % 3
                if (!isBoundary(t, e, j, k, same)) continue
                val len = sqrt(dot(FloatArray(3) { t.pts[j * 3 + it] - t.pts[e * 3 + it] }, FloatArray(3) { t.pts[j * 3 + it] - t.pts[e * 3 + it] }))
                val n = (len / spacing).toInt().coerceIn(1, MAX_LINK_SAMPLES)
                for (s in 0..n) {
                    val f = s.toFloat() / n
                    val at = FloatArray(3) { t.pts[e * 3 + it] + (t.pts[j * 3 + it] - t.pts[e * 3 + it]) * f }
                    val here = PaintTarget(
                        t.view,
                        t.paint[e * 2] + (t.paint[j * 2] - t.paint[e * 2]) * f,
                        t.paint[e * 2 + 1] + (t.paint[j * 2 + 1] - t.paint[e * 2 + 1]) * f,
                        t.region,
                    )
                    for (other in facesAt(at)) {
                        val sameFace = other.view == here.view && other.region == here.region
                        if (sameFace && abs(other.x - here.x) + abs(other.y - here.y) < SEAM_MIN) continue
                        out += CreaseLink(at, here, other)
                    }
                }
            }
        }
        return out
    }

    /** Whether edge [e]-[j] of [t] (opposite corner [k]) has no triangle of its own face across it in the image. */
    private fun isBoundary(t: FaceTri, e: Int, j: Int, k: Int, same: List<FaceTri>): Boolean {
        val ax = t.paint[e * 2]; val ay = t.paint[e * 2 + 1]
        val bx = t.paint[j * 2]; val by = t.paint[j * 2 + 1]
        val cx = t.paint[k * 2]; val cy = t.paint[k * 2 + 1]
        val dx = bx - ax; val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return false
        // The edge's normal, turned away from the triangle's third corner.
        var nx = -dy / len; var ny = dx / len
        if ((cx - ax) * nx + (cy - ay) * ny > 0f) { nx = -nx; ny = -ny }
        val qx = (ax + bx) / 2f + nx * PROBE
        val qy = (ay + by) / 2f + ny * PROBE
        return same.none { o -> o !== t && insidePaint(o, qx, qy) }
    }

    private fun insidePaint(o: FaceTri, x: Float, y: Float): Boolean {
        fun side(i: Int, j: Int) = (o.paint[j * 2] - o.paint[i * 2]) * (y - o.paint[i * 2 + 1]) - (o.paint[j * 2 + 1] - o.paint[i * 2 + 1]) * (x - o.paint[i * 2])
        val s0 = side(0, 1); val s1 = side(1, 2); val s2 = side(2, 0)
        return (s0 >= 0f && s1 >= 0f && s2 >= 0f) || (s0 <= 0f && s1 <= 0f && s2 <= 0f)
    }

    /**
     * The rectangle (left, top, right, bottom) a face occupies in [view]'s paint image: [region] 0 its sheet,
     * `1 + face` one of its caps (see [PaintTarget.region]). It bounds what an image-wide tool (a flood
     * fill) may reach from a touch on that face, so it never runs into the view's other faces, which
     * share the image. Null when the view or region carries nothing.
     */
    fun regionRect(view: SurfaceView, region: Int): FloatArray? {
        if (region <= 0) {
            val (w, h) = paintSize(view) ?: return null
            return floatArrayOf(0f, 0f, w, h)
        }
        val face = region - 1
        if (wallIndex >= 0 || face !in 0..5) return null
        val host = capHost(face / 2)
        if (host.view != view) return null
        val (p, q) = planeAxes(face / 2)
        val x = capOffset(host, face)
        return floatArrayOf(x, 0f, x + hi[p] - lo[p], hi[q] - lo[q])
    }

    /** The view whose image holds the caps on [axis]: the one looking at them most squarely (its sweep most parallel to the axis), the earlier on a tie. */
    private fun capHost(axis: Int): Prism {
        var best = prisms[0]
        for (p in prisms) if (abs(p.s[axis]) > abs(best.s[axis]) + 1e-6f) best = p
        return best
    }

    private fun planeAxes(axis: Int): Pair<Int, Int> = when (axis) {
        0 -> 1 to 2
        1 -> 0 to 2
        else -> 0 to 1
    }

    private fun capGap(): Float = max(hi[0] - lo[0], max(hi[1] - lo[1], hi[2] - lo[2])) * CAP_GAP_RATIO

    /** Where [face]'s region starts in its host's image: right of the sheet, faces in order, a gap between. */
    private fun capOffset(host: Prism, face: Int): Float {
        var x = host.cum[host.edges] + capGap()
        for (g in 0 until face) {
            if (capHost(g / 2) !== host) continue
            val (p, _) = planeAxes(g / 2)
            x += hi[p] - lo[p] + capGap()
        }
        return x
    }

    private fun capPaint(face: Int, x: Float, y: Float, z: Float): PaintTarget {
        val axis = face / 2
        val (p, q) = planeAxes(axis)
        val pos = floatArrayOf(x, y, z)
        val host = capHost(axis)
        return PaintTarget(host.view, capOffset(host, face) + (pos[p] - lo[p]), pos[q] - lo[q], 1 + face)
    }

    private fun buildCaps(): Map<SurfaceView, FloatArray> {
        if (wallIndex >= 0 || prisms.isEmpty()) return emptyMap()
        val out = LinkedHashMap<SurfaceView, ArrayList<Float>>()
        for (face in 0 until 6) {
            val axis = face / 2
            val (p, q) = planeAxes(axis)
            val origin = FloatArray(3)
            origin[axis] = if (face % 2 == 0) lo[axis] else hi[axis]
            origin[p] = lo[p]
            origin[q] = lo[q]
            val e = FloatArray(3)
            e[p] = hi[p] - lo[p]
            val s = FloatArray(3)
            s[q] = 1f
            val raw = ArrayList<Float>()
            tessellate(NO_SKIP, origin, e, s) { af, t ->
                val pos = FloatArray(3) { origin[it] + e[it] * af + s[it] * t }
                raw += pos[0]; raw += pos[1]; raw += pos[2]
                val target = capPaint(face, pos[0], pos[1], pos[2])
                raw += target.x; raw += target.y
            }
            val host = capHost(axis).view
            var i = 0
            while (i + 15 <= raw.size) {
                val c = FloatArray(3) { k -> (raw[i + k] + raw[i + 5 + k] + raw[i + 10 + k]) / 3f }
                // Strictly inside every drawn outline: a point on one's boundary is that sheet's.
                if (prisms.all { it.contains(c, strict = true) }) out.getOrPut(host) { ArrayList() }.addAll(raw.subList(i, i + 15))
                i += 15
            }
        }
        return out.mapValues { it.value.toFloatArray() }
    }

    private fun carrying(view: SurfaceView): Prism? =
        prisms.withIndex().firstOrNull { (j, p) -> p.view == view && (wallIndex < 0 || j == wallIndex) }?.value

    /** The sweep distances (from the outline's plane) the box spans along [prism]'s sweep. */
    private fun sweepRange(prism: Prism): Pair<Float, Float> {
        val p0 = prism.pts3[0]
        var tLo = Float.POSITIVE_INFINITY
        var tHi = Float.NEGATIVE_INFINITY
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val c = floatArrayOf(if (cx == 0) lo[0] else hi[0], if (cy == 0) lo[1] else hi[1], if (cz == 0) lo[2] else hi[2])
            val t = (c[0] - p0[0]) * prism.s[0] + (c[1] - p0[1]) * prism.s[1] + (c[2] - p0[2]) * prism.s[2]
            tLo = min(tLo, t)
            tHi = max(tHi, t)
        }
        return tLo to tHi
    }

    // ---- ray casting -------------------------------------------------------------------------

    /** An interval of a line's parameter inside the solid, and what bounds each end. */
    private class Iv(val lo: Float, val hi: Float, val loSrc: Int, val hiSrc: Int)

    /**
     * The parameters of the line `p0 + l * d` (all of it, `l` from -inf to inf) inside the box and
     * every prism but [skip]. Sorted. [skip] and the tie rule serve [tessellateFace]: a point
     * exactly on another prism's boundary is in it for a later view and out of it for an earlier
     * one, so a plane two views share is the earlier view's.
     */
    private fun lineIntervals(p0: FloatArray, d: FloatArray, skip: Int): List<Iv> {
        var acc = boxInterval(p0, d)?.let { listOf(it) } ?: return emptyList()
        val tol = TIE_EPS / sqrt(dot(d, d)).coerceAtLeast(1e-12f)
        for ((j, prism) in prisms.withIndex()) {
            if (j == skip || j == wallIndex) continue
            acc = intersect(acc, prism.intervals(p0, d, strict = skip != NO_SKIP && skip != wallIndex && j < skip), tol)
            if (acc.isEmpty()) return acc
        }
        return acc
    }

    private fun boxInterval(p0: FloatArray, d: FloatArray): Iv? {
        var l0 = Float.NEGATIVE_INFINITY
        var l1 = Float.POSITIVE_INFINITY
        var s0 = SRC_NONE
        var s1 = SRC_NONE
        for (k in 0..2) {
            if (abs(d[k]) < 1e-9f) {
                if (p0[k] < lo[k] - TIE_EPS || p0[k] > hi[k] + TIE_EPS) return null
                continue
            }
            var a = (lo[k] - p0[k]) / d[k]
            var b = (hi[k] - p0[k]) / d[k]
            var sa = boxSrc(k, 0)
            var sb = boxSrc(k, 1)
            if (a > b) {
                val ta = a; a = b; b = ta
                val ts = sa; sa = sb; sb = ts
            }
            if (a > l0) { l0 = a; s0 = sa }
            if (b < l1) { l1 = b; s1 = sb }
        }
        return if (l1 > l0) Iv(l0, l1, s0, s1) else null
    }

    /**
     * The source of an interval end that [xWins] by position. On an exact tie (two faces in one plane)
     * a drawn outline's sheet beats the box's face, and the earlier outline (front, side, top) beats a
     * later one, so a ray hit is attributed to the view whose patch [meshByView] emits for that plane.
     */
    private fun tie(xAt: Float, xSrc: Int, yAt: Float, ySrc: Int, xWins: Boolean, tol: Float): Int = when {
        // Two faces in one plane meet a slanted ray at parameters a rounding error apart: that is a tie
        // too ([tol] is [TIE_EPS] in the ray's own units), or the hit and the patch would name different views.
        abs(xAt - yAt) <= tol -> if (xSrc >= 0) xSrc else ySrc
        xWins -> xSrc
        else -> ySrc
    }

    private fun intersect(a: List<Iv>, b: List<Iv>, tol: Float): List<Iv> {
        val out = ArrayList<Iv>()
        for (x in a) for (y in b) {
            val l0 = max(x.lo, y.lo)
            val l1 = min(x.hi, y.hi)
            if (l1 > l0) {
                out += Iv(l0, l1, tie(x.lo, x.loSrc, y.lo, y.loSrc, x.lo > y.lo, tol), tie(x.hi, x.hiSrc, y.hi, y.hiSrc, x.hi < y.hi, tol))
            }
        }
        out.sortBy { it.lo }
        return out
    }

    /** The nearest strip of the wall the ray meets inside the other views and the box. */
    private fun wallHit(origin: FloatArray, dir: FloatArray, near: Float): HullHit? {
        val prism = prisms[wallIndex]
        var best: HullHit? = null
        for (i in 0 until prism.edges) {
            val p0 = prism.pts3[i]
            val p1 = prism.pts3[i + 1]
            val e = FloatArray(3) { p1[it] - p0[it] }
            // origin + s dir = p0 + a e + t sweep, solved for (s, a, t).
            val rhs = FloatArray(3) { p0[it] - origin[it] }
            val ne = FloatArray(3) { -e[it] }
            val ns = FloatArray(3) { -prism.s[it] }
            val det = det3(dir, ne, ns)
            if (abs(det) < 1e-9f) continue
            val sd = det3(rhs, ne, ns) / det
            val a = det3(dir, rhs, ns) / det
            if (!sd.isFinite() || sd <= near || a < 0f || a > 1f) continue
            if (best != null && sd >= best.distance) continue
            val x = origin[0] + dir[0] * sd
            val y = origin[1] + dir[1] * sd
            val z = origin[2] + dir[2] * sd
            if (!contains(x, y, z)) continue
            best = build(origin, dir, sd, wallIndex * EDGE_STRIDE + i, fromInside = false)
        }
        return best
    }

    private fun det3(a: FloatArray, b: FloatArray, c: FloatArray): Float =
        a[0] * (b[1] * c[2] - b[2] * c[1]) - b[0] * (a[1] * c[2] - a[2] * c[1]) + c[0] * (a[1] * b[2] - a[2] * b[1])

    private fun build(origin: FloatArray, dir: FloatArray, s: Float, src: Int, fromInside: Boolean): HullHit {
        val px = origin[0] + dir[0] * s
        val py = origin[1] + dir[1] * s
        val pz = origin[2] + dir[2] * s
        val p = floatArrayOf(px, py, pz)
        if (src < 0) {
            val face = -1 - src
            val n = FloatArray(3)
            n[face / 2] = if (face % 2 == 0) -1f else 1f
            return faced(HullHit(px, py, pz, n[0], n[1], n[2], s, null, -1, 0f, 0f, fromInside, face), dir)
        }
        val prism = prisms[src / EDGE_STRIDE]
        val edge = src % EDGE_STRIDE
        val (u, t) = prism.unrolled(p, edge)
        val n2x = prism.ys[(edge + 1) % prism.n] - prism.ys[edge]
        val n2y = -(prism.xs[(edge + 1) % prism.n] - prism.xs[edge])
        val len = sqrt(n2x * n2x + n2y * n2y).coerceAtLeast(1e-12f)
        val nx = (prism.a[0] * n2x + prism.b[0] * n2y) / len
        val ny = (prism.a[1] * n2x + prism.b[1] * n2y) / len
        val nz = (prism.a[2] * n2x + prism.b[2] * n2y) / len
        return faced(HullHit(px, py, pz, nx, ny, nz, s, prism.view, edge, u, t, fromInside), dir)
    }

    /** [hit] with its normal turned to face the ray's origin. */
    private fun faced(h: HullHit, dir: FloatArray): HullHit =
        if (h.nx * dir[0] + h.ny * dir[1] + h.nz * dir[2] > 0f) {
            HullHit(h.x, h.y, h.z, -h.nx, -h.ny, -h.nz, h.distance, h.view, h.edge, h.u, h.t, h.fromInside, h.face)
        } else {
            h
        }

    // ---- tessellation ------------------------------------------------------------------------

    private fun tessellateFace(k: Int, prism: Prism, i: Int, out: MutableList<Float>) {
        val p0 = prism.pts3[i]
        val p1 = prism.pts3[(i + 1) % prism.n]
        val e = FloatArray(3) { p1[it] - p0[it] }
        tessellate(k, p0, e, prism.s) { af, t -> prism.vertex(i, p0, e, af, t, out) }
    }

    /**
     * Triangles of the planar face `p0 + a e + t s` (a in 0..1, t over the sweep range that reaches the
     * box) that lie in the solid and every prism but [skip], each corner reported to [vertex] as (a, t).
     * A sheet is the face of a prism's edge with [skip] that prism; a cap is a face of the box with no skip.
     */
    private fun tessellate(skip: Int, p0: FloatArray, e: FloatArray, s: FloatArray, vertex: (Float, Float) -> Unit) {
        // The sweep range that reaches the box.
        var tLo = Float.POSITIVE_INFINITY
        var tHi = Float.NEGATIVE_INFINITY
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val c = floatArrayOf(if (cx == 0) lo[0] else hi[0], if (cy == 0) lo[1] else hi[1], if (cz == 0) lo[2] else hi[2])
            val tc = (c[0] - p0[0]) * s[0] + (c[1] - p0[1]) * s[1] + (c[2] - p0[2]) * s[2]
            tLo = min(tLo, tc)
            tHi = max(tHi, tc)
        }
        if (tHi - tLo < 1e-4f) return
        val cuts = ArrayList<Float>()
        cuts += tLo
        cuts += tHi
        // Where the face's two long edges (a = 0 and a = 1) cross the box and every other prism's outline,
        // and where each other outline's vertices line up with the face: the trim changes shape there.
        for (a in 0..1) {
            val c = FloatArray(3) { p0[it] + e[it] * a }
            for (axis in 0..2) {
                if (abs(s[axis]) < 1e-6f) continue
                cuts += (lo[axis] - c[axis]) / s[axis]
                cuts += (hi[axis] - c[axis]) / s[axis]
            }
        }
        for ((j, other) in prisms.withIndex()) {
            if (j == skip || j == wallIndex) continue
            val q0x = dot(p0, other.a)
            val q0y = dot(p0, other.b)
            val ax = dot(e, other.a)
            val ay = dot(e, other.b)
            val bx = dot(s, other.a)
            val by = dot(s, other.b)
            val cross = bx * ay - by * ax
            for (v in 0 until other.n) {
                val vx = other.xs[v]
                val vy = other.ys[v]
                if (abs(cross) > 1e-9f) cuts += ((vx - q0x) * ay - (vy - q0y) * ax) / cross
                val wx = other.xs[(v + 1) % other.n] - vx
                val wy = other.ys[(v + 1) % other.n] - vy
                for (a in 0..1) {
                    val cx = q0x + ax * a - vx
                    val cy = q0y + ay * a - vy
                    // v + f w = (q0 + a A) + t B  ->  solve for f, t.
                    val det = -wx * by + wy * bx
                    if (abs(det) < 1e-9f) continue
                    val f = (-cx * by + cy * bx) / det
                    val t = (wx * cy - wy * cx) / det
                    if (f in 0f..1f) cuts += t
                }
            }
        }
        val ts = cuts.filter { it.isFinite() && it >= tLo && it <= tHi }.sorted()
        val merged = ArrayList<Float>()
        for (t in ts) if (merged.isEmpty() || t - merged.last() > 1e-4f) merged += t
        for (m in 0 until merged.size - 1) slab(skip, s, p0, e, merged[m], merged[m + 1], 0, vertex)
    }

    /** The face's inside spans along its edge (a in 0..1) at sweep distance [t], widest first dropped if empty. */
    private fun scan(skip: Int, p0: FloatArray, e: FloatArray, s: FloatArray, t: Float): List<Iv> {
        val origin = FloatArray(3) { p0[it] + s[it] * t }
        return lineIntervals(origin, e, skip = skip)
            .mapNotNull { iv ->
                val a = max(iv.lo, 0f)
                val b = min(iv.hi, 1f)
                if (b - a > 1e-5f) Iv(a, b, iv.loSrc, iv.hiSrc) else null
            }
    }

    private fun slab(skip: Int, s: FloatArray, p0: FloatArray, e: FloatArray, t0: Float, t1: Float, depth: Int, vertex: (Float, Float) -> Unit) {
        val h = t1 - t0
        if (h < 1e-5f) return
        val m1 = t0 + 0.25f * h
        val m2 = t0 + 0.75f * h
        val a = scan(skip, p0, e, s, m1)
        val b = scan(skip, p0, e, s, m2)
        val c = scan(skip, p0, e, s, t0 + 0.5f * h)
        var linear = a.size == b.size && a.size == c.size
        if (linear) {
            for (n in a.indices) {
                if (abs(c[n].lo - (a[n].lo + b[n].lo) / 2f) > LINEAR_TOL || abs(c[n].hi - (a[n].hi + b[n].hi) / 2f) > LINEAR_TOL) linear = false
            }
        }
        if (!linear) {
            if (depth < MAX_DEPTH) {
                val mid = t0 + 0.5f * h
                slab(skip, s, p0, e, t0, mid, depth + 1, vertex)
                slab(skip, s, p0, e, mid, t1, depth + 1, vertex)
            }
            return
        }
        fun at(f1: Float, f2: Float, t: Float) = (f1 + (f2 - f1) * (t - m1) / (m2 - m1)).coerceIn(0f, 1f)
        for (n in a.indices) {
            val l0 = at(a[n].lo, b[n].lo, t0)
            val h0 = at(a[n].hi, b[n].hi, t0)
            val l1 = at(a[n].lo, b[n].lo, t1)
            val h1 = at(a[n].hi, b[n].hi, t1)
            val quad = arrayOf(l0 to t0, h0 to t0, h1 to t1, l1 to t1)
            fun v(q: Pair<Float, Float>) = vertex(q.first, q.second)
            if (h0 - l0 > 1e-6f) { v(quad[0]); v(quad[1]); v(quad[2]) }
            if (h1 - l1 > 1e-6f) { v(quad[0]); v(quad[2]); v(quad[3]) }
        }
    }

    // ---- a drawn view as a prism ---------------------------------------------------------------

    /**
     * One drawn outline: [pts3] its vertices (box-local, the closing duplicate dropped), [s] the
     * unit sweep, [a] and [b] an orthonormal basis of the plane the sweep is normal to, [xs] and
     * [ys] the outline in that basis, [cum] the arc length at each vertex round the closed outline.
     */
    private class Prism(
        val view: SurfaceView,
        val pts3: List<FloatArray>,
        val s: FloatArray,
        val a: FloatArray,
        val b: FloatArray,
        val xs: FloatArray,
        val ys: FloatArray,
        val cum: FloatArray,
        val closed: Boolean = true,
    ) {
        /** Its position in the hull's prism list; set once the list is built. */
        var index: Int = 0

        val n: Int get() = pts3.size

        /** The outline's edges: all round a closed outline, one fewer on an open one. */
        val edges: Int get() = if (closed) n else n - 1

        /** Whether [p] is in the prism: closed, or [strict] only strictly inside by [TIE_EPS]. */
        fun contains(p: FloatArray, strict: Boolean): Boolean {
            val x = dot(p, a)
            val y = dot(p, b)
            val inside = pointInPolygon(x, y)
            val near = boundaryDistance(x, y) <= TIE_EPS
            return if (strict) inside && !near else inside || near
        }

        fun pointInPolygon(x: Float, y: Float): Boolean {
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
                j = i
            }
            return inside
        }

        fun boundaryDistance(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in 0 until n) {
                val j = (i + 1) % n
                val wx = xs[j] - xs[i]
                val wy = ys[j] - ys[i]
                val len2 = wx * wx + wy * wy
                val f = if (len2 < 1e-12f) 0f else (((x - xs[i]) * wx + (y - ys[i]) * wy) / len2).coerceIn(0f, 1f)
                val dx = x - (xs[i] + wx * f)
                val dy = y - (ys[i] + wy * f)
                best = min(best, sqrt(dx * dx + dy * dy))
            }
            return best
        }

        /** The parts of the line `p0 + l * d` inside the prism. */
        fun intervals(p0: FloatArray, d: FloatArray, strict: Boolean): List<Iv> {
            val qx = dot(p0, a)
            val qy = dot(p0, b)
            val ex = dot(d, a)
            val ey = dot(d, b)
            val dl = sqrt(dot(d, d))
            val el = sqrt(ex * ex + ey * ey)
            if (el <= 1e-6f * dl) {
                // Parallel to the sweep: all in or all out.
                val inside = if (strict) pointInPolygon(qx, qy) && boundaryDistance(qx, qy) > TIE_EPS else pointInPolygon(qx, qy) || boundaryDistance(qx, qy) <= TIE_EPS
                return if (inside) listOf(Iv(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, SRC_NONE, SRC_NONE)) else emptyList()
            }
            // Crossings of the line with the outline, by the half-open rule so a vertex counts once.
            val nx = -ey
            val ny = ex
            val crossings = ArrayList<Pair<Float, Int>>()
            for (i in 0 until n) {
                val j = (i + 1) % n
                val d0 = nx * (xs[i] - qx) + ny * (ys[i] - qy)
                val d1 = nx * (xs[j] - qx) + ny * (ys[j] - qy)
                if ((d0 > 0f) == (d1 > 0f)) continue
                val f = d0 / (d0 - d1)
                val px = xs[i] + (xs[j] - xs[i]) * f - qx
                val py = ys[i] + (ys[j] - ys[i]) * f - qy
                crossings += ((px * ex + py * ey) / (el * el)) to i
            }
            crossings.sortBy { it.first }
            val out = ArrayList<Iv>()
            var c = 0
            while (c + 1 < crossings.size) {
                val s0 = index * EDGE_STRIDE + crossings[c].second
                val s1 = index * EDGE_STRIDE + crossings[c + 1].second
                out += Iv(crossings[c].first, crossings[c + 1].first, s0, s1)
                c += 2
            }
            return out
        }

        /** Unrolled coordinates of [p] (on the sheet of [edge]): arc length round the outline, and signed sweep distance. */
        fun unrolled(p: FloatArray, edge: Int): Pair<Float, Float> {
            val j = (edge + 1) % n
            val wx = xs[j] - xs[edge]
            val wy = ys[j] - ys[edge]
            val len2 = wx * wx + wy * wy
            val f = if (len2 < 1e-12f) 0f else (((dot(p, a) - xs[edge]) * wx + (dot(p, b) - ys[edge]) * wy) / len2).coerceIn(0f, 1f)
            val u = cum[edge] + (cum[edge + 1] - cum[edge]) * f
            val p0 = pts3[edge]
            val p1 = pts3[j]
            val on = FloatArray(3) { p0[it] + (p1[it] - p0[it]) * f }
            return u to dot(FloatArray(3) { p[it] - on[it] }, s)
        }

        /** Appends the vertex at face coordinates ([af] along the edge, [t] along the sweep) to [out]. */
        fun vertex(edge: Int, p0: FloatArray, e: FloatArray, af: Float, t: Float, out: MutableList<Float>) {
            for (c in 0..2) out += p0[c] + e[c] * af + s[c] * t
            out += cum[edge] + (cum[edge + 1] - cum[edge]) * af
            out += t
        }
    }

    companion object {
        /** A point within this of a prism's boundary is on it, in the hull's box units. */
        const val TIE_EPS = 1e-2f

        /** The longest outline the tessellation takes; a longer one is simplified (see [of]). */
        const val MAX_MESH_POINTS = 64

        private const val EDGE_STRIDE = 65536
        private const val SRC_NONE = Int.MIN_VALUE
        private const val NO_SKIP = -1
        private const val LINEAR_TOL = 1e-3f
        private const val MAX_DEPTH = 8
        private const val MAX_LINK_SAMPLES = 64
        private const val PROBE = 0.05f
        private const val SEAM_MIN = 1f

        /** The gap between a view's sheet and its cap regions in its image, and between caps, as a fraction of the box's widest side. */
        private const val CAP_GAP_RATIO = 0.05f

        /** The src code of a box face: axis [k], low (0) or high (1) side. */
        private fun boxSrc(k: Int, side: Int) = -1 - (k * 2 + side)

        /** Area under this fraction of the outline's squared perimeter is no area. */
        private const val MIN_AREA_RATIO = 1e-5f

        private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        /**
         * The hull of [profile]'s drawn views: its own profile as the front, and its [side] and
         * [top]. Null when none of them has area (a lone straight or open curve, say), so the
         * caller keeps the single-view sheet. A hull from the front alone is the front outline
         * swept both ways through the padded box.
         */
        fun of(profile: SurfaceProfile): SurfaceHull? {
            val drawn = listOfNotNull(
                profile.takeIf { it.isValid }?.let { SurfaceView.FRONT to it },
                profile.side?.takeIf { it.isValid }?.let { SurfaceView.SIDE to it },
                profile.top?.takeIf { it.isValid }?.let { SurfaceView.TOP to it },
            )
            if (drawn.isEmpty()) return null
            val made = drawn.mapNotNull { (v, p) -> prismOf(v, p) }
            val closedOnes = made.filter { it.closed }
            if (closedOnes.isEmpty()) return null
            // At most one open outline is kept, as the wall: the first of front, side, top.
            val wallPrism = made.firstOrNull { !it.closed }
            val prisms = closedOnes + listOfNotNull(wallPrism)
            prisms.forEachIndexed { i, p -> p.index = i }
            val wallIndex = if (wallPrism != null) prisms.lastIndex else -1
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for ((_, p) in drawn) for (i in 0 until p.count) for (c in 0..2) {
                lo[c] = min(lo[c], p.points[i * 3 + c])
                hi[c] = max(hi[c], p.points[i * 3 + c])
            }
            val widest = max(hi[0] - lo[0], max(hi[1] - lo[1], hi[2] - lo[2]))
            if (!(widest > 0f)) return null
            for (c in 0..2) {
                if (hi[c] - lo[c] < widest * 0.01f) {
                    val mid = (hi[c] + lo[c]) / 2f
                    lo[c] = mid - widest / 2f
                    hi[c] = mid + widest / 2f
                }
            }
            return SurfaceHull(prisms, wallIndex, lo, hi)
        }

        private fun prismOf(view: SurfaceView, profile: SurfaceProfile): Prism? {
            val raw = (0 until profile.count).map { profile.point(it) }.toMutableList()
            if (raw.size > 1 && distance(raw.first(), raw.last()) < 1e-3f) raw.removeAt(raw.lastIndex)
            val thin = if (raw.size > MAX_MESH_POINTS) simplifyClosed(raw) else raw
            val sw = SceneMath.normalize(profile.sweep)
            if (sw.any { !it.isFinite() } || dot(sw, sw) < 0.5f) return null
            val ref = if (abs(sw[0]) <= abs(sw[1]) && abs(sw[0]) <= abs(sw[2])) floatArrayOf(1f, 0f, 0f) else if (abs(sw[1]) <= abs(sw[2])) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, 1f)
            val a = SceneMath.normalize(SceneMath.cross(sw, ref))
            val b = SceneMath.cross(sw, a)
            val xs = FloatArray(thin.size) { dot(thin[it], a) }
            val ys = FloatArray(thin.size) { dot(thin[it], b) }
            var area = 0f
            var perimeter = 0f
            val cum = FloatArray(thin.size + 1)
            for (i in thin.indices) {
                val j = (i + 1) % thin.size
                area += xs[i] * ys[j] - xs[j] * ys[i]
                val edge = distance(thin[i], thin[j])
                perimeter += edge
                cum[i + 1] = cum[i] + edge
            }
            if (!(perimeter > 0f)) return null
            if (thin.size < 3 || abs(area) / 2f < MIN_AREA_RATIO * perimeter * perimeter) {
                // No area: an open curve (or a line): a thin wall. Its arc length is along the open curve.
                val open = raw.toList()
                if (open.size < 2) return null
                val ox = FloatArray(open.size) { dot(open[it], a) }
                val oy = FloatArray(open.size) { dot(open[it], b) }
                val oc = FloatArray(open.size + 1)
                for (i in 1 until open.size) oc[i] = oc[i - 1] + distance(open[i - 1], open[i])
                oc[open.size] = oc[open.size - 1]
                if (!(oc[open.size - 1] > 0f)) return null
                return Prism(view, open, sw, a, b, ox, oy, oc, closed = false)
            }
            return Prism(view, thin, sw, a, b, xs, ys, cum)
        }

        /** Keeps at most [MAX_MESH_POINTS] vertices of a closed outline by the open simplifier's growing tolerance. */
        private fun simplifyClosed(points: List<FloatArray>): List<FloatArray> {
            var tolerance = SurfaceMath.SIMPLIFY_TOLERANCE
            var out = SurfaceMath.simplify(points + listOf(points.first()), tolerance).dropLast(1)
            while (out.size > MAX_MESH_POINTS) {
                tolerance *= 1.5f
                out = SurfaceMath.simplify(points + listOf(points.first()), tolerance).dropLast(1)
            }
            return out
        }

        private fun distance(p: FloatArray, q: FloatArray): Float {
            val dx = p[0] - q[0]
            val dy = p[1] - q[1]
            val dz = p[2] - q[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
