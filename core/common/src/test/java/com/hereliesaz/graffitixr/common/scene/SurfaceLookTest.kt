@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** How a 3D surface looks (spec/3d-space.md, "How the surface looks"): fill and mesh colour, fill geometry. */
class SurfaceLookTest {
    private val w = 1000f
    private val h = 800f
    private val cam = SceneMath.frame(SceneCamera(yaw = 0.4f, pitch = -0.3f), w, h)
    private val model = SceneMath.chainModel(listOf(PlanePose()), w, h)

    private fun stroke(n: Int = 40) = (0..n).map { (200f + 600f * it / n) to 600f }
    private val sheet = SurfaceMath.capture(stroke(), SceneCamera(), w, h, listOf(PlanePose()))!!

    private val canvas = 0xFFF5F0E6.toInt()
    private val workspace = 0xFF2B2B2B.toInt()

    private fun signed(t: FloatArray) = (t[2] - t[0]) * (t[5] - t[1]) - (t[4] - t[0]) * (t[3] - t[1])

    private fun inside(t: FloatArray, x: Float, y: Float, eps: Float = 0.5f): Boolean {
        fun side(ax: Float, ay: Float, bx: Float, by: Float) = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        val d1 = side(t[0], t[1], t[2], t[3])
        val d2 = side(t[2], t[3], t[4], t[5])
        val d3 = side(t[4], t[5], t[0], t[1])
        return (d1 >= -eps && d2 >= -eps && d3 >= -eps) || (d1 <= eps && d2 <= eps && d3 <= eps)
    }

    @Test
    fun `the fill is the canvas colour and swapping canvas and workspace changes it`() {
        assertEquals(canvas, SurfaceLook.fill(canvas, workspace)!!.argb)
        assertTrue(SurfaceLook.fill(canvas, workspace)!!.edgeVisible)
        // Fed the workspace colour as the canvas (a mix-up) the result differs, and its edge is lost against it.
        assertEquals(workspace, SurfaceLook.fill(workspace, workspace)!!.argb)
        assertFalse(SurfaceLook.fill(workspace, workspace)!!.edgeVisible)
        assertTrue(SurfaceLook.fill(workspace, canvas)!!.argb != SurfaceLook.fill(canvas, workspace)!!.argb)
    }

    @Test
    fun `a see-through or translucent canvas has no surface fill`() {
        assertNull(SurfaceLook.fill(0x00FFFFFF, workspace))
        assertNull(SurfaceLook.fill(0x80F5F0E6.toInt(), workspace))
    }

    @Test
    fun `the mesh is the grid colour only while the grid is on`() {
        assertEquals(SurfaceLook.GRID_ARGB, SurfaceLook.meshArgb(true))
        assertNull(SurfaceLook.meshArgb(false))
    }

    @Test
    fun `every mesh line end lies inside the fill`() {
        val tris = SurfaceMath.fillTriangles(sheet, model, cam)
        assertTrue(tris.isNotEmpty())
        assertTrue(tris.all { signed(it) >= 0f }) // consistently oriented
        assertTrue(tris.sumOf { abs(signed(it)).toDouble() } > 1000.0)
        val lines = SurfaceMath.gridLines(sheet, model, cam)
        assertTrue(lines.isNotEmpty())
        for (l in lines) {
            assertTrue("start ${l.toList()}", tris.any { inside(it, l[0], l[1]) })
            assertTrue("end ${l.toList()}", tris.any { inside(it, l[2], l[3]) })
        }
    }

    @Test
    fun `a surface crossing the near plane keeps its visible part instead of vanishing`() {
        // Camera inside the sheet's reach: the far end of the sweep runs behind the eye's near plane.
        val close = SceneMath.frame(SceneCamera(distanceScale = 0.15f, pitch = -1.0f), w, h)
        val lines = SurfaceMath.gridLines(sheet, model, close)
        val tris = SurfaceMath.fillTriangles(sheet, model, close)
        assertTrue(lines.isNotEmpty())
        assertTrue("mesh remains so the fill must", tris.isNotEmpty())
        assertTrue(tris.all { t -> t.all { it.isFinite() } })
    }

    @Test
    fun `clipping to the near plane keeps the part in front and drops one fully behind`() {
        val near = 10f
        fun pt(x: Float, z: Float) = floatArrayOf(x, 0f, z)
        val crossing = SurfaceMath.clipNear(listOf(pt(0f, 20f), pt(10f, 20f), pt(5f, 0f)), near)
        assertTrue(crossing.size >= 3)
        assertTrue(crossing.all { it[2] >= near - 1e-4f })
        // The apex is cut: the kept part is a quad from z = 20 down to z = near, at the right x.
        assertTrue(crossing.any { abs(it[2] - near) < 1e-3f })
        assertEquals(4, crossing.size)
        val behind = SurfaceMath.clipNear(listOf(pt(0f, 1f), pt(10f, 2f), pt(5f, 3f)), near)
        assertTrue(behind.isEmpty())
        val front = SurfaceMath.clipNear(listOf(pt(0f, 20f), pt(10f, 20f), pt(5f, 30f)), near)
        assertEquals(3, front.size)
    }

    @Test
    fun `an invalid profile has no fill`() {
        assertTrue(SurfaceMath.fillTriangles(SurfaceProfile(emptyList(), 0f, 0f, 1f), model, cam).isEmpty())
    }

    @Test
    fun `a multi-view hull is a solid and has no sheet fill`() {
        fun box(a: Float) = listOf(0f to 0f, a to 0f, a to a, 0f to a)
        fun view(sweep: FloatArray, place: (Float, Float) -> FloatArray) =
            SurfaceProfile(box(100f).flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])
        val front = view(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }
        val hull = front.copy(side = view(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z) })
        assertNotNull(hull)
        assertTrue(SurfaceMath.fillTriangles(hull, model, cam).isEmpty())
    }
}
