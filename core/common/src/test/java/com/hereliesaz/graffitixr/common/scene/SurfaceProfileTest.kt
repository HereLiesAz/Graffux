@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Group surfaces (spec/3d-space.md, "Group surfaces"): capture, ray hits, arc-length paint. */
class SurfaceProfileTest {
    private val w = 1000f
    private val h = 800f
    private val camera = SceneCamera()
    private val identity = listOf(PlanePose())

    private fun line(from: Pair<Float, Float>, to: Pair<Float, Float>, n: Int = 40) = (0..n).map {
        val f = it / n.toFloat()
        (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f)
    }

    private fun arc(r: Float = 200f, n: Int = 90) = (0..n).map {
        val a = PI.toFloat() * it / n
        (500f + r * cos(a)) to (500f - r * sin(a))
    }

    private val ell = line(200f to 200f, 200f to 600f) + line(200f to 600f, 600f to 600f).drop(1)

    @Test
    fun `a straight stroke captures a flat plane swept away from the viewer`() {
        val p = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        assertEquals(2, p.count)
        assertTrue(p.isFlat)
        assertEquals(600f, p.length, 0.5f)
        assertEquals(0f, p.point(0)[2], 1e-3f)
        assertEquals(1f, p.sweepZ, 1e-5f)
    }

    @Test
    fun `a curved stroke keeps its bend and its arc length`() {
        val p = SurfaceMath.capture(arc(), camera, w, h, identity)!!
        assertFalse(p.isFlat)
        assertTrue(p.count in 4..SurfaceMath.MAX_POINTS)
        assertEquals(PI.toFloat() * 200f, p.length, 4f)
    }

