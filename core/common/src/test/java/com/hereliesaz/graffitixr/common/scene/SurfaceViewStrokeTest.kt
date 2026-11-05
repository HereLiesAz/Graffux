@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Painting a group drawn from several views (spec/3d-space.md, "Paint per view"): the mapping and the cut between images. */
class SurfaceViewStrokeTest {
    private fun outline(sweep: FloatArray, corners: List<Pair<Float, Float>>, place: (Float, Float) -> FloatArray) =
        SurfaceProfile(corners.flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])

    private val box = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
    private val cube = outline(floatArrayOf(0f, 0f, 1f), box) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f), box) { z, y -> floatArrayOf(0f, y, z) },
        top = outline(floatArrayOf(0f, 1f, 0f), box) { x, z -> floatArrayOf(x, 0f, z) },
    )
    private val group = Layer("g", "G", type = LayerType.GROUP, surface = cube)
    private val leaf = Layer("a", "A", parentId = "g")
    private val owner = SurfaceMath.ownerOf(listOf(group, leaf), "a")!!
    private val hull = SurfaceHull.of(cube)!!

    /** A camera turned off the front so the cube shows two faces side by side. */
    private val camera = SceneCamera(yaw = 0.7f, pitch = 0.2f)
    private val w = 1000f
    private val h = 800f

    private fun map(x: Float, y: Float) = SurfaceMath.worldToViewFlat(x, y, owner, camera, w, h, hull)

    /** The first screen step along a row where the touched view changes: (x before, x after). */
    private fun viewChange(): Triple<Float, Float, Float>? {
        for (y in 300..500 step 25) {
            var prev: SurfaceMath.ViewFlat? = null
            var px = 0f
            var x = 0f
            while (x < w) {
                val m = map(x, y.toFloat())
                if (prev != null && m != null && m.view != prev.view) return Triple(px, x, y.toFloat())
                if (m != null) {
                    prev = m
                    px = x
                } else {
                    prev = null
                }
                x += 2f
            }
        }
        return null
    }

    @Test
    fun `a touch on each visible face lands in that face's own image`() {
        val (x0, x1, y) = viewChange()!!
        val a = map(x0, y)!!
        val b = map(x1, y)!!
        assertNotEquals(a.view, b.view)
        for (f in listOf(a, b)) {
            val (pw, ph) = hull.paintSize(f.view)!!
            assertTrue("${f.view} x=${f.x} in 0..$pw", f.x in 0f..pw)
            assertTrue("${f.view} y=${f.y} in 0..$ph", f.y in -0.01f..ph + 0.01f)
        }
    }

    @Test
    fun `the cut between two images brackets the crease with no gap`() {
        val (x0, x1, y) = viewChange()!!
        val from = map(x0, y)!!
        val to = map(x1, y)!!
        val c = SurfaceViewStroke.crossing(x0, y, from, x1, y, to, ::map)
        assertEquals(from.view, c.last.view)
        assertEquals(to.view, c.first.view)
        // The two cut points are the same place on the surface to within a hair: nothing is left unpainted between them.
        val p = SurfaceMath.worldToSurface(c.lastX, c.lastY, owner, camera, w, h, hull)!!
        val q = SurfaceMath.worldToSurface(c.firstX, c.firstY, owner, camera, w, h, hull)!!
        val d = sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y) + (p.z - q.z) * (p.z - q.z))
        assertTrue("gap $d", d < 0.05f)
        // And they are tighter than the step the touches were apart.
        assertTrue(kotlin.math.abs(c.firstX - c.lastX) < 0.001f)
    }

    @Test
    fun `a step across a gap keeps the touches as the cut`() {
        val (x0, x1, y) = viewChange()!!
        val from = map(x0, y)!!
        val to = map(x1, y)!!
        // Nothing is ever on the surface in between: no bracket to trust.
        val c = SurfaceViewStroke.crossing(x0, y, from, x1, y, to) { _, _ -> null }
        assertEquals(x0, c.lastX, 0f)
        assertEquals(x1, c.firstX, 0f)
    }

    @Test
    fun `a footprint that reaches the surface paints it where the ray misses`() {
        // Beside the cube on screen: the ray misses, a brush as wide as the gap still reaches it.
        val edge = (0..1000).firstNotNullOfOrNull { x -> if (map(x.toFloat(), 400f) != null) x else null }!!
        val miss = edge - 6f
        assertNull(map(miss, 400f))
        assertNull(SurfaceFootprint.worldToViewFlat(miss, 400f, 2f, owner, camera, w, h, hull))
        val reached = SurfaceFootprint.worldToViewFlat(miss, 400f, 20f, owner, camera, w, h, hull)
        assertNotNull(reached)
        assertNull(SurfaceFootprint.worldToViewFlat(5f, 5f, 20f, owner, camera, w, h, hull))
    }

    @Test
    fun `a group with one view has no view mapping and keeps the sheet`() {
        val single = Layer("g", "G", type = LayerType.GROUP, surface = cube.copy(side = null, top = null))
        val o = SurfaceMath.ownerOf(listOf(single, leaf), "a")!!
        assertNull(SurfaceMath.worldToViewFlat(500f, 400f, o, camera, w, h))
        assertNull(SurfaceFootprint.worldToViewFlat(500f, 400f, 30f, o, camera, w, h))
    }
}
