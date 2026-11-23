@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The surface model's base perspective and solid flag (spec/guided-surface.md, "Any perspective first"). */
class SurfaceBaseViewTest {
    private fun outline(view: SurfaceView, pts: List<Pair<Float, Float>>, depth: Float = 0f): SurfaceProfile {
        val (u, v) = when (view) {
            SurfaceView.FRONT -> 0 to 1
            SurfaceView.SIDE -> 2 to 1
            SurfaceView.TOP -> 0 to 2
        }
        val flat = pts.flatMap { (p, q) -> FloatArray(3).also { it[u] = p; it[v] = q; it[view.axis] = depth }.toList() }
        val sweep = FloatArray(3).also { it[view.axis] = 1f }
        return SurfaceProfile(flat, sweep[0], sweep[1], sweep[2])
    }

    private val rect = listOf(0f to 0f, 100f to 0f, 100f to 60f, 0f to 60f, 0f to 0f)
    private val circle = (0 until 48).map { 50f + 50f * cos(2 * PI.toFloat() * it / 48) to 50f + 50f * sin(2 * PI.toFloat() * it / 48) }
    private val open = listOf(0f to 0f, 50f to 0f, 100f to 0f)

    @Test
    fun `a profile from before format 15 is front-first and not solid`() {
        val p = SurfaceProfile(listOf(0f, 0f, 0f, 100f, 0f, 0f))
        assertNull(p.base)
        assertEquals(SurfaceView.FRONT, p.baseView)
        assertFalse(p.solid)
        assertFalse(p.hasHull)
        assertEquals(listOf(SurfaceView.FRONT), p.drawnViews)
    }

    @Test
    fun `view and withView are exact for the front base, as before`() {
        val base = outline(SurfaceView.FRONT, rect)
        val side = outline(SurfaceView.SIDE, rect)
        val top = outline(SurfaceView.TOP, rect)
        val both = base.withView(SurfaceView.SIDE, side).withView(SurfaceView.TOP, top)
        assertEquals(side, both.side)
        assertEquals(top, both.top)
        assertNull(both.front)
        assertEquals(base, both.view(SurfaceView.FRONT))
        assertEquals(side, both.view(SurfaceView.SIDE))
        assertTrue(both.hasHull)
        // Drawing the front again keeps the others.
        val redrawn = both.withView(SurfaceView.FRONT, outline(SurfaceView.FRONT, open))
        assertEquals(side, redrawn.side)
        assertEquals(top, redrawn.top)
    }

    @Test
    fun `every base keeps its root and puts the others in the other two slots`() {
        for (base in SurfaceView.entries) {
            val others = SurfaceGuide.order(base).drop(1)
            val root = outline(base, rect).copy(base = base.takeIf { it != SurfaceView.FRONT })
            var p = root
            for (v in others) p = p.withView(v, outline(v, rect))
            assertEquals(base, p.baseView)
            assertEquals(SurfaceGuide.order(base), p.drawnViews)
            assertEquals(outline(base, rect), p.view(base))
            for (v in others) assertEquals(outline(v, rect), p.view(v))
            assertTrue(p.hasHull)
            // The slot for the base's own perspective is never filled.
            val slots = mapOf(SurfaceView.FRONT to p.front, SurfaceView.SIDE to p.side, SurfaceView.TOP to p.top)
            assertNull(slots.getValue(base))
            // withView never moves a surface between the root and a slot.
            assertEquals(base, p.withView(others[0], outline(others[0], open)).baseView)
            assertEquals(base, p.withView(base, outline(base, open)).baseView)
        }
    }

    @Test
    fun `solid makes a lone closed outline a hull, an open one stays a sheet`() {
        val closed = outline(SurfaceView.FRONT, rect).copy(solid = true)
        assertTrue(closed.hasHull)
        assertNotNull(SurfaceHull.of(closed))
        // An arc encloses area when its ends are joined, but it does not end on its start: it is open.
        val arc = (0..16).map { 50f + 50f * cos(PI.toFloat() * it / 16) to 50f * sin(PI.toFloat() * it / 16) }
        assertFalse(SurfaceGuide.isClosed(outline(SurfaceView.FRONT, arc)))
        assertTrue(SurfaceGuide.isClosed(closed))
        assertFalse(outline(SurfaceView.FRONT, rect).hasHull)
        assertNull(SurfaceHull.of(outline(SurfaceView.FRONT, open).copy(solid = true)))
        assertNull(closed.sweepLimit)
    }

