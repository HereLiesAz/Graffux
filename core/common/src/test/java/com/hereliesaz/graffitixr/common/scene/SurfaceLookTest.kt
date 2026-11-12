@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** How a 3D surface looks (spec/3d-space.md, "How the surface looks"): fill and mesh colour, fill geometry. */
class SurfaceLookTest {
    private val w = 1000f
    private val h = 800f
    private val camera = SceneCamera(yaw = 0.4f, pitch = -0.3f)
    private val cam = SceneMath.frame(camera, w, h)

    private fun stroke(n: Int = 40) = (0..n).map { (200f + 600f * it / n) to 600f }
    private val sheet = SurfaceMath.capture(stroke(), SceneCamera(), w, h, listOf(PlanePose()))!!

    private fun area(t: FloatArray) = abs((t[2] - t[0]) * (t[5] - t[1]) - (t[4] - t[0]) * (t[3] - t[1])) / 2f

    @Test
    fun `the fill is the canvas background, not the workspace`() {
        val canvas = 0xFFF5F0E6.toInt()
        assertEquals(canvas, SurfaceLook.fillArgb(canvas))
        assertTrue(SurfaceLook.fillArgb(canvas) != 0xFF2B2B2B.toInt())
    }

    @Test
    fun `a transparent canvas has no fill`() {
        assertNull(SurfaceLook.fillArgb(0x00FFFFFF))
    }

    @Test
    fun `the mesh is the grid colour only while the grid is on`() {
        assertEquals(SurfaceLook.GRID_ARGB, SurfaceLook.meshArgb(true))
        assertEquals(0x55112233, SurfaceLook.meshArgb(true, 0x55112233))
        assertNull(SurfaceLook.meshArgb(false))
    }

    @Test
    fun `the fill covers the surface the mesh draws`() {
        val model = SceneMath.chainModel(listOf(PlanePose()), w, h)
        val tris = SurfaceMath.fillTriangles(sheet, model, cam)
        assertTrue(tris.isNotEmpty())
        assertTrue(tris.all { t -> t.all { it.isFinite() } && area(t) >= 0f })
        assertTrue(tris.sumOf { area(it).toDouble() } > 1000.0)
        // Every mesh line end lies within the fill's screen bounds (same extent).
        val xs = tris.flatMap { listOf(it[0], it[2], it[4]) }
        val ys = tris.flatMap { listOf(it[1], it[3], it[5]) }
        for (l in SurfaceMath.gridLines(sheet, model, cam)) {
            assertTrue(l[0] in xs.min() - 1f..xs.max() + 1f && l[1] in ys.min() - 1f..ys.max() + 1f)
        }
    }

    @Test
    fun `an invalid profile has no fill`() {
        val model = SceneMath.chainModel(listOf(PlanePose()), w, h)
        assertTrue(SurfaceMath.fillTriangles(SurfaceProfile(emptyList(), 0f, 0f, 1f), model, cam).isEmpty())
    }

    @Test
    fun `a multi-view hull is a solid and has no sheet fill`() {
        fun box(a: Float) = listOf(0f to 0f, a to 0f, a to a, 0f to a)
        fun view(sweep: FloatArray, place: (Float, Float) -> FloatArray) =
            SurfaceProfile(box(100f).flatMap { place(it.first, it.second).toList() }, sweep[0], sweep[1], sweep[2])
        val front = view(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }
        val hull = front.copy(side = view(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z) })
        val model = SceneMath.chainModel(listOf(PlanePose()), w, h)
        assertTrue(SurfaceMath.fillTriangles(hull, model, cam).isEmpty())
    }
}
