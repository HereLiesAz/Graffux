@file:Suppress("MaxLineLength", "MagicNumber", "LongParameterList", "NestedBlockDepth", "DestructuringDeclarationWithTooManyEntries", "ReturnCount")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private typealias P = Pair<Float, Float>

/**
 * A safety net over a library of exact, already-clean outlines (thin and fat bars, V/W, trapezoids, arcs,
 * regular polygons, circles, ellipses, L/T/U), each sampled densely at several rotations, translations,
 * windings and start points: cleaning must not change them (area, closure, corners, bounding box), must be
 * idempotent, and must stay a valid outline. Noisy copies must stay within the documented tolerances.
 */
class OutlineAssistPropertyTest {
    private class Shape(val name: String, val closed: Boolean, val corners: Int, val pts: List<P>, val verts: List<P>? = null)

    private fun poly(v: List<P>, closed: Boolean, per: Int = 25): List<P> {
        val out = ArrayList<P>()
        val edges = if (closed) v.size else v.size - 1
        for (e in 0 until edges) {
            val a = v[e]
            val b = v[(e + 1) % v.size]
            for (k in 0 until per) {
                val f = k / per.toFloat()
                out += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
        }
        return if (closed) out + out.first() else out + v.last()
    }

    private fun ngon(n: Int) = (0 until n).map { (300f + 150f * cos(0.4 + 2 * PI * it / n).toFloat()) to (300f + 150f * sin(0.4 + 2 * PI * it / n).toFloat()) }

    private fun ellipse(a: Float, b: Float, n: Int = 160): List<P> =
        (0..n).map { (300f + a * cos(2 * PI * it / n).toFloat()) to (300f + b * sin(2 * PI * it / n).toFloat()) }

    private fun polyShape(name: String, closed: Boolean, corners: Int, v: List<P>) = Shape(name, closed, corners, poly(v, closed), v)

    /** Whether rotating [v] by [rot] leaves a side a few degrees off horizontal or vertical, where cleanup is meant to snap it. */
    private fun nearAxis(v: List<P>, closed: Boolean, rot: Double): Boolean {
        val edges = if (closed) v.size else v.size - 1
        for (e in 0 until edges) {
            val a = v[e]
            val b = v[(e + 1) % v.size]
            val ang = Math.toDegrees(atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble()) + rot)
            var m = ((ang % 90) + 90) % 90
            m = min(m, 90 - m)
            if (m > 0.01 && m < 9.0) return true
        }
        return false
    }

    private fun library(): List<Shape> {
        val l = ArrayList<Shape>()
        for (aspect in listOf(1f, 1.5f, 2f, 5f, 10f, 20f, 40f)) {
            val w = 400f
            val h = w / aspect
            l += polyShape("rect 1:$aspect", true, 4, listOf(100f to 100f, 100f + w to 100f, 100f + w to 100f + h, 100f to 100f + h))
        }
        l += polyShape("V", false, 1, listOf(100f to 300f, 200f to 100f, 300f to 300f))
        l += polyShape("V asym", false, 1, listOf(100f to 300f, 240f to 100f, 300f to 300f))
        l += polyShape("W", false, 3, listOf(100f to 100f, 160f to 300f, 220f to 150f, 280f to 300f, 340f to 100f))
        l += polyShape("trapezoid", true, 4, listOf(150f to 100f, 350f to 100f, 450f to 300f, 50f to 300f))
        l += polyShape("right trapezoid", true, 4, listOf(100f to 100f, 300f to 100f, 400f to 300f, 100f to 300f))
        l += Shape("arc", false, 0, (0..100).map { (300f + 200f * cos(PI * it / 100)).toFloat() to (300f - 150f * sin(PI * it / 100)).toFloat() })
        for (n in 3..8) l += polyShape("$n-gon", true, n, ngon(n))
        l += Shape("circle", true, 0, ellipse(150f, 150f))
        for (a in listOf(1.5f, 3f, 10f)) l += Shape("ellipse 1:$a", true, 0, ellipse(150f * a, 150f))
        l += polyShape("L", false, 1, listOf(100f to 100f, 100f to 300f, 300f to 300f))
        l += polyShape("U", false, 2, listOf(100f to 100f, 100f to 300f, 300f to 300f, 300f to 100f))
        l += polyShape("T", true, 8, listOf(100f to 100f, 400f to 100f, 400f to 160f, 280f to 160f, 280f to 400f, 220f to 400f, 220f to 160f, 100f to 160f))
        return l
    }