    @Test
    fun `a solid circle extends as far as its widest side both ways, like a cylinder as long as it is wide`() {
        for (base in SurfaceView.entries) {
            val p = outline(base, circle).copy(base = base.takeIf { it != SurfaceView.FRONT }, solid = true)
            val hull = SurfaceHull.of(p)!!
            assertEquals(listOf(base), hull.views)
            assertNull(hull.wall)
            val lo = hull.boundsMin
            val hi = hull.boundsMax
            val spans = (0..2).map { hi[it] - lo[it] }
            assertEquals("$base", 100f, spans[0], 0.5f)
            assertEquals("$base", 100f, spans[1], 0.5f)
            assertEquals("$base", 100f, spans[2], 0.5f)
            // The axis the outline looks along is centred on its plane (depth 0), so it spans both ways.
            assertEquals(-50f, lo[base.axis], 0.5f)
            assertEquals(50f, hi[base.axis], 0.5f)
            // A ray along the sweep through the middle meets the solid: it is not an unbounded tube.
            val origin = FloatArray(3) { 50f }.also { it[base.axis] = -500f }
            val dir = FloatArray(3).also { it[base.axis] = 1f }
            val hit = hull.hit(origin, dir)
            assertNotNull("$base", hit)
            assertEquals("$base", 450f, hit!!.distance, 1f)
        }
    }

