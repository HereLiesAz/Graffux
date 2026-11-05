@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
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
}
