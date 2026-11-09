@file:Suppress("MaxLineLength", "MagicNumber", "LongParameterList")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Surface-outline assist: shapes cleaned on lift (spec/3d-space.md, "Outline assist"). */
private typealias Pts = List<Pair<Float, Float>>

class OutlineAssistTest {
    

    private fun noisy(p: Pts, sigma: Float, seed: Long): Pts {
        val r = Random(seed)
        return p.map { (it.first + r.nextGaussian().toFloat() * sigma) to (it.second + r.nextGaussian().toFloat() * sigma) }
    }

    /** The closed polygon through [v], [per] samples per edge, starting [startFrac] of the way round the first edge, ending where it started. */
    private fun closedPoly(v: List<Pair<Float, Float>>, per: Int = 25, startFrac: Float = 0.4f): Pts {
        val out = ArrayList<Pair<Float, Float>>()
        for (e in v.indices) {
            val a = v[e]
            val b = v[(e + 1) % v.size]
            for (k in 0 until per) {
                val f = k / per.toFloat()
                out += (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
            }
        }
        val shift = (per * startFrac).toInt()
        val rot = out.drop(shift) + out.take(shift)
        return rot + rot.first()
    }

    private fun openPoly(v: List<Pair<Float, Float>>, per: Int = 25): Pts {
        val out = ArrayList<Pair<Float, Float>>()
        for (e in 0 until v.size - 1) for (k in 0 until per) {
            val f = k / per.toFloat()
            out += (v[e].first + (v[e + 1].first - v[e].first) * f) to (v[e].second + (v[e + 1].second - v[e].second) * f)
        }
        return out + v.last()
    }

    private fun ellipse(cx: Float, cy: Float, a: Float, b: Float, n: Int = 120, tilt: Double = 0.0): Pts =
        (0..n).map {
            val t = 2 * PI * it / n
            val u = a * cos(t)
            val v = b * sin(t)
            (cx + u * cos(tilt) - v * sin(tilt)).toFloat() to (cy + u * sin(tilt) + v * cos(tilt)).toFloat()
        }

    /** The corners of a closed result: all but the repeated last point. */
    private fun ring(p: Pts) = p.dropLast(1)
    /** The corners of a closed result: ring points where the outline really turns (a start vertex on a side is not one). */
    private fun cornersOf(p: Pts): Pts {
        val r = ring(p)
        return r.indices.filter { i -> angleAt(r[i], r[(i + r.size - 1) % r.size], r[(i + 1) % r.size]) < 175.0 }.map { r[it] }
    }

    private fun signedArea(p: Pts): Double {
        var a = 0.0
        for (i in 0 until p.size - 1) a += p[i].first.toDouble() * p[i + 1].second - p[i + 1].first.toDouble() * p[i].second
        return a + p.last().first.toDouble() * p[0].second - p[0].first.toDouble() * p.last().second
    }

    private fun regular(n: Int, radius: Float = 120f, phase: Double = 0.2): List<Pair<Float, Float>> =
        (0 until n).map { (300f + radius * cos(phase + 2 * PI * it / n).toFloat()) to (300f + radius * sin(phase + 2 * PI * it / n).toFloat()) }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())

