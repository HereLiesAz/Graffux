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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A multi-view surface as one camera sees it (spec/3d-space.md, "Selection on a multi-view surface"): every
 * triangle of every view's patches and caps, projected, each with the paint point of its corners, so a
 * point can be carried both ways between the canvas and the view images with the perspective the surface
 * is drawn with. A triangle is [Tri.facing] when it turns its outer side to the viewer; selection and the
 * move of selected pixels only ever work on those, so the far side is never reached through the near one.
 *
 * Pure Kotlin. Built once per camera pose ([of]) and asked many times.
 */
class SurfaceProjection private constructor(private val all: List<Tri>) {
    /** One projected triangle: screen corners, one over camera depth, and paint-image corners. */
    class Tri internal constructor(
        val view: SurfaceView,
        /** 0 for the view's sheet, `1 + face` for a cap ([PaintTarget.region]). */
        val region: Int,
        /** The triangle's outer side is towards the eye (a thin wall has no inside: both sides count). */
        val facing: Boolean,
        internal val sx: FloatArray,
        internal val sy: FloatArray,
        internal val invZ: FloatArray,
        internal val px: FloatArray,
        internal val py: FloatArray,
    ) {
        /** The paint point under the canvas point [p], assuming it is inside (barycentric, divided by depth). */
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

        /**
         * The inverse of [paintAt]: where the paint point ([x], [y]) shows on the canvas, assuming it is in
         * this triangle. The paint barycentrics, each multiplied by its corner's camera depth and renormalised,
         * are the screen barycentrics (perspective-correct interpolation run backwards).
         */
        fun screenAt(x: Float, y: Float): Offset? {
            val d = (py[1] - py[2]) * (px[0] - px[2]) + (px[2] - px[1]) * (py[0] - py[2])
            if (abs(d) < 1e-12f) return null
            val a = ((py[1] - py[2]) * (x - px[2]) + (px[2] - px[1]) * (y - py[2])) / d
            val b = ((py[2] - py[0]) * (x - px[2]) + (px[0] - px[2]) * (y - py[2])) / d
            val c = 1f - a - b
            val wa = a / invZ[0]
            val wb = b / invZ[1]
            val wc = c / invZ[2]
            val sum = wa + wb + wc
            if (abs(sum) < 1e-12f) return null
            return Offset(
                (wa * sx[0] + wb * sx[1] + wc * sx[2]) / sum,
                (wa * sy[0] + wb * sy[1] + wc * sy[2]) / sum,
            )
        }

        /** Whether the paint point ([x], [y]) is in the triangle (to within [eps] of an edge, in barycentric units). */
        fun holdsPaint(x: Float, y: Float, eps: Float = EDGE): Boolean = inside(px, py, x, y, eps)

        /** Whether the canvas point ([x], [y]) is in the triangle (to within [eps] of an edge). */
        fun holdsScreen(x: Float, y: Float, eps: Float = EDGE): Boolean = inside(sx, sy, x, y, eps)

        /** The camera depth of the canvas point ([x], [y]), assuming it is in the triangle. */
        internal fun depthAt(x: Float, y: Float): Float {
            val d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
            if (abs(d) < 1e-12f) return Float.MAX_VALUE
            val a = ((sy[1] - sy[2]) * (x - sx[2]) + (sx[2] - sx[1]) * (y - sy[2])) / d
            val b = ((sy[2] - sy[0]) * (x - sx[2]) + (sx[0] - sx[2]) * (y - sy[2])) / d
            val inv = a * invZ[0] + b * invZ[1] + (1f - a - b) * invZ[2]
            return if (inv > 0f) 1f / inv else Float.MAX_VALUE
        }

        internal fun paintArea(): Float =
            abs((px[1] - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (py[1] - py[0])) / 2f

        internal val minSx: Float get() = min(sx[0], min(sx[1], sx[2]))
        internal val maxSx: Float get() = max(sx[0], max(sx[1], sx[2]))
        internal val minSy: Float get() = min(sy[0], min(sy[1], sy[2]))
        internal val maxSy: Float get() = max(sy[0], max(sy[1], sy[2]))

        private fun inside(xs: FloatArray, ys: FloatArray, x: Float, y: Float, eps: Float): Boolean {
            val d = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if (abs(d) < 1e-12f) return false
            val a = ((ys[1] - ys[2]) * (x - xs[2]) + (xs[2] - xs[1]) * (y - ys[2])) / d
            val b = ((ys[2] - ys[0]) * (x - xs[2]) + (xs[0] - xs[2]) * (y - ys[2])) / d
            return a >= -eps && b >= -eps && 1f - a - b >= -eps
        }
    }

    /** What the canvas shows at a point: the nearest [tri] there and the paint point ([x], [y]) under it. */
    class Hit(val tri: Tri, val x: Float, val y: Float)

    /** The triangles of [view] that face the viewer: what its image shows. */
    fun facing(view: SurfaceView): List<Tri> = facingByView[view].orEmpty()

    /** Every triangle of [view], facing or not. */
    fun triangles(view: SurfaceView): List<Tri> = all.filter { it.view == view }

    /** The views that have a triangle facing the viewer. */
    val facingViews: Set<SurfaceView> get() = facingByView.keys

    private val facingByView: Map<SurfaceView, List<Tri>> by lazy {
        all.filter { it.facing }.groupBy { it.view }
    }

    // A triangle with no area in the image (a sliver the unroll flattens) has no paint to give.
    private val facingAll: List<Tri> by lazy { all.filter { it.facing && it.paintArea() > MIN_PAINT_AREA } }

    private class Grid(
        val minX: Float,
        val minY: Float,
        val cell: Float,
        val cols: Int,
        val rows: Int,
        val cells: Array<IntArray?>,
    )

    private val grid: Grid? by lazy {
        if (facingAll.isEmpty()) return@lazy null
        val minX = facingAll.minOf { it.minSx }
        val minY = facingAll.minOf { it.minSy }
        val maxX = facingAll.maxOf { it.maxSx }
        val maxY = facingAll.maxOf { it.maxSy }
        var cell = CELL
        while (((maxX - minX) / cell + 1) * ((maxY - minY) / cell + 1) > MAX_CELLS) cell *= 2f
        val cols = floor((maxX - minX) / cell).toInt() + 1
        val rows = floor((maxY - minY) / cell).toInt() + 1
        val lists = arrayOfNulls<ArrayList<Int>>(cols * rows)
        facingAll.forEachIndexed { i, t ->
            val c0 = floor((t.minSx - minX) / cell).toInt().coerceIn(0, cols - 1)
            val c1 = floor((t.maxSx - minX) / cell).toInt().coerceIn(0, cols - 1)
            val r0 = floor((t.minSy - minY) / cell).toInt().coerceIn(0, rows - 1)
            val r1 = floor((t.maxSy - minY) / cell).toInt().coerceIn(0, rows - 1)
            for (r in r0..r1) for (c in c0..c1) (lists[r * cols + c] ?: ArrayList<Int>().also { lists[r * cols + c] = it }).add(i)
        }
        Grid(minX, minY, cell, cols, rows, Array(lists.size) { lists[it]?.toIntArray() })
    }

    /**
     * The nearest facing triangle under the canvas point [p] and the paint point there: what the viewer
     * sees at [p] (a face hidden behind another is not found). Null where no surface is.
     */
    fun surfaceAt(p: Offset): Hit? {
        val g = grid ?: return null
        val c = floor((p.x - g.minX) / g.cell).toInt()
        val r = floor((p.y - g.minY) / g.cell).toInt()
        if (c < 0 || r < 0 || c >= g.cols || r >= g.rows) return null
        val candidates = g.cells[r * g.cols + c] ?: return null
        var best: Tri? = null
        var bestZ = Float.MAX_VALUE
        for (i in candidates) {
            val t = facingAll[i]
            if (!t.holdsScreen(p.x, p.y)) continue
            val z = t.depthAt(p.x, p.y)
            // Two views' sheets in one plane are the same surface: the earlier view owns it (as a ray hit says).
            val same = abs(z - bestZ) <= TIE * bestZ
            if (z < bestZ - TIE * bestZ && !same || same && best != null && t.view.ordinal < best.view.ordinal || best == null) {
                best = t; bestZ = z
            }
        }
        val t = best ?: return null
        val paint = t.paintAt(p) ?: return null
        return Hit(t, paint.first, paint.second)
    }

    /**
     * Whether [tri] is, at the canvas point [p], a copy of a surface an earlier view's triangle already
     * carries (two views' sheets lying in one plane): that area belongs to the earlier view's image.
     */
    fun sharedWithEarlier(tri: Tri, p: Offset): Boolean {
        val g = grid ?: return false
        val c = floor((p.x - g.minX) / g.cell).toInt()
        val r = floor((p.y - g.minY) / g.cell).toInt()
        if (c < 0 || r < 0 || c >= g.cols || r >= g.rows) return false
        val z = tri.depthAt(p.x, p.y)
        for (i in g.cells[r * g.cols + c] ?: return false) {
            val o = facingAll[i]
            if (o.view.ordinal >= tri.view.ordinal || !o.holdsScreen(p.x, p.y)) continue
            if (abs(o.depthAt(p.x, p.y) - z) <= TIE * z) return true
        }
        return false
    }

    companion object {
        private const val TIE = 1e-3f
        private const val EDGE = 1e-4f
        private const val MIN_PAINT_AREA = 1e-2f
        private const val CELL = 16f
        private const val MAX_CELLS = 400_000

        /**
         * [hull]'s surface as [camera] sees it on a [width] by [height] canvas, for the leaf [owner]: triangles
         * that are wholly behind the eye, or whose corners have no paint point, are left out.
         */
        fun of(
            hull: SurfaceHull,
            owner: SurfaceOwner,
            camera: SceneCamera,
            width: Float,
            height: Float,
        ): SurfaceProjection {
            if (width <= 0f || height <= 0f) return SurfaceProjection(emptyList())
            val cam = SceneMath.frame(camera, width, height)
            val model = SceneMath.chainModel(owner.ownerChain, width, height)
            val eye = model.inverse()?.apply(cam.eye[0], cam.eye[1], cam.eye[2])
            val out = ArrayList<Tri>()
            fun add(view: SurfaceView, mesh: FloatArray, sheet: Boolean) {
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
                        val paint = if (sheet) hull.paintPointAt(view, mesh[o + 3], mesh[o + 4]) else mesh[o + 3] to mesh[o + 4]
                        if (paint == null) { ok = false; break }
                        sx[k] = screen.first; sy[k] = screen.second; iz[k] = 1f / z
                        px[k] = paint.first; py[k] = paint.second
                    }
                    if (ok) {
                        val region = if (sheet) 0 else hull.capRegionAt(mesh, i)
                        out += Tri(view, region, facesViewer(hull, mesh, i, eye), sx, sy, iz, px, py)
                    }
                    i += 15
                }
            }
            for ((view, mesh) in hull.meshByView()) add(view, mesh, true)
            for ((view, mesh) in hull.capMeshByView()) add(view, mesh, false)
            return SurfaceProjection(out)
        }

