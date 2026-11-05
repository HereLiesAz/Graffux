@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Multi-view surfaces (spec/3d-space.md, "Group surfaces"): outlines intersected like silhouette carving. */
class SurfaceHullTest {
    /** A closed outline on the plane normal to [sweep], from 2D corners placed by [place]. */
    private fun outline(sweep: FloatArray, corners: List<Pair<Float, Float>>, place: (Float, Float) -> FloatArray) =
        SurfaceProfile(corners.flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])

    private fun rect(w: Float, h: Float) = listOf(0f to 0f, w to 0f, w to h, 0f to h)

    private fun circle(r: Float, n: Int = 48) = (0 until n).map {
        val a = 2 * PI.toFloat() * it / n
        (r * cos(a)) to (r * sin(a))
    }

    // Front looks down +z (x, y); side looks down +x (z, y); top looks down +y (x, z).
    private fun front(c: List<Pair<Float, Float>>) = outline(floatArrayOf(0f, 0f, 1f), c) { x, y -> floatArrayOf(x, y, 0f) }
    private fun side(c: List<Pair<Float, Float>>) = outline(floatArrayOf(1f, 0f, 0f), c) { z, y -> floatArrayOf(0f, y, z) }
    private fun top(c: List<Pair<Float, Float>>) = outline(floatArrayOf(0f, 1f, 0f), c) { x, z -> floatArrayOf(x, 0f, z) }

    private val cube = front(rect(100f, 100f)).copy(side = side(rect(100f, 100f)), top = top(rect(100f, 100f)))

    private fun area(m: FloatArray): Float {
        var total = 0f
        var i = 0
        while (i + 14 < m.size + 0 && i + 15 <= m.size) {
            val a = m.copyOfRange(i, i + 3)
            val b = m.copyOfRange(i + 5, i + 8)
            val c = m.copyOfRange(i + 10, i + 13)
            val u = FloatArray(3) { b[it] - a[it] }
            val v = FloatArray(3) { c[it] - a[it] }
            val x = SceneMath.cross(u, v)
            total += sqrt(SceneMath.dot(x, x)) / 2f
            i += 15
        }
        return total
    }

    @Test
    fun `a box from three rectangles has six faces and the right area`() {
        val hull = SurfaceHull.of(cube)!!
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE, SurfaceView.TOP), hull.views)
        val mesh = hull.meshByView()
        assertEquals(60000f, mesh.values.sumOf { area(it).toDouble() }.toFloat(), 60f)
        for (m in mesh.values) for (i in m.indices step 5) assertTrue(hull.contains(m[i], m[i + 1], m[i + 2]))
    }

    @Test
    fun `a ray at the box hits the nearest face and reads its unrolled coordinates`() {
        val hull = SurfaceHull.of(cube)!!
        val h = hull.hit(floatArrayOf(30f, 40f, -500f), floatArrayOf(0f, 0f, 1f), near = 1f)!!
        assertEquals(500f, h.distance, 0.05f)
        assertEquals(0f, h.z, 0.05f)
        assertEquals(-1f, h.nz, 1e-4f)
        assertFalse(h.fromInside)
        assertNotNull(h.view)
    }

    @Test
    fun `a front face hit carries arc length and signed depth`() {
        val hull = SurfaceHull.of(cube)!!
        // From the side through the box at height 40: enters at x = 0 (the front's left edge or the top's; either carries the plane).
        val h = hull.hit(floatArrayOf(-300f, 40f, 25f), floatArrayOf(1f, 0f, 0f), near = 1f)!!
        assertEquals(300f, h.distance, 0.05f)
        assertEquals(-1f, h.nx, 1e-4f)
        assertTrue(h.u in 0f..400f)
    }

    @Test
    fun `a cylinder from a circle and two rectangles`() {
        val r = 50f
        val cyl = front(listOf(-r to 0f, r to 0f, r to 100f, -r to 100f))
            .copy(side = side(listOf(-r to 0f, r to 0f, r to 100f, -r to 100f)), top = top(circle(r)))
        val hull = SurfaceHull.of(cyl)!!
        // Along x at mid height, z = 0: enters at the wall x = -r.
        val h = hull.hit(floatArrayOf(-400f, 50f, 0f), floatArrayOf(1f, 0f, 0f), near = 1f)!!
        assertEquals(400f - r, h.distance, 0.1f)
        // The corner of the square the circle cuts away is a miss in the carved solid.
        assertNull(hull.hit(floatArrayOf(-400f, 50f, 49f), floatArrayOf(1f, 0f, 0f), 1f)?.takeIf { it.x < -r * 0.9f && it.distance < 400f - 0.9f * r - 1f })
        assertFalse(hull.contains(r * 0.95f, 50f, r * 0.95f))
        assertTrue(hull.contains(0f, 50f, 0f))
        val m = hull.meshByView()
        val total = m.values.sumOf { area(it).toDouble() }.toFloat()
        val expected = (2 * PI * r * 100 + 2 * PI * r * r).toFloat()
        assertEquals(expected, total, expected * 0.03f)
    }

    @Test
    fun `an undrawn view does not constrain`() {
        val frontSide = front(rect(100f, 100f)).copy(side = side(rect(60f, 100f)))
        val hull = SurfaceHull.of(frontSide)!!
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE), hull.views)
        // Top undrawn: x spans the whole front, z only the side's depth.
        assertTrue(hull.contains(99f, 50f, 59f))
        assertFalse(hull.contains(50f, 50f, 61f))
    }

    @Test
    fun `a ray that misses returns null`() {
        val hull = SurfaceHull.of(cube)!!
        assertNull(hull.hit(floatArrayOf(500f, 500f, -500f), floatArrayOf(0f, 0f, 1f), 1f))
        assertNull(hull.hit(floatArrayOf(50f, 50f, -500f), floatArrayOf(0f, 0f, -1f), 1f))
    }

    @Test
    fun `a ray starting inside leaves through the far face`() {
        val hull = SurfaceHull.of(cube)!!
        val h = hull.hit(floatArrayOf(50f, 50f, 50f), floatArrayOf(0f, 0f, 1f), near = 0f)!!
        assertTrue(h.fromInside)
        assertEquals(50f, h.distance, 0.05f)
    }

    @Test
    fun `degenerate profiles do not constrain and do not crash`() {
        val line = front(listOf(0f to 0f, 100f to 0f, 200f to 0f))
        assertNull(SurfaceHull.of(line))
        assertNull(SurfaceHull.of(front(listOf(0f to 0f, 10f to 10f))))
        // A lone open outline has no solid to bound it: the caller keeps the single-view sheet.
        assertNull(SurfaceHull.of(front(listOf(0f to 0f, 50f to 0f, 100f to 0f)).copy(side = side(listOf(0f to 0f, 50f to 0f, 100f to 0f)))))
    }

    private val wallFront = front(listOf(0f to 50f, 100f to 50f))
        .copy(side = side(rect(60f, 100f)), top = top(rect(100f, 60f)))

    @Test
    fun `an open front stays a thin wall bounded by the side and top`() {
        val hull = SurfaceHull.of(wallFront)!!
        assertEquals(SurfaceView.FRONT, hull.wall)
        val h = hull.hit(floatArrayOf(50f, -200f, 30f), floatArrayOf(0f, 1f, 0f), 1f)!!
        assertEquals(250f, h.distance, 0.05f)
        assertEquals(SurfaceView.FRONT, h.view)
        assertEquals(50f, h.u, 0.05f)
        assertEquals(30f, h.t, 0.05f)
        // Beyond the side's depth, or off the wall's length, there is nothing.
        assertNull(hull.hit(floatArrayOf(50f, -200f, 70f), floatArrayOf(0f, 1f, 0f), 1f))
        assertNull(hull.hit(floatArrayOf(150f, -200f, 30f), floatArrayOf(0f, 1f, 0f), 1f))
        // The wall is the whole surface: the closed views' own faces are not there.
        assertNull(hull.hit(floatArrayOf(-300f, 20f, 30f), floatArrayOf(1f, 0f, 0f), 1f))
        assertEquals(6000f, hull.meshByView().values.sumOf { area(it).toDouble() }.toFloat(), 10f)
        assertEquals(setOf(SurfaceView.FRONT), hull.meshByView().keys)
    }

    @Test
    fun `an open side with a closed front is a wall too, and only one wall is kept`() {
        val p = front(rect(100f, 100f)).copy(side = side(listOf(0f to 0f, 50f to 100f)), top = top(listOf(0f to 0f, 100f to 60f)))
        val hull = SurfaceHull.of(p)!!
        assertEquals(SurfaceView.SIDE, hull.wall)
        assertEquals(listOf(SurfaceView.FRONT, SurfaceView.SIDE), hull.views)
    }

    @Test
    fun `a closed outline with a repeated first vertex is the same solid`() {
        val closed = front(rect(100f, 100f) + listOf(0f to 0f)).copy(side = side(rect(100f, 100f)), top = top(rect(100f, 100f)))
        assertEquals(60000f, SurfaceHull.of(closed)!!.meshByView().values.sumOf { area(it).toDouble() }.toFloat(), 60f)
    }

    @Test
    fun `world to surface casts through the camera and only for multi-view groups`() {
        val group = com.hereliesaz.graffitixr.common.model.Layer("g", "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = cube)
        val owner = SurfaceMath.ownerOf(listOf(group), "g")!!
        val hit = SurfaceMath.worldToSurface(50f, 50f, owner, SceneCamera(), 1000f, 800f)
        assertNotNull(hit)
        val single = group.copy(surface = cube.copy(side = null, top = null))
        assertNull(SurfaceMath.worldToSurface(50f, 50f, SurfaceMath.ownerOf(listOf(single), "g")!!, SceneCamera(), 1000f, 800f))
    }

    @Test
    fun `views are replaced, not stacked, and the front keeps the others`() {
        val newSide = side(rect(10f, 10f))
        val p = cube.withView(SurfaceView.SIDE, newSide.copy(side = cube))
        assertEquals(newSide, p.side)
        assertEquals(cube.top, p.top)
        val f = p.withView(SurfaceView.FRONT, front(rect(5f, 5f)))
        assertEquals(newSide, f.side)
        assertEquals(cube.top, f.top)
        assertEquals(front(rect(5f, 5f)), f.view(SurfaceView.FRONT))
        assertTrue(f.hasHull)
    }

    @Test
    fun `camera presets align to the side and the top`() {
        val base = SceneCamera(yaw = 0.3f, pitch = 0.2f, distanceScale = 2f)
        assertEquals(SceneCamera(distanceScale = 2f), SurfaceView.FRONT.camera(base))
        assertEquals(PI.toFloat() / 2, SurfaceView.SIDE.camera(base).yaw, 1e-5f)
        assertEquals(SceneCamera.MAX_PITCH, SurfaceView.TOP.camera(base).pitch, 0f)
        assertEquals(2f, SurfaceView.TOP.camera(base).distanceScale, 0f)
    }

    @Test
    fun `a profile with side and top round-trips as json and a v9 profile still reads`() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(SurfaceProfile.serializer(), cube)
        assertEquals(cube, json.decodeFromString(SurfaceProfile.serializer(), text))
        val v9 = json.decodeFromString(SurfaceProfile.serializer(), """{"points":[0,0,0,10,0,0],"sweepZ":1}""")
        assertFalse(v9.hasHull)
    }

    @Test
    fun `each view has its own paint image sized by its outline and reach`() {
        val hull = SurfaceHull.of(cube)!!
        for (v in SurfaceView.values()) {
            val (w, h) = hull.paintSize(v)!!
            assertEquals(400f, w, 0.01f)
            assertEquals(100f, h, 0.05f)
        }
        // A hit on the cube's front-most face lands inside that view's image, never negative.
        val hit = hull.hit(floatArrayOf(30f, 40f, -500f), floatArrayOf(0f, 0f, 1f), 1f)!!
        val (x, y) = hull.paintPoint(hit)!!
        val (w, h) = hull.paintSize(hit.view!!)!!
        assertTrue(x in 0f..w && y in 0f..h)
        assertNull(SurfaceHull.of(wallFront)!!.paintSize(SurfaceView.SIDE))
        assertNotNull(SurfaceHull.of(wallFront)!!.paintSize(SurfaceView.FRONT))
    }

    @Test
    fun `world to view flat maps a touch into the hit view's image`() {
        val group = com.hereliesaz.graffitixr.common.model.Layer("g", "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = cube)
        val leaf = com.hereliesaz.graffitixr.common.model.Layer("a", "A", parentId = "g")
        val owner = SurfaceMath.ownerOf(listOf(group, leaf), "a")!!
        val f = SurfaceMath.worldToViewFlat(50f, 50f, owner, SceneCamera(), 1000f, 800f)!!
        assertTrue(f.x in 0f..400f && f.y in 0f..100f)
        assertNull(SurfaceMath.worldToViewFlat(900f, 700f, owner, SceneCamera(), 1000f, 800f))
    }

    // ---- caps (spec/3d-space.md, "Caps") ----

    /** A top view swept slightly off vertical (as a real top camera is), so the box closes the +z end of the solid. */
    private val tiltedTop = outline(floatArrayOf(0f, -0.98f, 0.196f), rect(100f, 40f)) { x, z -> floatArrayOf(x, 100f, z - 20f) }
    private val capped = front(rect(100f, 100f)).copy(top = tiltedTop)

    private fun triangles(m: FloatArray) = (0 until m.size / 15).map { t ->
        (0..2).map { v -> FloatArray(3) { m[t * 15 + v * 5 + it] } }
    }

    @Suppress("ReturnCount")
    private fun onTriangle(p: FloatArray, tri: List<FloatArray>, eps: Float): Boolean {
        val a = tri[0]; val b = tri[1]; val c = tri[2]
        val n = SceneMath.cross(FloatArray(3) { b[it] - a[it] }, FloatArray(3) { c[it] - a[it] })
        val nl = sqrt(SceneMath.dot(n, n))
        if (nl < 1e-9f) return false
        val d = SceneMath.dot(FloatArray(3) { p[it] - a[it] }, n) / nl
        if (abs(d) > eps) return false
        val u = SceneMath.cross(FloatArray(3) { c[it] - b[it] }, FloatArray(3) { p[it] - b[it] })
        val v = SceneMath.cross(FloatArray(3) { a[it] - c[it] }, FloatArray(3) { p[it] - c[it] })
        val w = SceneMath.cross(FloatArray(3) { b[it] - a[it] }, FloatArray(3) { p[it] - a[it] })
        return SceneMath.dot(u, n) >= -eps * nl && SceneMath.dot(v, n) >= -eps * nl && SceneMath.dot(w, n) >= -eps * nl
    }

    @Test
    fun `a face the box closes is a cap, drawn and paintable in the host view's image`() {
        val hull = SurfaceHull.of(capped)!!
        val h = hull.hit(floatArrayOf(50f, 50f, 500f), floatArrayOf(0f, 0f, -1f), near = 1f)!!
        assertTrue(h.cap)
        assertEquals(20f, h.z, 0.05f)
        // The view that looks at the face most squarely (front, sweeping along z) holds it, right of its sheet.
        val target = hull.paintTarget(h)!!
        assertEquals(SurfaceView.FRONT, target.view)
        assertTrue(target.region > 0)
        assertTrue(target.x > hull.paintSize(SurfaceView.FRONT)!!.first)
        // And it is a mesh, the whole 100 x 100 face.
        val cap = hull.capMeshByView()[SurfaceView.FRONT]!!
        assertEquals(10000f, area(cap), 100f)
        for (i in 2 until cap.size step 5) assertEquals(20f, cap[i], 0.05f)
    }

    @Test
    fun `sheets and caps leave no gap and no overlap in the drawn surface`() {
        val hull = SurfaceHull.of(capped)!!
        val tris = (hull.meshByView().values + hull.capMeshByView().values).flatMap { triangles(it) }
        val rnd = java.util.Random(7)
        var hits = 0
        var capHits = 0
        repeat(400) {
            val aim = floatArrayOf(rnd.nextFloat() * 100f, rnd.nextFloat() * 100f, rnd.nextFloat() * 40f - 20f)
            // Half the rays look down the cap's axis, the rest come from anywhere round the solid.
            val o = if (it % 2 == 0) {
                floatArrayOf(aim[0] + rnd.nextFloat() * 60f - 30f, aim[1] + rnd.nextFloat() * 60f - 30f, 300f)
            } else {
                floatArrayOf(rnd.nextFloat() * 600f - 250f, rnd.nextFloat() * 600f - 250f, rnd.nextFloat() * 600f - 250f)
            }
            val dir = FloatArray(3) { aim[it] - o[it] }
            val h = hull.hit(o, dir, near = 1f) ?: return@repeat
            hits++
            if (h.cap) capHits++
            val p = floatArrayOf(h.x, h.y, h.z)
            assertTrue("hit at ${p.toList()} (view ${h.view}) is on drawn surface", tris.any { onTriangle(p, it, 0.5f) })
            assertNotNull("every hit is paintable", hull.paintTarget(h))
        }
        assertTrue(hits > 100)
        assertTrue("some rays land on the cap, $capHits of $hits", capHits > 5)
        // Closed: every mesh vertex is in or on the solid.
        for (m in hull.capMeshByView().values) for (i in m.indices step 5) assertTrue(hull.contains(m[i], m[i + 1], m[i + 2]))
    }

    @Test
    fun `a face that coincides with a drawn sheet is not also a cap`() {
        val hull = SurfaceHull.of(cube)!!
        assertTrue(hull.capMeshByView().isEmpty())
        val h = hull.hit(floatArrayOf(30f, 40f, -500f), floatArrayOf(0f, 0f, 1f), near = 1f)!!
        assertEquals(0, hull.paintTarget(h)!!.region)
    }

    @Test
    fun `cap regions do not overlap the sheet or each other in the host image`() {
        val hull = SurfaceHull.of(capped)!!
        val width = hull.paintSize(SurfaceView.FRONT)!!.first
        val cap = hull.capMeshByView()[SurfaceView.FRONT]!!
        for (i in 3 until cap.size step 5) assertTrue(cap[i] > width)
    }

    @Test
    fun `an oblique hit on a plane two views share is the view that draws it`() {
        // Front, side and top boxes share their planes pairwise: a slanted ray meets each shared plane
        // a rounding error apart through the two views' edges, so the nearer by a hair must not decide.
        val hull = SurfaceHull.of(cube)!!
        val mesh = hull.meshByView().mapValues { triangles(it.value) }
        val rnd = java.util.Random(3)
        var checked = 0
        repeat(500) {
            val aim = floatArrayOf(rnd.nextFloat() * 100f, rnd.nextFloat() * 100f, rnd.nextFloat() * 100f)
            val dir = floatArrayOf(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
            val o = FloatArray(3) { aim[it] - dir[it] * 600f }
            val h = hull.hit(o, dir, near = 1f) ?: return@repeat
            if (h.fromInside) return@repeat
            checked++
            assertNotNull("a hit on the cube is never a cap", h.view)
            assertTrue("hit at (${h.x}, ${h.y}, ${h.z}) names ${h.view}, which draws no patch there", mesh[h.view]!!.any { t -> onTriangle(floatArrayOf(h.x, h.y, h.z), t, 0.5f) })
        }
        assertTrue(checked > 100)
    }
}