    private fun angleAt(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Double {
        val a1 = Math.atan2((a.second - p.second).toDouble(), (a.first - p.first).toDouble())
        val a2 = Math.atan2((b.second - p.second).toDouble(), (b.first - p.first).toDouble())
        return Math.toDegrees(abs(a1 - a2)).let { if (it > 180) 360 - it else it }
    }

    private fun assertClean(out: Pts, closed: Boolean) {
        assertTrue(OutlineAssist.isValidOutline(out))
        if (closed) assertTrue(SurfacePayload.isClosedOutline(out))
    }

    /** Every point has its mirror image about x = [cx] among the points (to [eps]). */
    private fun assertMirror(p: Pts, cx: Float, eps: Float = 1e-3f) {
        for (q in p) assertTrue("no mirror of $q", p.any { abs(it.first - (2 * cx - q.first)) < eps && abs(it.second - q.second) < eps })
    }

    private fun distToPolyline(q: Pair<Float, Float>, p: Pts): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until p.size - 1) {
            val ax = p[i].first.toDouble(); val ay = p[i].second.toDouble()
            val dx = p[i + 1].first - ax; val dy = p[i + 1].second - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-12) 0.0 else (((q.first - ax) * dx + (q.second - ay) * dy) / l2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(q.first - (ax + dx * t), q.second - (ay + dy * t)))
        }
        return best
    }

    /** The two outlines are the same curve to within [tol] (Hausdorff), whatever their vertices or start. */
    private fun assertSameShape(a: Pts, b: Pts, tol: Double) {
        for (q in a) assertTrue("$q is ${distToPolyline(q, b)} from the second", distToPolyline(q, b) <= tol)
        for (q in b) assertTrue("$q is ${distToPolyline(q, a)} from the first", distToPolyline(q, a) <= tol)
    }

    private val square = listOf(100f to 100f, 300f to 100f, 300f to 300f, 100f to 300f)

    @Test fun `noisy square becomes an exact axis aligned square`() {
        for (seed in 1L..20L) {
            val out = OutlineAssist.assist(noisy(closedPoly(square), 2f, seed))
            assertClean(out, true)
            val r = cornersOf(out)
            assertEquals("seed $seed", 4, r.size)
            val sides = (0 until 4).map { dist(r[it], r[(it + 1) % 4]) }
            sides.forEach { assertEquals(sides[0], it, 1e-3) }
            for (i in 0 until 4) assertEquals(90.0, angleAt(r[i], r[(i + 3) % 4], r[(i + 1) % 4]), 1e-3)
            for (i in 0 until 4) {
                val n = r[(i + 1) % 4]
                assertTrue(abs(r[i].first - n.first) < 1e-3 || abs(r[i].second - n.second) < 1e-3)
            }
        }
    }

    @Test fun `tilted noisy square stays a square at its own angle`() {
        val a = Math.toRadians(25.0)
        val tilted = square.map { (x, y) -> (200 + (x - 200) * cos(a) - (y - 200) * sin(a)).toFloat() to (200 + (x - 200) * sin(a) + (y - 200) * cos(a)).toFloat() }
        val r = cornersOf(OutlineAssist.assist(noisy(closedPoly(tilted), 2f, 3)))
        assertEquals(4, r.size)
        val sides = (0 until 4).map { dist(r[it], r[(it + 1) % 4]) }
        sides.forEach { assertEquals(sides[0], it, 1e-3) }
        for (i in 0 until 4) assertEquals(90.0, angleAt(r[i], r[(i + 3) % 4], r[(i + 1) % 4]), 1e-3)
        assertTrue(abs(r[0].first - r[1].first) > 1f && abs(r[0].second - r[1].second) > 1f)
    }

    @Test fun `noisy rectangle keeps its aspect`() {
        val rect = listOf(100f to 100f, 400f to 100f, 400f to 250f, 100f to 250f)
        for (seed in 1L..20L) {
            val r = cornersOf(OutlineAssist.assist(noisy(closedPoly(rect), 2f, seed)))
            assertEquals(4, r.size)
            val w = dist(r[0], r[1])
            val h = dist(r[1], r[2])
            assertEquals(2.0, maxOf(w, h) / minOf(w, h), 0.08)
            for (i in 0 until 4) assertEquals(90.0, angleAt(r[i], r[(i + 3) % 4], r[(i + 1) % 4]), 1e-3)
        }
    }

    @Test fun `noisy circle becomes a circle`() {
        for (seed in 1L..20L) {
            val out = OutlineAssist.assist(noisy(ellipse(300f, 300f, 120f, 120f), 2f, seed))
            assertClean(out, true)
            val r = ring(out)
            val cx = r.sumOf { it.first.toDouble() } / r.size
            val cy = r.sumOf { it.second.toDouble() } / r.size
            val radii = r.map { hypot(it.first - cx, it.second - cy) }
            assertEquals(radii[0], radii.max(), 1e-2)
            assertEquals(radii[0], radii.min(), 1e-2)
            assertEquals(120.0, radii[0], 4.0)
        }
    }

    @Test fun `an already clean circle keeps its start point and winding`() {
        for (dir in listOf(1, -1)) {
            val circle = (0..96).map {
                val t = PI + dir * 2 * PI * it / 96
                (300f + 120f * cos(t).toFloat()) to (300f + 120f * sin(t).toFloat())
            }
            val out = OutlineAssist.assist(circle)
            assertClean(out, true)
            assertTrue("start ${out.first()}", dist(out.first(), circle.first()) < 8.0)
            assertEquals(Math.signum(signedArea(circle)), Math.signum(signedArea(out)), 0.0)
        }
    }

    @Test fun `every closed shape keeps its start point and winding`() {
        val shapes = mapOf(
            "square" to square,
            "rectangle" to listOf(100f to 100f, 400f to 100f, 400f to 250f, 100f to 250f),
            "triangle" to listOf(100f to 300f, 300f to 300f, 150f to 120f),
            "polygon" to listOf(100f to 300f, 300f to 300f, 300f to 180f, 130f to 80f, 100f to 180f),
            "hexagon" to regular(6),
        )
        for ((name, v) in shapes) for (dir in listOf(1, -1)) {
            val verts = if (dir == 1) v else v.reversed()
            val input = noisy(closedPoly(verts), 1f, 4)
            val out = OutlineAssist.assist(input)
            assertClean(out, true)
            assertEquals("$name winding", Math.signum(signedArea(input)), Math.signum(signedArea(out)), 0.0)
            assertTrue("$name start ${out.first()} vs ${input.first()}", dist(out.first(), input.first()) < 6.0)
        }
        for (dir in listOf(1, -1)) {
            val input = noisy(ellipse(300f, 300f, 160f, 80f).let { if (dir == 1) it else it.reversed() }, 1f, 5)
            val out = OutlineAssist.assist(input)
            assertEquals(Math.signum(signedArea(input)), Math.signum(signedArea(out)), 0.0)
            assertTrue(dist(out.first(), input.first()) < 12.0)
        }
    }

    @Test fun `regular polygons stay polygons with straight sides`() {
        for (n in 5..8) {
            for (seed in 1L..5L) {
                val out = OutlineAssist.assist(noisy(closedPoly(regular(n), 25), 1f, seed))
                assertClean(out, true)
                val corners = cornersOf(out)
                assertEquals("$n-gon seed $seed ${out.joinToString { "(%.0f,%.0f)".format(it.first, it.second) }}", n, corners.size)
                val sides = corners.indices.map { dist(corners[it], corners[(it + 1) % n]) }
                sides.forEach { assertEquals(sides[0], it, 1.0) }
                corners.forEach { assertEquals(120.0, dist(it, 300f to 300f), 3.0) }
            }
        }
    }

    @Test fun `a clean hexagon is not changed`() {
        val hex = regular(6)
        val out = OutlineAssist.assist(closedPoly(hex, 25, 0f))
        val corners = cornersOf(out)
        assertEquals(6, corners.size)
        for (v in hex) assertTrue(corners.any { dist(it, v) < 0.5 })
    }

    @Test fun `a thin closed bar stays a thin rectangle`() {
        for (h in listOf(20f, 15f, 10f)) {
            val bar = listOf(100f to 100f, 700f to 100f, 700f to 100f + h, 100f to 100f + h)
            val out = OutlineAssist.assist(closedPoly(bar, 25, 0f))
            assertClean(out, true)
            val corners = cornersOf(out)
            assertEquals("h=$h ${out.joinToString { "(%.0f,%.0f)".format(it.first, it.second) }}", 4, corners.size)
            assertEquals(600.0, corners.maxOf { it.first } - corners.minOf { it.first }.toDouble(), 1.0)
            assertEquals(h.toDouble(), corners.maxOf { it.second } - corners.minOf { it.second }.toDouble(), 1.0)
        }
    }

    @Test fun `a three vertex open V is made exactly symmetric`() {
        val out = OutlineAssist.assist(openPoly(listOf(100f to 300f, 205f to 100f, 300f to 300f)))
        assertClean(out, false)
        val cx = (out.first().first + out.last().first) / 2
        assertMirror(out, cx)
        assertEquals(3, out.size)
    }

    @Test fun `ellipse stays an ellipse`() {
        val r = ring(OutlineAssist.assist(noisy(ellipse(300f, 300f, 160f, 80f), 2f, 5)))
        val w = r.maxOf { it.first } - r.minOf { it.first }
        val h = r.maxOf { it.second } - r.minOf { it.second }
        assertEquals(2.0, (w / h).toDouble(), 0.08)
    }

    @Test fun `triangle keeps three corners`() {
        val tri = listOf(100f to 300f, 300f to 300f, 150f to 120f)
        for (seed in 1L..10L) {
            val out = OutlineAssist.assist(noisy(closedPoly(tri), 2f, seed))
            assertClean(out, true)
            assertEquals(3, cornersOf(out).size)
        }
    }

    @Test fun `near equilateral triangle becomes equilateral`() {
        val h = (200 * sqrt3 / 2).toFloat()
        val tri = listOf(100f to 300f, 300f to 300f, 200f to 300f - h)
        val r = cornersOf(OutlineAssist.assist(noisy(closedPoly(tri), 1.5f, 2)))
        assertEquals(3, r.size)
        val s = (0 until 3).map { dist(r[it], r[(it + 1) % 3]) }
        s.forEach { assertEquals(s[0], it, 1e-3) }
    }

    @Test fun `tilted wobbly line becomes a straight horizontal line`() {
        val line = (0..60).map { (100f + it * 5f) to (200f + it * 0.2f) }
        val out = OutlineAssist.assist(noisy(line, 1.2f, 4))
        assertEquals(2, out.size)
        assertEquals(out[0].second, out[1].second, 1e-3f)
    }

    @Test fun `diagonal line stays diagonal but straight`() {
        val line = (0..60).map { (100f + it * 4f) to (100f + it * 3f) }
        val out = OutlineAssist.assist(noisy(line, 1.2f, 4))
        assertEquals(2, out.size)
        assertEquals(0.75, ((out[1].second - out[0].second) / (out[1].first - out[0].first)).toDouble(), 0.03)
    }

    @Test fun `L shape keeps its corner and becomes exactly right angled`() {
        val l = listOf(100f to 100f, 100f to 300f, 330f to 305f)
        for (seed in 1L..10L) {
            val out = OutlineAssist.assist(noisy(openPoly(l), 2f, seed))
            assertClean(out, false)
            assertEquals(3, out.size)
            assertEquals(90.0, angleAt(out[1], out[0], out[2]), 1e-3)
            assertTrue(abs(out[0].first - out[1].first) < 1e-3)
        }
    }

    private fun vase(sigma: Float, seed: Long): Pts {
        fun w(y: Float) = 30f + 25f * sin(PI * y / 100).toFloat()
        val left = (0..50).map { (200f - w(it * 4f)) to (it * 4f) }
        val right = (50 downTo 0).map { (200f + w(it * 4f)) to (it * 4f) }
        val top = (1 until 10).map { (200f - w(200f) + 2 * w(200f) * it / 10) to 200f }
        val base = (1 until 10).map { (200f + w(0f) - 2 * w(0f) * it / 10) to 0f }
        val loop = left + top + right + base
        return noisy(loop + loop.first(), sigma, seed)
    }

    @Test fun `wobbly symmetric vase becomes exactly mirror symmetric`() {
        for (seed in 1L..10L) {
            val out = OutlineAssist.assist(vase(0.8f, seed))
            assertClean(out, true)
            val r = ring(out)
            val cx = (r.minOf { it.first } + r.maxOf { it.first }) / 2
            assertEquals(200f, cx, 3f)
            assertMirror(r, cx)
        }
    }

    @Test fun `wobbly symmetric open bell becomes exactly mirror symmetric`() {
        val bell = (0..100).map { (it * 2f) to (100f * sin(PI * it / 100).toFloat()) }
        for (seed in 1L..10L) {
            val out = OutlineAssist.assist(noisy(bell, 1.0f, seed))
            assertClean(out, false)
            val cx = (out.first().first + out.last().first) / 2
            assertMirror(out, cx)
            assertEquals(out.first().second, out.last().second, 1e-3f)
        }
    }

    @Test fun `deliberately lopsided shape stays lopsided`() {
        val house = listOf(100f to 300f, 300f to 300f, 300f to 180f, 130f to 80f, 100f to 180f)
        val out = OutlineAssist.assist(noisy(closedPoly(house), 1.5f, 6))
        assertClean(out, true)
        val r = ring(out)
        val cx = (r.minOf { it.first } + r.maxOf { it.first }) / 2
        val unmirrored = r.count { q -> r.none { abs(it.first - (2 * cx - q.first)) < 3f && abs(it.second - q.second) < 3f } }
        assertTrue("mirrored a lopsided shape", unmirrored >= 2)
    }

    @Test fun `assist is idempotent`() {
        val cases = mapOf(
            "square" to noisy(closedPoly(square), 2f, 1),
            "rect" to noisy(closedPoly(listOf(100f to 100f, 400f to 100f, 400f to 250f, 100f to 250f)), 2f, 2),
            "triangle" to noisy(closedPoly(listOf(100f to 300f, 300f to 300f, 150f to 120f)), 2f, 3),
            "line" to noisy((0..60).map { (100f + it * 5f) to (200f + it * 0.2f) }, 1.2f, 4),
            "L" to noisy(openPoly(listOf(100f to 100f, 100f to 300f, 330f to 305f)), 2f, 5),
            "circle" to noisy(ellipse(300f, 300f, 120f, 120f), 2f, 6),
            "ellipse" to noisy(ellipse(300f, 300f, 160f, 80f), 2f, 7),
            "vase" to vase(0.8f, 8),
            "bell" to noisy((0..100).map { (it * 2f) to (100f * sin(PI * it / 100).toFloat()) }, 1f, 9),
        )
        for ((name, input) in cases) {
            val once = OutlineAssist.assist(input)
            val twice = OutlineAssist.assist(once)
            if (name in setOf("square", "rect", "triangle", "line", "L")) assertEquals(name, once.size, twice.size)
            assertSameShape(once, twice, 0.5)
        }
    }

    @Test fun `tiny and degenerate strokes are returned unchanged`() {
        val cases = listOf(
            emptyList(), listOf(5f to 5f), listOf(5f to 5f, 5f to 5f, 5f to 5f),
            listOf(5f to 5f, 6f to 6f, 7f to 5f), listOf(5f to 5f, 8f to 5f),
        )
        for (c in cases) assertSame(c, OutlineAssist.assist(c))
        val nan = listOf(0f to 0f, Float.NaN to 3f, 100f to 100f)
        assertSame(nan, OutlineAssist.assist(nan))
    }

    @Test fun `output always passes outline validation`() {
        val r = Random(99)
        repeat(200) {
            val n = 2 + r.nextInt(300)
            val p = (0 until n).map { (r.nextFloat() * 500f) to (r.nextFloat() * 500f) }
            val out = OutlineAssist.assist(p)
            if (out !== p) assertTrue(OutlineAssist.isValidOutline(out))
        }
        val dense = noisy(ellipse(300f, 300f, 120f, 120f, n = 900), 3f, 1)
        assertTrue(OutlineAssist.isValidOutline(OutlineAssist.assist(dense)))
        assertFalse(OutlineAssist.assist(dense).size > SurfaceMath.MAX_POINTS)
    }

    @Test fun `a long hand drawn scribble is not forced into a shape`() {
        val wave = (0..200).map { (it * 3f) to (60f * sin(it / 12.0).toFloat() + 0.5f * it) }
        val out = OutlineAssist.assist(wave)
        assertTrue(out.size > 6)
    }

    private val sqrt3 = Math.sqrt(3.0)
}
