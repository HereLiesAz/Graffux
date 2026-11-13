@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a surface crossing the near plane keeps every mesh line end inside its fill`() {
        val close = SceneMath.frame(SceneCamera(distanceScale = 0.15f, pitch = -1.0f), w, h)
        val lines = SurfaceMath.gridLines(sheet, model, close)
        val tris = SurfaceMath.fillTriangles(sheet, model, close)
        assertTrue(lines.isNotEmpty() && tris.isNotEmpty())
        // The old drop-the-strip code lost the strip whose far end crossed the near plane, leaving mesh ends bare.
        for (l in lines) {
            assertTrue("start ${l.toList()}", tris.any { inside(it, l[0], l[1], 1f) })
            assertTrue("end ${l.toList()}", tris.any { inside(it, l[2], l[3], 1f) })
        }
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
        // Area (in the x-z plane) is the visible fraction: the apex at z = 0 is cut off below z = near,
        // leaving 75 of the triangle's 100.
        fun area(poly: List<FloatArray>) = abs(poly.indices.sumOf { i ->
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            (a[0] * b[2] - b[0] * a[2]).toDouble()
        }) / 2.0
        assertEquals(75.0, area(crossing), 1e-3)
        assertEquals(100.0, area(listOf(pt(0f, 20f), pt(10f, 20f), pt(5f, 0f))), 1e-3)
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
        assertTrue(SurfaceMath.fillTriangles(hull, model, cam).isEmpty())
    }

    private val straight = SurfaceProfile(listOf(0f, 0f, 0f, 200f, 0f, 0f), sweepX = 0f, sweepY = 1f, sweepZ = 0f)
    private val shapes = listOf(VectorShape(ShapeKind.RECTANGLE))
    private fun group(id: String) = Layer(id, id, type = LayerType.GROUP, surface = straight)
    private fun paint(id: String, parent: String) = Layer(id, id, parentId = parent, shapes = shapes)

    @Test
    fun `on the 2D path no fill is emitted before a depth pass, every fill sits in the stack`() {
        // No paint on the surface and no 3D ink: no pass at all, the fill sits at its group's place.
        val layers = listOf(group("g"))
        val depth = DepthComposite.plan(layers, SceneCamera())
        val fills = SurfaceMath.fillGroupIds(layers)
        assertEquals(listOf("g"), fills)
        assertTrue(SurfaceMath.passFillGroups(layers, depth, fills).isEmpty())
        // Paint on a surface joins the pass even with the identity camera, so its fill goes before the pass.
        val painted = listOf(group("g"), paint("a", "g"))
        val plan = DepthComposite.plan(painted, SceneCamera())
        assertEquals(listOf("g"), SurfaceMath.passFillGroups(painted, plan, SurfaceMath.fillGroupIds(painted)))
    }

    @Test
    fun `on the scene path the fill of a group the depth pass paints is emitted before the pass, in stack order`() {
        val layers = listOf(
            paint("below", "none").copy(parentId = null), group("g1"), paint("a", "g1"),
            group("empty"), group("g2"), paint("b", "g2"),
        )
        val depth = DepthComposite.plan(layers, SceneCamera(yaw = 0.4f))
        val fills = SurfaceMath.fillGroupIds(layers)
        assertEquals(listOf("g1", "empty", "g2"), fills)
        // g2 comes after the anchor's group but its paint is drawn by the same pass, so it joins g1 ahead of it;
        // a group with no paint in the pass keeps its place in the stack.
        assertEquals(listOf("g1", "g2"), SurfaceMath.passFillGroups(layers, depth, fills))
    }

    @Test
    fun `a group holding only hidden or empty paint keeps its fill in the stack`() {
        val layers = listOf(
            group("g2"), paint("hidden", "g2").copy(isVisible = false),
            group("g3"), Layer("blank", "blank", parentId = "g3"),
            group("g1"), paint("a", "g1"),
        )
        val depth = DepthComposite.plan(layers, SceneCamera(yaw = 0.4f))
        assertTrue("a" in depth.memberIds && "hidden" !in depth.memberIds && "blank" !in depth.memberIds)
        val fills = SurfaceMath.fillGroupIds(layers)
        assertEquals(listOf("g1"), SurfaceMath.passFillGroups(layers, depth, fills))
    }

    @Test
    fun `a nested, hidden or zero-opacity group gets no fill`() {
        val layers = listOf(
            group("top"), group("inner").copy(parentId = "top"),
            group("hidden").copy(isVisible = false), group("zero").copy(opacity = 0f),
        )
        assertEquals(listOf("top"), SurfaceMath.fillGroupIds(layers))
    }

    @Test
    fun `equal fill inputs give an equal key`() {
        fun key() = SurfaceMath.fillKey(sheet.copy(), SceneMath.chainModel(listOf(PlanePose()), w, h), SceneMath.frame(SceneCamera(yaw = 0.4f), w, h))
        assertEquals(key(), key())
        val other = SurfaceMath.fillKey(sheet, SceneMath.chainModel(listOf(PlanePose()), w, h), SceneMath.frame(SceneCamera(yaw = 0.5f), w, h))
        assertTrue(key() != other)
    }
}
