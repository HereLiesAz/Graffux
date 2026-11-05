@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
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
    ): Selection? {
        if (width <= 0f || height <= 0f) return null
        val tris = triangles(view, owner, hull, camera, width, height)
        if (tris.isEmpty()) return null
        val rings = ArrayList<SelectionRing>()
        var reached = false
        for (ring in selection.rings) {
            if (!ring.isUsable) continue
            for (t in tris) {
                val clipped = clip(ring.path, t)
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

    /** A projected triangle with the paint coordinates of its corners, for perspective-correct mapping. */
    private class Tri(
        val sx: FloatArray,
        val sy: FloatArray,
        val invZ: FloatArray,
        val px: FloatArray,
        val py: FloatArray,
    ) {
        /** The paint point under the world point [p] inside this triangle (barycentric, divided by depth). */
        fun paintAt(p: Offset): Pair<Float, Float>? {
            val d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
            if (abs(d) < 1e-12f) return null
            val a = ((sy[1] - sy[2]) * (p.x - sx[2]) + (sx[2] - sx[1]) * (p.y - sy[2])) / d
            val b = ((sy[2] - sy[0]) * (p.x - sx[2]) + (sx[0] - sx[2]) * (p.y - sy[2])) / d
            val c = 1f - a - b
            val wa = a * invZ[0]
            val wb = b * invZ[1]
            val wc = c * invZ[2]
            val sum = wa + wb + wc
            if (abs(sum) < 1e-12f) return null
            return (wa * px[0] + wb * px[1] + wc * px[2]) / sum to (wa * py[0] + wb * py[1] + wc * py[2]) / sum
        }
    }

    private fun triangles(
        view: SurfaceView,
        owner: SurfaceOwner,
        hull: SurfaceHull,
        camera: SceneCamera,
        width: Float,
        height: Float,
    ): List<Tri> {
        val cam = SceneMath.frame(camera, width, height)
        val model = SceneMath.chainModel(owner.ownerChain, width, height)
        val out = ArrayList<Tri>()
        fun add(mesh: FloatArray, sheet: Boolean) {
            var i = 0
            while (i + 15 <= mesh.size) {
                val sx = FloatArray(3)
                val sy = FloatArray(3)
                val iz = FloatArray(3)
                val px = FloatArray(3)
                val py = FloatArray(3)
                var ok = true
                for (k in 0..2) {
                    val o = i + k * SurfaceHullMesh.STRIDE
                    val p = model.apply(mesh[o], mesh[o + 1], mesh[o + 2])
                    val screen = cam.project(p)
                    val z = cam.toCamera(p)[2]
                    if (screen == null || !(z > 0f)) { ok = false; break }
                    val paint = if (sheet) {
                        hull.paintPointAt(view, mesh[o + 3], mesh[o + 4])
                    } else {
                        mesh[o + 3] to mesh[o + 4]
                    }
                    if (paint == null) { ok = false; break }
                    sx[k] = screen.first; sy[k] = screen.second; iz[k] = 1f / z
                    px[k] = paint.first; py[k] = paint.second
                }
                if (ok) out += Tri(sx, sy, iz, px, py)
                i += 15
            }
        }
        hull.meshByView()[view]?.let { add(it, true) }
        hull.capMeshByView()[view]?.let { add(it, false) }
        return out
    }

    /** Sutherland-Hodgman: [subject] cut to the (convex) triangle [t], in world space. */
    private fun clip(subject: List<Offset>, t: Tri): List<Offset> {
        val ccw = (t.sx[1] - t.sx[0]) * (t.sy[2] - t.sy[0]) - (t.sx[2] - t.sx[0]) * (t.sy[1] - t.sy[0]) > 0f
        var poly = subject
        for (e in 0..2) {
            val a = e
            val b = (e + 1) % 3
            val ax = t.sx[a]; val ay = t.sy[a]; val bx = t.sx[b]; val by = t.sy[b]
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
