@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "ComplexCondition",
    "MaxLineLength",
)

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import kotlin.math.abs

/**
 * A selection on a multi-view surface (spec/3d-space.md, "Paint per view"): the lasso is drawn in
 * world (canvas) space over the picture, but each view's paint image has its own flat space, so a
 * region that crosses a crease is several regions, one per image. Split here, exactly: the lasso
 * ring is clipped to each projected triangle of the view's patches and caps (the same triangles that
 * are drawn), and each piece is carried into the image through the triangle's own mapping, so a point
 * is selected in an image precisely when it is under the lasso on the surface. A face the lasso covers
 * partly shows only the covered part; one it covers whole is whole. Faces facing away are selected
 * too (the lasso selects what is under it, seen or not), which only matters to a stroke that could
 * not reach them.
 */
object SurfaceViewSelection {
    /**
     * [selection] (rings in world space) as a selection in [view]'s flat space: the same rings in the
     * same order and sense, each cut to the view's faces. Null when it reaches none of them.
     */
    fun forView(
        selection: Selection,
        view: SurfaceView,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        camera: SceneCamera,
        width: Float,
        height: Float,
        projection: SurfaceProjection? = null,
    ): Selection? {
        if (width <= 0f || height <= 0f) return null
        val tris = (projection ?: SurfaceProjection.of(hull, owner, camera, width, height)).facing(view)
        if (tris.isEmpty()) return null
        val rings = ArrayList<SelectionRing>()
        var reached = false
        for (ring in selection.rings) {
            if (!ring.isUsable) continue
            for (t in tris) {
                val clipped = clip(ring.path, t.sx, t.sy)
                if (clipped.size < 3) continue
                val flat = clipped.mapNotNull { t.paintAt(it) }
                    .mapNotNull { (px, py) -> SurfaceMath.unrolledToFlat(px, py, owner.innerChain, width, height) }
                    .map { Offset(it.first, it.second) }
                if (flat.size < 3 || area(flat) < MIN_AREA) continue
                rings += SelectionRing(flat, ring.additive)
                if (ring.additive != selection.inverted) reached = true
            }
        }
        if (!reached) return null
        return Selection(rings, selection.canvasSize, selection.inverted, selection.featherPx)
    }

    /**
     * The inverse of [forView]: rings in [view]'s flat space (a wand region traced in its image, with the
     * holes marked) as rings in world space. Each ring is cut to every triangle of the view's patches and
     * caps and carried to the canvas through the triangle's own perspective mapping, so the result is the
     * footprint the region has on screen. Only triangles that face the viewer count, so the far side of the
     * solid is never selected through the near one; the triangle under [tappedAt] (the paint point the wand was
     * tapped at) counts whichever way it faces. Same order and sense as the rings given.
     */
    fun fromView(
        rings: List<SelectionRing>,
        view: SurfaceView,
        owner: SurfaceOwner,
        projection: SurfaceProjection,
        width: Float,
        height: Float,
        tappedAt: Offset? = null,
    ): List<SelectionRing> {
        val tris = projection.triangles(view).filter { it.facing || (tappedAt != null && it.holdsPaint(tappedAt.x, tappedAt.y)) }
        val out = ArrayList<SelectionRing>()
        for (ring in rings) {
            if (!ring.isUsable) continue
            val paint = ring.path.map {
                val (u, t) = SurfaceMath.flatToUnrolled(it.x, it.y, owner.innerChain, width, height)
                Offset(u, t)
            }
            for (t in tris) {
                val clipped = clip(paint, t.px, t.py)
                if (clipped.size < 3) continue
                val world = clipped.mapNotNull { t.screenAt(it.x, it.y) }
                if (world.size < 3 || area(world) < MIN_AREA) continue
                val mid = Offset(world.sumOf { it.x.toDouble() }.toFloat() / world.size, world.sumOf { it.y.toDouble() }.toFloat() / world.size)
                if (t.facing && projection.sharedWithEarlier(t, mid)) continue
                out += SelectionRing(world, ring.additive)
            }
        }
        return out
    }

    /**
     * The rectangle of the face a touch is on ([SurfaceMath.ViewFlat.region]) as a one-ring selection
     * in the view's flat space: what a flood fill from that touch may reach, since the image's other
     * faces share it.
     */
    fun faceRegion(
        flat: SurfaceMath.ViewFlat,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        canvasSize: androidx.compose.ui.unit.IntSize,
        width: Float,
        height: Float,
    ): Selection? {
        val r = hull.regionRect(flat.view, flat.region) ?: return null
        val corners = listOf(r[0] to r[1], r[2] to r[1], r[2] to r[3], r[0] to r[3])
            .map { (x, y) -> SurfaceMath.unrolledToFlat(x, y, owner.innerChain, width, height) ?: return null }
            .map { Offset(it.first, it.second) }
        return Selection.ofPolygon(corners, canvasSize)
    }

    /** Sutherland-Hodgman: [subject] cut to the (convex) triangle [t], in world space. */
    private fun clip(subject: List<Offset>, xs: FloatArray, ys: FloatArray): List<Offset> {
        val ccw = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]) > 0f
        var poly = subject
        for (e in 0..2) {
            val a = e
            val b = (e + 1) % 3
            val ax = xs[a]; val ay = ys[a]; val bx = xs[b]; val by = ys[b]
            fun side(p: Offset): Float {
                val s = (bx - ax) * (p.y - ay) - (by - ay) * (p.x - ax)
                return if (ccw) s else -s
            }
            val next = ArrayList<Offset>()
            for (i in poly.indices) {
                val cur = poly[i]
                val prev = poly[(i + poly.size - 1) % poly.size]
                val sc = side(cur)
                val sp = side(prev)
                if (sc >= 0f) {
                    if (sp < 0f) next += cross(prev, cur, sp, sc)
                    next += cur
                } else if (sp >= 0f) {
                    next += cross(prev, cur, sp, sc)
                }
            }
            poly = next
            if (poly.size < 3) return emptyList()
        }
        return poly
    }

    private fun cross(p: Offset, q: Offset, sp: Float, sq: Float): Offset {
        val f = sp / (sp - sq)
        return Offset(p.x + (q.x - p.x) * f, p.y + (q.y - p.y) * f)
    }

    private fun area(p: List<Offset>): Float {
        var a = 0f
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i].x * q.y - q.x * p[i].y
        }
        return abs(a) / 2f
    }

    private const val MIN_AREA = 1e-3f
}
