@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The wire mesh of a multi-view surface (spec/3d-space.md, "Guide grid"). The front profile's swept
 * sheet is only one face of a hull, so a group with a side or top drawing is meshed on the hull's own
 * faces (every view's sheet and the caps), which is the outer skin of the one solid the views define.
 *
 * Lines are the iso-lines of each face's paint coordinates `(u, t)` at multiples of the spacing (arc
 * length round the carrying outline, sweep distance), the same lattice a single profile's grid uses,
 * plus the faces' outline: edges only one triangle uses (where the surface ends) and creases (edges
 * whose triangles lie in different planes, whether the faces belong to one view's mesh or to two).
 */
object SurfaceHullGrid {
    private const val STRIDE = 5
    private const val EPS = 1e-4f
    private const val SAME_PLANE = 0.9999f
    private const val QUANT = 10f

    /** Box-local segments `x0, y0, z0, x1, y1, z1` of [hull]'s wire mesh at [spacing] units. */
    fun segments(hull: SurfaceHull, spacing: Float = SurfaceMath.GRID_SPACING): List<FloatArray> =
        fromMeshes(hull.meshByView().values + hull.capMeshByView().values, spacing)

    /**
     * The wire mesh of triangle lists (`x, y, z, u, t` per corner, three corners per triangle), one list
     * per patch of the surface. Exposed for tests; [segments] is the hull's.
     */
    internal fun fromMeshes(patches: Collection<FloatArray>, spacing: Float): List<FloatArray> {
        if (spacing <= 0f) return emptyList()
        val out = ArrayList<FloatArray>()
        for (mesh in patches) {
            var i = 0
            while (i + 3 * STRIDE <= mesh.size) {
                isoLines(mesh, i, 3, spacing, out)
                isoLines(mesh, i, 4, spacing, out)
                i += 3 * STRIDE
            }
            outline(mesh, out)
        }
        return distinct(out)
    }

    private data class Q(val x: Long, val y: Long, val z: Long) : Comparable<Q> {
        override fun compareTo(other: Q): Int = compareValuesBy(this, other, { it.x }, { it.y }, { it.z })
    }

    private fun q(v: Float) = round(v * QUANT).toLong()
    private fun key(p: FloatArray, o: Int = 0) = Q(q(p[o]), q(p[o + 1]), q(p[o + 2]))

    /** Drops segments drawn twice: a crease, or a grid line along a tessellation edge, is met by both sides. */
    private fun distinct(all: List<FloatArray>): List<FloatArray> {
        val seen = HashSet<Pair<Q, Q>>()
        return all.filter { s ->
            val a = key(s, 0)
            val b = key(s, 3)
            a != b && seen.add(if (a <= b) a to b else b to a)
        }
    }

    /**
     * The triangle at [base]'s part of each line `component = k * spacing`. A corner on the level counts
     * (so a line along a tessellation edge or through a vertex is kept), a crossing between corners on
     * either side is interpolated, and the touching points are deduplicated into a segment.
     */
    private fun isoLines(m: FloatArray, base: Int, component: Int, spacing: Float, out: MutableList<FloatArray>) {
        val v = FloatArray(3) { m[base + it * STRIDE + component] }
        val lo = minOf(v[0], v[1], v[2])
        val hi = maxOf(v[0], v[1], v[2])
        if (hi - lo < EPS) return
        var k = ceil((lo - EPS) / spacing).toInt()
        val last = floor((hi + EPS) / spacing).toInt()
        var n = 0
        while (k <= last && n < SurfaceMath.GRID_MAX_LINES) {
            val level = k * spacing
            val pts = ArrayList<FloatArray>(3)
            fun add(p: FloatArray) {
                if (pts.none { key(it) == key(p) }) pts += p
            }
            fun corner(c: Int) = FloatArray(3) { m[base + c * STRIDE + it] }
            for (c in 0 until 3) if (abs(v[c] - level) <= EPS) add(corner(c))
            for (a in 0 until 3) {
                val b = (a + 1) % 3
                val da = v[a] - level
                val db = v[b] - level
                if (abs(da) > EPS && abs(db) > EPS && da * db < 0f) {
                    val f = da / (da - db)
                    val pa = corner(a)
                    val pb = corner(b)
                    add(FloatArray(3) { pa[it] + (pb[it] - pa[it]) * f })
                }
            }
            if (pts.size == 2) out += pts[0] + pts[1]
            k++
            n++
        }
    }

    private class EdgeUse(val ends: FloatArray, val normals: MutableList<FloatArray> = ArrayList())

    /** Edges only one triangle uses (where a face ends) and edges between triangles in different planes (creases). */
    private fun outline(m: FloatArray, out: MutableList<FloatArray>) {
        val edges = HashMap<Pair<Q, Q>, EdgeUse>()
        var t = 0
        while (t + 3 * STRIDE <= m.size) {
            val n = normal(m, t)
            for (e in 0 until 3) {
                val a = t + e * STRIDE
                val b = t + ((e + 1) % 3) * STRIDE
                val ka = key(m, a)
                val kb = key(m, b)
                if (ka == kb) continue
                val id = if (ka <= kb) ka to kb else kb to ka
                val use = edges.getOrPut(id) {
                    EdgeUse(floatArrayOf(m[a], m[a + 1], m[a + 2], m[b], m[b + 1], m[b + 2]))
                }
                if (n != null) use.normals += n
            }
            t += 3 * STRIDE
        }
        for (use in edges.values) {
            val first = use.normals.firstOrNull()
            val crease = first != null && use.normals.any { abs(dot(first, it)) < SAME_PLANE }
            if (use.normals.size <= 1 || crease) out += use.ends
        }
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** The unit normal of the triangle at [t]; null for a degenerate one. */
    private fun normal(m: FloatArray, t: Int): FloatArray? {
        val ux = m[t + STRIDE] - m[t]
        val uy = m[t + STRIDE + 1] - m[t + 1]
        val uz = m[t + STRIDE + 2] - m[t + 2]
        val vx = m[t + 2 * STRIDE] - m[t]
        val vy = m[t + 2 * STRIDE + 1] - m[t + 1]
        val vz = m[t + 2 * STRIDE + 2] - m[t + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return if (len < 1e-9f) null else floatArrayOf(nx / len, ny / len, nz / len)
    }
}
