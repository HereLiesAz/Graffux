@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * The wire mesh of a multi-view surface (spec/3d-space.md, "Guide grid"). The front profile's swept
 * sheet is only one face of a hull; the mesh of a group that has a side or top drawing is drawn on the
 * hull's own faces (every view's sheet and the caps), so it shows the solid the views carve out.
 *
 * Lines are the iso-lines of each face's paint coordinates `(u, t)` at multiples of the spacing (arc
 * length round the carrying outline, sweep distance), the same lattice a single profile's grid uses,
 * plus the faces' open edges (creases and the box's rim), so the mesh closes where the surface ends.
 */
object SurfaceHullGrid {
    private const val STRIDE = 5
    private const val EPS = 1e-4f

    private data class VKey(val x: Long, val y: Long, val z: Long) {
        fun order(): Triple<Long, Long, Long> = Triple(x, y, z)
    }

    private operator fun Triple<Long, Long, Long>.compareTo(o: Triple<Long, Long, Long>): Int =
        compareValuesBy(this, o, { it.first }, { it.second }, { it.third })

    /** Box-local segments `x0, y0, z0, x1, y1, z1` of [hull]'s wire mesh at [spacing] units. */
    fun segments(hull: SurfaceHull, spacing: Float = SurfaceMath.GRID_SPACING): List<FloatArray> {
        if (spacing <= 0f) return emptyList()
        val out = ArrayList<FloatArray>()
        val patches = ArrayList<FloatArray>()
        patches += hull.meshByView().values
        patches += hull.capMeshByView().values
        for (mesh in patches) {
            var i = 0
            while (i + 3 * STRIDE <= mesh.size) {
                isoLines(mesh, i, 3, spacing, out)
                isoLines(mesh, i, 4, spacing, out)
                i += 3 * STRIDE
            }
            openEdges(mesh, out)
        }
        return distinct(out)
    }

    /** Drops segments drawn twice: a crease is an open edge of both faces that meet there. */
    private fun distinct(all: List<FloatArray>): List<FloatArray> {
        fun q(v: Float) = round(v * 10f).toLong()
        fun end(s: FloatArray, o: Int) = Triple(q(s[o]), q(s[o + 1]), q(s[o + 2]))
        val seen = HashSet<Pair<Triple<Long, Long, Long>, Triple<Long, Long, Long>>>()
        return all.filter { s ->
            val a = end(s, 0)
            val b = end(s, 3)
            seen.add(if (a <= b) a to b else b to a)
        }
    }

    /** The triangle at [base]'s crossings of the lines `component = k * spacing`, as segments in box space. */
    private fun isoLines(m: FloatArray, base: Int, component: Int, spacing: Float, out: MutableList<FloatArray>) {
        val v = FloatArray(3) { m[base + it * STRIDE + component] }
        val lo = minOf(v[0], v[1], v[2])
        val hi = maxOf(v[0], v[1], v[2])
        if (hi - lo < EPS) return
        var k = ceil(lo / spacing).toInt()
        val last = floor(hi / spacing).toInt()
        var n = 0
        while (k <= last && n < SurfaceMath.GRID_MAX_LINES) {
            val level = k * spacing
            val pts = ArrayList<FloatArray>(2)
            for (e in 0 until 3) {
                val a = e
                val b = (e + 1) % 3
                val da = v[a] - level
                val db = v[b] - level
                if (da * db < 0f) {
                    val f = da / (da - db)
                    pts += FloatArray(3) { c ->
                        val pa = m[base + a * STRIDE + c]
                        pa + (m[base + b * STRIDE + c] - pa) * f
                    }
                }
            }
            if (pts.size == 2) out += pts[0] + pts[1]
            k++
            n++
        }
    }

    /** Edges only one triangle of the patch uses: where the face ends. */
    private fun openEdges(m: FloatArray, out: MutableList<FloatArray>) {
        val uses = HashMap<Pair<VKey, VKey>, IntArray>()
        val ends = HashMap<Pair<VKey, VKey>, FloatArray>()
        fun q(v: Float) = round(v * 100f).toLong()
        fun key(i: Int) = VKey(q(m[i]), q(m[i + 1]), q(m[i + 2]))
        var t = 0
        while (t + 3 * STRIDE <= m.size) {
            for (e in 0 until 3) {
                val a = t + e * STRIDE
                val b = t + ((e + 1) % 3) * STRIDE
                if (abs(m[a] - m[b]) + abs(m[a + 1] - m[b + 1]) + abs(m[a + 2] - m[b + 2]) < EPS) continue
                val ka = key(a)
                val kb = key(b)
                val k = if (ka.order() <= kb.order()) ka to kb else kb to ka
                uses.getOrPut(k) { IntArray(1) }[0]++
                ends[k] = floatArrayOf(m[a], m[a + 1], m[a + 2], m[b], m[b + 1], m[b + 2])
            }
            t += 3 * STRIDE
        }
        for ((k, n) in uses) if (n[0] == 1) ends[k]?.let(out::add)
    }
}