    @Test
    fun `an L-shaped stroke is two legs and a corner`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        assertEquals(3, p.count)
        assertEquals(200f, p.point(1)[0], 0.5f)
        assertEquals(600f, p.point(1)[1], 0.5f)
        assertEquals(800f, p.length, 1f)
    }

    @Test
    fun `a tap is no profile`() {
        assertNull(SurfaceMath.capture(listOf(10f to 10f, 10.5f to 10f), camera, w, h, identity))
    }

    @Test
    fun `a ray meets a curved surface where it shows`() {
        val p = SurfaceMath.capture(arc(), camera, w, h, identity)!!
        val cam = SceneMath.frame(camera, w, h)
        for (touch in listOf(400f to 380f, 560f to 340f, 470f to 370f)) {
            val hit = SurfaceMath.hit(touch.first, touch.second, p, Affine3.IDENTITY, cam)
            assertNotNull("touch $touch", hit)
            val shown = cam.project(p.at(hit!!.u, hit.t))!!
            assertEquals(touch.first, shown.first, 0.5f)
            assertEquals(touch.second, shown.second, 0.5f)
            assertTrue(hit.t > 0f)
        }
    }

    @Test
    fun `paint maps by arc length, so the second leg starts where the first ends`() {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = SurfaceMath.capture(ell, camera, w, h, identity))
        val child = Layer("c", "C", parentId = "g")
        val owner = SurfaceMath.ownerOf(listOf(group, child), "c")!!
        val onLeg = SurfaceMath.worldToFlat(400f, 600f, owner, camera, w, h)!!
        assertEquals(600f, onLeg.first, 1f)
        assertEquals(0f, onLeg.second, 1f)
        val back = SurfaceMath.flatToWorld(onLeg.first, onLeg.second, owner, camera, w, h)!!
        assertEquals(400f, back.first, 0.5f)
        assertEquals(600f, back.second, 0.5f)
    }

    @Test
    fun `a ray that misses the surface has no point`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        val group = Layer("g", "G", type = LayerType.GROUP, surface = floor)
        val owner = SurfaceMath.ownerOf(listOf(group, Layer("c", "C", parentId = "g")), "c")!!
        // Above the horizon line of a floor that recedes from the viewer.
        assertNull(SurfaceMath.worldToFlat(500f, 100f, owner, camera, w, h))
    }

    @Test
    fun `a group's pose moves its surface and every layer on it`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        val moved = Layer("g", "G", type = LayerType.GROUP, surface = floor, offset = androidx.compose.ui.geometry.Offset(0f, -100f))
        val owner = SurfaceMath.ownerOf(listOf(moved, Layer("c", "C", parentId = "g")), "c")!!
        val shown = SurfaceMath.flatToWorld(300f, 0f, owner, camera, w, h)!!
        assertEquals(500f, shown.first, 0.5f)
        assertEquals(500f, shown.second, 0.5f)
    }

    @Test
    fun `2D groups stay on the flat path and out of the depth pass`() {
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP), Layer("c", "C", parentId = "g"))
        assertTrue(SceneRenderPath.isFlat(layers, camera))
        assertTrue(SceneRenderPath.sceneLayerIds(layers, camera).isEmpty())
        assertEquals(DepthComposite.EMPTY, DepthComposite.plan(layers, camera))
        assertNull(SurfaceMath.ownerOf(layers, "c"))
    }

    @Test
    fun `a profiled group takes its layers to the scene path`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("c", "C", parentId = "g"))
        assertFalse(SceneRenderPath.isFlat(layers, camera))
        assertEquals(setOf("c"), DepthComposite.plan(layers, camera).surfaceIds)
    }

    @Test
    fun `an adjustment layer on a surface is drawn in the stack, not dropped`() {
        val wall = SurfaceMath.capture(ell, camera, w, h, identity)
        val adj = Layer(
            "adj", "Light", parentId = "g",
            adjustment = com.hereliesaz.graffitixr.common.photo.PhotoAdjustment(com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT),
        )
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = wall), Layer("c", "C", parentId = "g"), adj)
        val plan = DepthComposite.plan(layers, camera)
        assertFalse("adj" in plan.surfaceIds)
        assertFalse("adj" in plan)
        assertTrue("c" in plan.surfaceIds)
    }

    @Test
    fun `on a flat surface a blend mode or clip keeps the layer on the plane path`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)!!
        assertTrue(floor.isFlat)
        fun layers(surface: SurfaceProfile) = listOf(
            Layer("g", "G", type = LayerType.GROUP, surface = surface),
            Layer("plain", "Plain", parentId = "g"),
            Layer("mult", "Multiply", parentId = "g", blendMode = androidx.compose.ui.graphics.BlendMode.Multiply),
            Layer("clip", "Clip", parentId = "g", clipToLayerBelow = true),
        )
        val flat = DepthComposite.plan(layers(floor), camera)
        assertEquals(setOf("plain"), flat.surfaceIds)
        // A curved surface has no plane to fall back to: those stay on it.
        val curved = DepthComposite.plan(layers(SurfaceMath.capture(ell, camera, w, h, identity)!!), camera)
        assertEquals(setOf("plain", "mult", "clip"), curved.surfaceIds)
    }

    @Test
    fun `animation playback takes the live path for a surface group`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("c", "C", parentId = "g"))
        assertTrue(needsPerspectiveRender(layers, camera))
        assertFalse(needsPerspectiveRender(listOf(Layer("a", "A")), camera))
        assertTrue(needsPerspectiveRender(listOf(Layer("a", "A", rotationX = 10f)), camera))
    }

    @Test
    fun `is3DIn over a prebuilt map agrees with the list form`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(
            Layer("g", "G", type = LayerType.GROUP, surface = floor), Layer("c", "C", parentId = "g"),
            Layer("f", "F"), Layer("t", "T", rotationY = 5f),
        )
        val byId = layers.associateBy { it.id }
        layers.forEach { assertEquals(it.id, it.is3DIn(layers), it.is3DIn(byId)) }
        assertEquals(SurfaceMath.ownerOf(layers, "c")?.group?.id, SurfaceMath.ownerOf(byId, "c")?.group?.id)
    }

    @Test
    fun `strips cover the image's unrolled bounds and stop at the profile's ends`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        val mesh = SurfaceMath.mesh(p, -50f, 900f, 0f, 300f)
        // Two strips (one per leg), two triangles each, five floats a vertex.
        assertEquals(2 * 6 * 5, mesh.size)
        assertEquals(0f, (0 until mesh.size / 5).minOf { mesh[it * 5 + 3] }, 1e-3f)
        assertEquals(p.length, (0 until mesh.size / 5).maxOf { mesh[it * 5 + 3] }, 1e-3f)
    }

    @Test
    fun `glTF export emits a curved group's mesh`() {
        val p = SurfaceMath.capture(arc(), camera, w, h, identity)!!
        val mesh = SurfaceMath.mesh(p, 0f, p.length, 0f, 200f)
        val json = SceneGltfExporter.encode(
            SceneGltfExporter.Scene(w, h, listOf(SceneGltfExporter.Quad("curve", byteArrayOf(1, 2, 3), emptyList(), 1f, mesh)), emptyList(), null),
        )
        val n = mesh.size / 5
        assertTrue(json.contains("\"count\":$n,\"type\":\"VEC3\""))
        assertTrue(n > 6)
    }

    @Test
    fun `only the active layer's group shows its grid, and only while the toggle is on`() {
        val floor = SurfaceMath.capture(line(200f to 600f, 800f to 600f), camera, w, h, identity)
        val layers = listOf(
            Layer("a", "A", type = LayerType.GROUP, surface = floor), Layer("a1", "A1", parentId = "a"),
            Layer("b", "B", type = LayerType.GROUP, surface = floor), Layer("b1", "B1", parentId = "b"),
            Layer("flat", "Flat"),
        )
        assertEquals("a", SurfaceMath.gridGroupId(layers, "a1", visible = true))
        assertEquals("b", SurfaceMath.gridGroupId(layers, "b1", visible = true))
        assertEquals("b", SurfaceMath.gridGroupId(layers, "b", visible = true))
        assertNull(SurfaceMath.gridGroupId(layers, "flat", visible = true))
        assertNull(SurfaceMath.gridGroupId(layers, "a1", visible = false))
    }

    @Test
    fun `the grid follows the surface in perspective`() {
        val p = SurfaceMath.capture(ell, camera, w, h, identity)!!
        val cam = SceneMath.frame(camera, w, h)
        val lines = SurfaceMath.gridLines(p, Affine3.IDENTITY, cam)
        assertTrue(lines.size > 10)
        // The first profile line is the stroke itself; sweep lines run toward the vanishing point.
        val first = lines.first()
        assertEquals(200f, first[0], 0.5f)
        assertEquals(200f, first[1], 0.5f)
        val sweep = lines.last()
        assertEquals(p.length, 800f, 1f)
        assertTrue(kotlin.math.abs(sweep[2] - w / 2f) < kotlin.math.abs(sweep[0] - w / 2f))
        assertTrue(kotlin.math.abs(sweep[3] - h / 2f) < kotlin.math.abs(sweep[1] - h / 2f))
    }
}