        /**
         * Whether the triangle at [i] of [mesh] turns its outer side to [eye] (box space). Outer is the side of
         * the triangle the solid is not on; a thin wall has no inside, so both its sides count as facing.
         */
        private fun facesViewer(hull: SurfaceHull, mesh: FloatArray, i: Int, eye: FloatArray?): Boolean {
            if (eye == null || hull.wall != null) return true
            fun v(k: Int, c: Int) = mesh[i + k * SurfaceHullMesh.STRIDE + c]
            val e1 = FloatArray(3) { v(1, it) - v(0, it) }
            val e2 = FloatArray(3) { v(2, it) - v(0, it) }
            val n = SceneMath.cross(e1, e2)
            val len = sqrt(SceneMath.dot(n, n))
            if (len < 1e-9f) return true
            val c = FloatArray(3) { (v(0, it) + v(1, it) + v(2, it)) / 3f }
            val unit = FloatArray(3) { n[it] / len }
            // Step off the face by more than the hull's tie tolerance: still inside the solid means the normal points in.
            val step = SurfaceHull.TIE_EPS * 10f
            val inward = hull.contains(c[0] + unit[0] * step, c[1] + unit[1] * step, c[2] + unit[2] * step)
            val outer = if (inward) FloatArray(3) { -unit[it] } else unit
            return SceneMath.dot(outer, FloatArray(3) { eye[it] - c[it] }) > 0f
        }
    }
}
