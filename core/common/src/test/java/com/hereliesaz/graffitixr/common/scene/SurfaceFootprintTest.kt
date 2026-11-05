package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Paint and erase by footprint (spec/3d-space.md, "Painting by footprint"): where a stroke lands on
 * a surface does not depend on which part of it the touch hits, only on the brush footprint under it.
 */
class SurfaceFootprintTest {
    private val w = 1000f
    private val h = 800f
    private val camera = SceneCamera()
    private val identity = listOf(PlanePose())

    private fun line(from: Pair<Float, Float>, to: Pair<Float, Float>, n: Int = 40) = (0..n).map {
        val f = it / n.toFloat()
        (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f)
    }

    /** A wall (200,200)-(200,600) then a floor (200,600)-(600,600): two segments, a crease at u = 400. */
    private val ell = line(200f to 200f, 200f to 600f) + line(200f to 600f, 600f to 600f).drop(1)

    private fun owner(): SurfaceOwner {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = SurfaceMath.capture(ell, camera, w, h, identity))
        return SurfaceMath.ownerOf(listOf(group, Layer("c", "C", parentId = "g")), "c")!!
    }

    private fun map(owner: SurfaceOwner, p: Pair<Float, Float>, radius: Float) =
        SurfaceFootprint.worldToFlat(p.first, p.second, radius, owner, camera, w, h)

    private val acrossCrease = listOf(
        215f to 500f, 215f to 560f, 215f to 590f, 235f to 597f, 280f to 597f, 340f to 597f,
    )

    @Test
    fun `a stroke whose samples fall on both legs of a bent profile paints every sample, continuously`() {
        val o = owner()
        val flat = acrossCrease.map { map(o, it, 20f) }
        flat.forEachIndexed { i, f -> assertNotNull("sample $i dropped", f) }
        // u runs up the wall, through the crease at 400, and along the floor.
        assertTrue(flat.first()!!.first < 400f)
        assertTrue(flat.last()!!.first > 400f)
        flat.zipWithNext().forEach { (a, b) ->
            val step = hypot(b!!.first - a!!.first, b.second - a.second)
            assertTrue("gap $a -> $b", step < 150f)
        }
    }

    @Test
    fun `a hit is the same whatever the radius`() {
        val o = owner()
        val exact = SurfaceMath.worldToFlat(300f, 590f, o, camera, w, h)!!
        val withFootprint = map(o, 300f to 590f, 60f)!!
        assertEquals(exact.first, withFootprint.first, 1e-3f)
        assertEquals(exact.second, withFootprint.second, 1e-3f)
    }

    @Test
    fun `a touch whose centre misses but whose footprint reaches the surface still lands on it`() {
        val o = owner()
        // Below the floor's near edge (t < 0): the ray misses every strip.
        assertNull(SurfaceMath.worldToFlat(400f, 615f, o, camera, w, h))
        val p = map(o, 400f to 615f, 30f)
        assertNotNull(p)
        assertEquals(600f, p!!.first, 3f)
        assertEquals(0f, p.second, 1f)
    }

    @Test
    fun `a touch whose footprint does not reach the surface is a true miss`() {
        val o = owner()
        assertNull(map(o, 400f to 615f, 5f))
        assertNull(map(o, 500f to 100f, 20f))
    }

    @Test
    fun `beyond the profile's end the footprint clamps to the end`() {
        val o = owner()
        // The wall begins at (200,200), u = 0; a touch just above it.
        val p = map(o, 200f to 190f, 25f)
        assertNotNull(p)
        assertEquals(0f, p!!.first, 2f)
    }
}