    private fun transform(s: Shape, rot: Double, dx: Float, dy: Float, flip: Boolean, shift: Int): List<P> {
        var pts = s.pts
        if (s.closed) {
            val ring = pts.dropLast(1)
            val sh = shift % ring.size
            val r = ring.drop(sh) + ring.take(sh)
            pts = r + r.first()
        }
        if (flip) pts = pts.reversed()
        val c = cos(rot)
        val sn = sin(rot)
        return pts.map { (x, y) ->
            val ox = x - 300f
            val oy = y - 300f
            (300f + dx + (ox * c - oy * sn).toFloat()) to (300f + dy + (ox * sn + oy * c).toFloat())
        }
    }

    private fun area(p: List<P>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i].first.toDouble() * q.second - q.first.toDouble() * p[i].second
        }
        return abs(a) / 2
    }

    private fun diag(p: List<P>) = hypot((p.maxOf { it.first } - p.minOf { it.first }).toDouble(), (p.maxOf { it.second } - p.minOf { it.second }).toDouble())

    private fun cornerCount(p: List<P>, closed: Boolean): Int {
        val n = if (closed) p.size - 1 else p.size
        var c = 0
        val range = if (closed) 0 until n else 1 until n - 1
        for (i in range) {
            val a = p[(i - 1 + n) % n]
            val b = p[i]
            val d = p[(i + 1) % n]
            val a1 = atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble())
            val a2 = atan2((d.second - b.second).toDouble(), (d.first - b.first).toDouble())
            var t = abs(a2 - a1)
            if (t > PI) t = 2 * PI - t
            if (Math.toDegrees(t) >= 30.0) c++
        }
        return c
    }

    private fun segDist(q: P, a: P, b: P): Double {
        val dx = (b.first - a.first).toDouble()
        val dy = (b.second - a.second).toDouble()
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-12) 0.0 else (((q.first - a.first) * dx + (q.second - a.second) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(q.first - (a.first + dx * t), q.second - (a.second + dy * t))
    }

    private fun toPolyline(q: P, p: List<P>): Double = (0 until p.size - 1).minOf { segDist(q, p[it], p[it + 1]) }

    /** Ramer-Douglas-Peucker, keeping the ends (a closed outline keeps its repeated closing point). */
    private fun simplify(p: List<P>, tol: Double): List<P> {
        if (p.size < 3) return p
        var worst = -1
        var wd = tol
        for (i in 1 until p.size - 1) {
            val dd = segDist(p[i], p.first(), p.last())
            if (dd > wd) { wd = dd; worst = i }
        }
        if (worst < 0) return listOf(p.first(), p.last())
        return simplify(p.subList(0, worst + 1), tol).dropLast(1) + simplify(p.subList(worst, p.size), tol)
    }

    private fun hausdorff(a: List<P>, b: List<P>) = max(a.maxOf { toPolyline(it, b) }, b.maxOf { toPolyline(it, a) })

    private fun check(name: String, shape: Shape, input: List<P>, out: List<P>, areaTol: Double, boxTol: Double, noisy: Boolean = false) {
        val d = diag(input)
        assertTrue("$name: invalid ${out.size}", OutlineAssist.isValidOutline(out))
        assertTrue("$name: SurfaceProfile invalid", SurfaceProfile(out.flatMap { listOf(it.first, it.second, 0f) }).isValid)
        if (shape.closed) {
            assertTrue("$name: no longer closed", SurfacePayload.isClosedOutline(out))
            assertTrue("$name: does not end on its start", hypot((out.first().first - out.last().first).toDouble(), (out.first().second - out.last().second).toDouble()) < 1e-3)
            assertEquals("$name: area", 1.0, area(out.dropLast(1)) / area(input.dropLast(1)), areaTol)
        } else {
            assertTrue("$name: ends moved", hypot((out.first().first - input.first().first).toDouble(), (out.first().second - input.first().second).toDouble()) <= 0.03 * d)
        }
        val judged = if (noisy) simplify(out, 0.01 * d) else out
        if (!(noisy && shape.corners == 0)) assertEquals("$name: corners ${if (shape.name == "T") judged.joinToString { "(%.0f,%.0f)".format(it.first, it.second) } else ""}", shape.corners, cornerCount(judged, shape.closed))
        for ((lo, hi, olo, ohi) in listOf(
            listOf(input.minOf { it.first }, input.maxOf { it.first }, out.minOf { it.first }, out.maxOf { it.first }),
            listOf(input.minOf { it.second }, input.maxOf { it.second }, out.minOf { it.second }, out.maxOf { it.second }),
        )) {
            assertTrue("$name: bbox ${lo}..$hi vs $olo..$ohi", abs(lo - olo) <= boxTol * d && abs(hi - ohi) <= boxTol * d)
        }
    }

    @Test fun `exact clean outlines are preserved, valid and idempotent`() {
        val rots = listOf(0.0, Math.toRadians(25.0), Math.toRadians(90.0), Math.toRadians(143.0))
        val moves = listOf(0f to 0f, 250f to -130f)
        val failures = ArrayList<String>()
        for (shape in library()) for (rot in rots) for ((dx, dy) in moves) for (flip in listOf(false, true)) {
            if (shape.verts != null && nearAxis(shape.verts, shape.closed, rot)) continue
            val input = transform(shape, rot, dx, dy, flip, shift = 37)
            val name = "${shape.name} rot=${Math.toDegrees(rot).toInt()} d=($dx,$dy) flip=$flip"
            collect(failures) {
                val out = OutlineAssist.assist(input)
                check(name, shape, input, out, 0.02, 0.01)
                val twice = OutlineAssist.assist(out)
                val h = hausdorff(out, twice)
                assertTrue("$name: not idempotent ($h)", h <= 0.003 * diag(input))
            }
        }
        report(failures)
    }

    private fun collect(into: MutableList<String>, body: () -> Unit) {
        try { body() } catch (e: AssertionError) { into += e.message.orEmpty() }
    }

    private fun report(failures: List<String>) {
        assertTrue("${failures.size} failures:\n" + failures.take(60).joinToString("\n"), failures.isEmpty())
    }

    @Test fun `noisy outlines stay within tolerance`() {
        val rots = listOf(0.0, Math.toRadians(25.0), Math.toRadians(143.0))
        val failures = ArrayList<String>()
        val unchanged = ArrayList<String>()
        var total = 0
        for (shape in library()) for (rot in rots) for (seed in 1L..3L) {
            if (shape.verts != null && nearAxis(shape.verts, shape.closed, rot)) continue
            val input = transform(shape, rot, 40f, -20f, seed % 2L == 0L, shift = 11)
            // The documented tolerance: jitter up to 0.7% of the outline's scale (its size, or 3x its thickness for a bar)
            // and 1.5% of its shortest side.
            val thick = min(diag(input), 3.0 * thickness(input))
            val shortest = shape.verts?.let { v ->
                val edges = if (shape.closed) v.size else v.size - 1
                (0 until edges).minOf { hypot((v[(it + 1) % v.size].first - v[it].first).toDouble(), (v[(it + 1) % v.size].second - v[it].second).toDouble()) }
            } ?: Double.MAX_VALUE
            val sigma = min(0.007 * thick, 0.015 * shortest)
            val r = Random(seed * 31)
            val noisy = input.map { (it.first + (r.nextGaussian() * sigma).toFloat()) to (it.second + (r.nextGaussian() * sigma).toFloat()) }
            val name = "noisy ${shape.name} rot=${Math.toDegrees(rot).toInt()} seed=$seed"
            collect(failures) {
                val out = OutlineAssist.assist(noisy)
                total++
                if (out === noisy) unchanged += name
                check(name, shape, noisy, out, 0.10, 0.03, noisy = true)
                val reference = transform(shape, rot, 40f, -20f, seed % 2L == 0L, shift = 11)
                assertTrue("$name: strays ${hausdorff(out, reference)} from the clean outline", hausdorff(out, reference) <= 0.02 * diag(reference))
            }
        }
        report(failures)
        // The fall-back ("cannot clean it, keep it as drawn") must be the exception, not the rule.
        assertTrue("${unchanged.size} of $total left unchanged: $unchanged", unchanged.size * 20 <= total)
    }

    private fun thickness(p: List<P>): Double {
        val mx = p.sumOf { it.first.toDouble() } / p.size
        val my = p.sumOf { it.second.toDouble() } / p.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in p) { val x = q.first - mx; val y = q.second - my; sxx += x * x; syy += y * y; sxy += x * y }
        val a = 0.5 * atan2(2 * sxy, sxx - syy)
        var u0 = 1e9; var u1 = -1e9; var v0 = 1e9; var v1 = -1e9
        for (q in p) {
            val x = q.first - mx; val y = q.second - my
            val u = x * cos(a) + y * sin(a); val v = -x * sin(a) + y * cos(a)
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
        }
        return min(u1 - u0, v1 - v0)
    }
}
