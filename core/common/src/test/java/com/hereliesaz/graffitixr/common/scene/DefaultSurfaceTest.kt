package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSurfaceTest {
    private fun group(p: SurfaceProfile?) = Layer(id = "g", name = "g", type = LayerType.GROUP, surface = p)

    private fun pivot(w: Float, h: Float): FloatArray {
        val p = DefaultSurface.profile(DefaultSurface.rect(w, h), w, h, emptyList())!!
        return OrbitPivot.ofSurfaces(listOf(group(p)), w, h)!!
    }

    @Test
    fun `portrait is 3 over 4 of the width by 2 over 3 of the height, centred`() {
        val r = DefaultSurface.rect(1000f, 1500f)!!
        assertEquals(750f, r.width, 0.01f)
        assertEquals(1000f, r.height, 0.01f)
        assertEquals(125f, r.x, 0.01f)
        assertEquals(250f, r.y, 0.01f)
    }

    @Test
    fun `landscape is 2 over 3 of the width by 3 over 4 of the height, centred`() {
        val r = DefaultSurface.rect(1500f, 1000f)!!
        assertEquals(1000f, r.width, 0.01f)
        assertEquals(750f, r.height, 0.01f)
        assertEquals(250f, r.x, 0.01f)
        assertEquals(125f, r.y, 0.01f)
    }

    @Test
    fun `either way it is 3 over 4 of the short side by 2 over 3 of the long side`() {
        for ((w, h) in listOf(900f to 2100f, 2100f to 900f)) {
            val r = DefaultSurface.rect(w, h)!!
            val shortSide = if (w < h) r.width else r.height
            val longSide = if (w < h) r.height else r.width
            assertEquals(0.75f * minOf(w, h), shortSide, 0.01f)
            assertEquals(2f / 3f * maxOf(w, h), longSide, 0.01f)
        }
    }

    @Test
    fun `a square counts as portrait`() {
        val r = DefaultSurface.rect(600f, 600f)!!
        assertEquals(450f, r.width, 0.01f)
        assertEquals(400f, r.height, 0.01f)
    }

    @Test
    fun `zoom shrinks the sheet to the same share of what is on screen, about the given centre`() {
        val r = DefaultSurface.rect(1000f, 1600f, zoom = 2f, centerX = 200f, centerY = 300f)!!
        assertEquals(375f, r.width, 0.01f)
        assertEquals(1600f * 2f / 3f / 2f, r.height, 0.01f)
        assertEquals(200f, r.x + r.width / 2f, 0.01f)
        assertEquals(300f, r.y + r.height / 2f, 0.01f)
    }

    @Test
    fun `degenerate sizes give nothing`() {
        assertNull(DefaultSurface.rect(0f, 100f))
        assertNull(DefaultSurface.rect(100f, 0f))
        assertNull(DefaultSurface.rect(-1f, 100f))
        assertNull(DefaultSurface.rect(Float.NaN, 100f))
        assertNull(DefaultSurface.rect(Float.POSITIVE_INFINITY, 100f))
        assertNull(DefaultSurface.rect(100f, 100f, zoom = 0f))
        assertNull(DefaultSurface.profile(null, 100f, 100f, emptyList()))
    }

    @Test
    fun `the profile is a valid flat sheet in the plane z = 0, bounded to the rectangle`() {
        val p = DefaultSurface.profile(DefaultSurface.rect(1000f, 1500f), 1000f, 1500f, emptyList())
        assertNotNull(p)
        p!!
        assertTrue(p.isValid && p.isFlat)
        assertEquals(125f, p.point(0)[0], 0.01f)
        assertEquals(875f, p.point(1)[0], 0.01f)
        assertEquals(0f, p.point(0)[2], 0f)
        assertEquals(1f, p.sweepY, 1e-4f)
        assertEquals(1000f, p.reach!!, 0.01f)
    }

    @Test
    fun `the orbit pivot is the sheet's centre, portrait`() {
        val c = pivot(1000f, 1600f)
        assertEquals(500f, c[0], 0.01f)
        assertEquals(800f, c[1], 0.01f)
        assertEquals(0f, c[2], 0.01f)
    }

    @Test
    fun `the orbit pivot is the sheet's centre, landscape`() {
        val c = pivot(2000f, 1000f)
        assertEquals(1000f, c[0], 0.01f)
        assertEquals(500f, c[1], 0.01f)
    }

    @Test
    fun `the surface's box is the rectangle, so the grid reaches only its height plus the margin`() {
        val p = DefaultSurface.profile(DefaultSurface.rect(1000f, 1600f), 1000f, 1600f, emptyList())!!
        val (lo, hi) = SurfaceMath.localBounds(p)!!
        assertEquals(125f, lo[0], 0.01f)
        assertEquals(875f, hi[0], 0.01f)
        assertEquals(267f - 0.33f, lo[1], 0.5f)
        assertEquals(267f + 1066.67f, hi[1], 0.5f)
    }

    @Test
    fun `a posed group's profile is in its own box space, still bounded to the sheet`() {
        val chain = listOf(PlanePose(scale = 2f))
        val p = DefaultSurface.profile(DefaultSurface.rect(1000f, 1600f), 1000f, 1600f, chain)
        assertNotNull(p)
        // Scaled 2x about the canvas centre, the box is half the size, so the reach in box units halves too.
        assertEquals(1066.67f / 2f, p!!.reach!!, 0.5f)
    }

    private fun sheetWithChild(w: Float, h: Float): Pair<List<Layer>, SurfaceProfile> {
        val profile = DefaultSurface.profile(DefaultSurface.rect(w, h), w, h, emptyList())!!
        val layers = listOf(group(profile), Layer(id = "c", name = "c", parentId = "g"))
        return layers to profile
    }

    @Test
    fun `with a full-canvas layer painted on it the mesh is exactly the sheet`() {
        for ((w, h) in listOf(1000f to 1600f, 2000f to 1000f)) {
            val (_, p) = sheetWithChild(w, h)
            val mesh = SurfaceMath.mesh(p, 0f, w, 0f, h)
            val ts = (0 until mesh.size / 5).map { mesh[it * 5 + 4] }
            val us = (0 until mesh.size / 5).map { mesh[it * 5 + 3] }
            assertEquals(p.reach!!, ts.max(), 0.01f)
            assertEquals(p.length, us.max(), 0.01f)
        }
    }

    @Test
    fun `the rendered reach and the visibility box stop at the sheet, and the pivot stays its centre`() {
        for ((w, h) in listOf(1000f to 1600f, 2000f to 1000f)) {
            val (layers, p) = sheetWithChild(w, h)
            val byId = layers.associateBy { it.id }
            val reach = SurfaceVisibility.renderedReach(layers, byId, layers[0], w, h) { floatArrayOf(0f, 0f, w, h) }
            assertEquals(p.reach!!, reach!!, 0.01f)
            val c = OrbitPivot.ofSurfaces(layers, w, h)!!
            val r = DefaultSurface.rect(w, h)!!
            assertEquals(r.x + r.width / 2f, c[0], 0.01f)
            assertEquals(r.y + r.height / 2f, c[1], 0.01f)
        }
    }

    @Test
    fun `a touch below the sheet misses it and one on it hits`() {
        val (_, p) = sheetWithChild(1000f, 1600f)
        val cam = SceneMath.frame(SceneCamera(), 1000f, 1600f)
        val model = SceneMath.chainModel(emptyList(), 1000f, 1600f)
        assertNotNull(SurfaceMath.hit(500f, 800f, p, model, cam))
        assertNull(SurfaceMath.hit(500f, 1500f, p, model, cam))
        assertNull(SurfaceMath.hit(100f, 800f, p, model, cam))
    }

    @Test
    fun `a touch that grazes the sheet's side below it paints nowhere, and one beside the sheet still maps`() {
        val (_, p) = sheetWithChild(1000f, 1600f)
        val cam = SceneMath.frame(SceneCamera(), 1000f, 1600f)
        val model = SceneMath.chainModel(emptyList(), 1000f, 1600f)
        // The sheet is x 125..875, y 267..1333. Just left of its side but well below its bottom edge: out of reach.
        assertNull(SurfaceFootprint.hit(130f, 1500f, 20f, p, model, cam))
        // Just beside the side, level with the sheet: the footprint maps to the side edge, within the sheet's t.
        val beside = SurfaceFootprint.hit(115f, 800f, 20f, p, model, cam)
        assertNotNull(beside)
        assertTrue(beside!!.t <= p.reach!! + 0.01f)
        // Just under the bottom edge, within the footprint: clamped to the far edge, not past it.
        val under = SurfaceFootprint.hit(500f, 1345f, 20f, p, model, cam)
        assertNotNull(under)
        assertEquals(p.reach!!, under!!.t, 0.5f)
    }

    @Test
    fun `a layer drawn through the sheet's plane is cropped to the sheet`() {
        val (layers, p) = sheetWithChild(1000f, 1600f)
        val crop = SurfaceMath.flatSurfaceCrops(layers, 1000f, 1600f)["c"]!!
        assertEquals(listOf(0f to 0f, p.length to 0f, p.length to p.reach!!, 0f to p.reach!!), crop)
        // A near-plane polygon covering far more is cut down to the sheet, either winding.
        val big = listOf(-5000f to -5000f, 5000f to -5000f, 5000f to 5000f, -5000f to 5000f)
        for (c in listOf(crop, crop.reversed())) {
            val cut = SceneMath.clipConvex(big, c)
            assertEquals(0f, cut.minOf { it.first }, 0.01f)
            assertEquals(p.length, cut.maxOf { it.first }, 0.01f)
            assertEquals(p.reach!!, cut.maxOf { it.second }, 0.01f)
        }
        assertTrue(SceneMath.clipConvex(listOf(2000f to 2000f, 2100f to 2000f, 2100f to 2100f), crop).isEmpty())
    }
}