    @Test
    fun `a hull from any first perspective equals the front-first one for the same outlines rotated`() {
        // A circle bounded by a rectangle: the front-first hull, and the same pair with the side as the root.
        val frontFirst = outline(SurfaceView.FRONT, circle).copy(solid = true)
            .withView(SurfaceView.SIDE, outline(SurfaceView.SIDE, rect))
        val sideFirst = outline(SurfaceView.SIDE, circle).copy(base = SurfaceView.SIDE, solid = true)
            .withView(SurfaceView.TOP, outline(SurfaceView.TOP, rect))
        val a = SurfaceHull.of(frontFirst)!!
        val b = SurfaceHull.of(sideFirst)!!
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE), a.views)
        assertEquals(listOf(SurfaceView.SIDE, SurfaceView.TOP), b.views)
        assertNull(a.wall)
        assertNull(b.wall)
        // The same shape in a rotated frame (front, side, top) -> (side, top, front): the cycle x -> z -> y -> x.
        val box = { h: SurfaceHull -> (0..2).map { h.boundsMax[it] - h.boundsMin[it] }.sorted() }
        assertEquals(box(a)[0], box(b)[0], 0.5f)
        assertEquals(box(a)[1], box(b)[1], 0.5f)
        assertEquals(box(a)[2], box(b)[2], 0.5f)
        // The cycle x -> y -> z -> x carries the front-first pair onto the side-first one: a point is inside one
        // exactly when its image (z, x, y) is inside the other. Sampled off the faces, away from ties.
        val ticks = (0 until 11).map { 3.7f + 9.1f * it }
        val points = ticks.flatMap { x -> ticks.flatMap { y -> ticks.map { z -> Triple(x, y, z) } } }
        for ((x, y, z) in points) assertEquals("($x, $y, $z)", a.contains(x, y, z), b.contains(z, x, y))
        val inside = points.count { (x, y, z) -> a.contains(x, y, z) }
        assertTrue("the sample hits the solid", inside in 1 until points.size)
    }

    @Test
    fun `an open first outline is the wall, shaped by the closed second and third`() {
        val wall = outline(SurfaceView.SIDE, open).copy(base = SurfaceView.SIDE)
            .withView(SurfaceView.TOP, outline(SurfaceView.TOP, circle))
        val hull = SurfaceHull.of(wall)!!
        assertEquals(SurfaceView.SIDE, hull.wall)
        assertEquals(listOf(SurfaceView.TOP, SurfaceView.SIDE), hull.views)
    }

    @Test
    fun `a second open outline is not used when a wall already exists, the root is the wall`() {
        val p = outline(SurfaceView.TOP, open).copy(base = SurfaceView.TOP)
            .withView(SurfaceView.FRONT, outline(SurfaceView.FRONT, open))
            .withView(SurfaceView.SIDE, outline(SurfaceView.SIDE, rect))
        val hull = SurfaceHull.of(p)!!
        // The root (top) is the first open one in step order, so it is the wall; the front's open outline is dropped.
        assertEquals(SurfaceView.TOP, hull.wall)
        assertEquals(listOf(SurfaceView.SIDE, SurfaceView.TOP), hull.views)
    }

    @Test
    fun `the new fields are written only when set and a file without them reads as before`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val legacy = outline(SurfaceView.FRONT, rect)
        val text = json.encodeToString(SurfaceProfile.serializer(), legacy)
        assertTrue(text, listOf("\"base\"", "\"front\"", "\"solid\"").none { text.contains(it) })
        val guided = outline(SurfaceView.TOP, circle).copy(base = SurfaceView.TOP, front = legacy, solid = true)
        val back = json.decodeFromString(SurfaceProfile.serializer(), json.encodeToString(SurfaceProfile.serializer(), guided))
        assertEquals(guided, back)
        assertEquals(SurfaceView.TOP, back.baseView)
        assertEquals(legacy, json.decodeFromString(SurfaceProfile.serializer(), text))
        // An older file with a side view and no new keys is the front-first hull it always was.
        val v10 = legacy.withView(SurfaceView.SIDE, outline(SurfaceView.SIDE, rect))
        val read = json.decodeFromString(SurfaceProfile.serializer(), json.encodeToString(SurfaceProfile.serializer(), v10))
        assertEquals(SurfaceView.FRONT, read.baseView)
        assertEquals(v10, read)
    }

    @Test
    fun `the cyclic order is front, side, top rotated to start at the first`() {
        assertEquals(listOf(SurfaceView.SIDE, SurfaceView.TOP, SurfaceView.FRONT), SurfaceView.cyclicFrom(SurfaceView.SIDE))
        assertEquals(setOf(0, 1, 2), SurfaceView.entries.map { it.axis }.toSet())
    }

    // ── Redrawing the root keeps `solid` honest (a stale flag made a hull that renders nothing) ──

    private fun renders(p: SurfaceProfile) = !p.hasHull || SurfaceHull.of(p) != null

    @Test
    fun `a closed solid redrawn open is no longer solid and still renders`() {
        for (base in SurfaceView.entries) {
            val solid = outline(base, circle).copy(base = base.takeIf { it != SurfaceView.FRONT }, solid = true)
            val redrawn = solid.withView(base, outline(base, open))
            assertFalse("$base", redrawn.solid)
            assertEquals(base, redrawn.baseView)
            assertTrue("$base renders", renders(redrawn))
            assertFalse("$base is a sheet again", redrawn.hasHull)
        }
    }

    @Test
    fun `a guided open root redrawn closed becomes a solid`() {
        for (base in listOf(SurfaceView.SIDE, SurfaceView.TOP)) {
            val sheet = outline(base, open).copy(base = base)
            val redrawn = sheet.withView(base, outline(base, circle))
            assertTrue("$base", redrawn.solid)
            assertNotNull(SurfaceHull.of(redrawn))
        }
    }

    @Test
    fun `redrawing the root keeps the other views and recomputes solid with them present`() {
        val second = outline(SurfaceView.SIDE, rect)
        val third = outline(SurfaceView.TOP, rect)
        val full = outline(SurfaceView.FRONT, circle).copy(solid = true).withView(SurfaceView.SIDE, second)
            .withView(SurfaceView.TOP, third)
        val openFront = full.withView(SurfaceView.FRONT, outline(SurfaceView.FRONT, open))
        assertFalse(openFront.solid)
        assertEquals(second, openFront.side)
        assertEquals(third, openFront.top)
        assertTrue(renders(openFront))
        val closedAgain = openFront.withView(SurfaceView.FRONT, outline(SurfaceView.FRONT, rect))
        // No longer guided (not solid, front base): the legacy rule keeps it a non-solid root, as before.
        assertFalse(closedAgain.solid)
        assertTrue(renders(closedAgain))
        val sideRoot = outline(SurfaceView.SIDE, circle).copy(base = SurfaceView.SIDE, solid = true)
            .withView(SurfaceView.TOP, third).withView(SurfaceView.FRONT, second)
        val reopened = sideRoot.withView(SurfaceView.SIDE, outline(SurfaceView.SIDE, open))
        assertFalse(reopened.solid)
        assertEquals(third, reopened.top)
        assertTrue(renders(reopened))
    }

    @Test
    fun `a legacy front-first sheet redrawn closed stays the sheet it was`() {
        val sheet = outline(SurfaceView.FRONT, open)
        val redrawn = sheet.withView(SurfaceView.FRONT, outline(SurfaceView.FRONT, rect))
        assertFalse(redrawn.solid)
        assertFalse(redrawn.hasHull)
    }

    // ── Pinned behaviour (owner call, see the spec's known limits) ──

    @Test
    fun `an arc root is open by the guide but the hull treats it as a closed prism once a closed second exists`() {
        val arc = (0..24).map { 50f + 50f * cos(1.5f * PI.toFloat() * it / 24) to 50f + 50f * sin(1.5f * PI.toFloat() * it / 24) }
        val root = outline(SurfaceView.FRONT, arc)
        assertFalse(SurfaceGuide.isClosed(root))
        assertFalse(root.copy(solid = false).hasHull)
        val withSecond = root.withView(SurfaceView.SIDE, outline(SurfaceView.SIDE, rect))
        val hull = SurfaceHull.of(withSecond)!!
        // The older hull rule (area when the ends are joined) is unchanged: both are closed prisms, no wall.
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE), hull.views)
        assertNull(hull.wall)
    }

    @Test
    fun `a mirrored third can widen the hull beyond the first two, by its plane constant`() {
        val first = outline(SurfaceView.FRONT, circle)
        val second = outline(SurfaceView.SIDE, rect, depth = 200f)
        val without = SurfaceHull.of(first.copy(solid = true).withView(SurfaceView.SIDE, second))!!
        val third = SurfaceGuide.mirrored(second, first, SurfaceView.FRONT, SurfaceView.SIDE)
        val with = SurfaceHull.of(first.copy(solid = true).withView(SurfaceView.SIDE, second).withView(SurfaceView.TOP, third))!!
        // The third sits at y = 50 + (200 - 50) = 150, beyond the first two's 100.
        assertTrue(with.boundsMax[1] > without.boundsMax[1] + 1f)
    }
}
