@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The automatic plane meets the nearest edge of the surfaces of every plane that has content (spec/3d-space.md). */
class NearestEdgeTest {
    private val w = 1000f
    private val h = 1600f
    private val eps = 0.05f

    private fun layer(id: String, z: Float = 0f, rx: Float = 0f, ry: Float = 0f) =
        Layer(id = id, name = id, positionZ = z, rotationX = rx, rotationY = ry)

    private fun outlines(layers: List<Layer>, only: Set<String>? = null) =
        NearestEdge.outlines(layers, w, h) { only == null || it.id in only }

    private fun frame(yaw: Float, pitch: Float = 0f) = SceneMath.frame(SceneCamera(yaw = yaw, pitch = pitch), w, h)

    private fun brute(layers: List<Layer>, cam: CameraFrame): Float =
        layers.flatMap { l ->
            val m = SceneMath.chainModel(planeChain(layers, l.id), w, h)
            listOf(0f to 0f, w to 0f, w to h, 0f to h).map { (x, y) -> cam.toCamera(m.apply(x, y, 0f))[2] }
        }.min()

    @Test
    fun `a flat plane facing the view meets the eye at its own depth`() {
        val cam = frame(0f)
        val l = layer("a", z = 150f)
        val d = NearestEdge.depth(outlines(listOf(l)), cam)!!
        assertEquals(brute(listOf(l), cam), d, eps)
        assertEquals(cam.toCamera(SceneMath.chainModel(planeChain(listOf(l), "a"), w, h).apply(500f, 800f, 0f))[2], d, eps)
    }

    @Test
    fun `a tilted reference seen from several angles gives the depth of its nearest corner`() {
        val l = layer("a", z = 100f, rx = 35f, ry = -20f)
        for ((yaw, pitch) in listOf(0f to 0f, 0.6f to 0f, -1.2f to 0.3f, 0.2f to -0.8f, 2.5f to 0.1f)) {
            val cam = frame(yaw, pitch)
            val d = NearestEdge.depth(outlines(listOf(l)), cam)
            assertNotNull("yaw $yaw pitch $pitch", d)
            assertEquals("yaw $yaw pitch $pitch", brute(listOf(l), cam), d!!, eps)
        }
    }

    @Test
    fun `an edge-on reference still has a nearest edge, not the pivot`() {
        val l = layer("a")
        val cam = frame((Math.PI / 2).toFloat())
        val d = NearestEdge.depth(outlines(listOf(l)), cam)!!
        assertEquals(brute(listOf(l), cam), d, eps)
        val pivotDepth = cam.toCamera(floatArrayOf(w / 2f, h / 2f, 800f))[2]
        assertTrue("the edge is nearer than the pivot", d < pivotDepth - 1f)
    }

    @Test
    fun `no plane with content has no depth, and an empty plane is not a reference`() {
        val cam = frame(0f)
        assertNull(NearestEdge.depth(outlines(emptyList()), cam))
        assertNull(NearestEdge.depth(outlines(listOf(layer("a")), only = emptySet()), cam))
    }

    @Test
    fun `with three planes the nearest edge is the first plane's, then the second's`() {
        val cam = frame(0f)
        val firstNearest = listOf(layer("a", z = -300f), layer("b", z = 0f), layer("c", z = 200f))
        val secondNearest = listOf(layer("a", z = 0f), layer("b", z = -300f), layer("c", z = 200f))
        val da = NearestEdge.depth(outlines(firstNearest), cam)!!
        val db = NearestEdge.depth(outlines(secondNearest), cam)!!
        assertEquals(brute(firstNearest, cam), da, eps)
        assertEquals(brute(secondNearest, cam), db, eps)
        // The last plane alone (the superseded rule) would say something else in both.
        val lastOnlyA = NearestEdge.depth(outlines(firstNearest, setOf("c")), cam)!!
        val lastOnlyB = NearestEdge.depth(outlines(secondNearest, setOf("c")), cam)!!
        assertTrue("first plane is nearer than the last", da < lastOnlyA - 100f)
        assertTrue("second plane is nearer than the last", db < lastOnlyB - 100f)
        assertEquals(da, NearestEdge.depth(outlines(firstNearest, setOf("a")), cam)!!, eps)
        assertEquals(db, NearestEdge.depth(outlines(secondNearest, setOf("b")), cam)!!, eps)
    }

    @Test
    fun `a hidden plane and paint on a surface are not references`() {
        val cam = frame(0f)
        val hidden = layer("a", z = -400f).copy(isVisible = false)
        val shown = layer("b", z = 0f)
        assertEquals(brute(listOf(shown), cam), NearestEdge.depth(outlines(listOf(hidden, shown)), cam)!!, eps)
    }

    @Test
    fun `a surface contributes its outline`() {
        val wall = SurfaceProfile(listOf(0f, 0f, 0f, 400f, 0f, 0f), reach = 300f)
        val group = Layer(id = "G", name = "G", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = wall)
        val paint = Layer(id = "P", name = "P", parentId = "G")
        val cam = frame(0f)
        val list = listOf(group, paint)
        val o = NearestEdge.outlines(list, w, h) { true }
        assertEquals("the group's surface only; its paint stands for it", 1, o.size)
        assertNotNull(NearestEdge.depth(o, cam))
        val empty = NearestEdge.outlines(list, w, h) { false }
        assertEquals("a surface with no paint is not a reference", 0, empty.size)
    }

    @Test
    fun `viewPlanePose with a depth sits at that depth and keeps the box on screen where it was`() {
        val camera = SceneCamera(yaw = 0.7f, pitch = 0.2f)
        val pivot = floatArrayOf(500f, 800f, 800f)
        val cam = SceneMath.frame(camera, w, h)
        val base = SceneMath.viewPlanePose(camera, w, h, emptyList(), pivot)!!
        val moved = SceneMath.viewPlanePose(camera, w, h, emptyList(), pivot, 900f)!!
        assertEquals(900f / cam.focal, moved.scale, 1e-4f)
        val c0 = SceneMath.model(base, w, h).apply(w / 2f, h / 2f, 0f)
        val c1 = SceneMath.model(moved, w, h).apply(w / 2f, h / 2f, 0f)
        assertEquals(900f, cam.toCamera(c1)[2], eps)
        val p0 = cam.project(c0)!!
        val p1 = cam.project(c1)!!
        assertEquals(p0.first, p1.first, 0.01f)
        assertEquals(p0.second, p1.second, 0.01f)
        // Without a depth the pose is exactly what it was.
        assertEquals(base, SceneMath.viewPlanePose(camera, w, h, emptyList(), pivot, null)!!)
    }

    private fun reliefScene(): List<Layer> {
        val depth = FloatArray(16) { (it % 4) * 1f + (it / 4) * 0.5f }
        val relief = ReliefSurface.build(depth, 4, 4, 100, 100, 0f, 0f, 400f, 400f)!!
        val group = Layer(id = "R", name = "Relief", type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, surface = relief.toProfile())
        return listOf(group, Layer(id = "P", name = "Photo", parentId = "R"))
    }

    @Test
    fun `an extracted relief is a reference by its displaced boundary, photo and all`() {
        val layers = reliefScene()
        val relief = layers[0].surface!!.relief!!
        val cam = frame(0.4f, 0.2f)
        val d = NearestEdge.depth(NearestEdge.outlines(layers, w, h) { it.id == "P" }, cam)!!
        val expected = (0 until relief.cols).flatMap { i -> listOf(0, relief.rows - 1).map { j -> i to j } }
            .plus((0 until relief.rows).flatMap { j -> listOf(0, relief.cols - 1).map { i -> i to j } })
            .minOf { (i, j) -> cam.toCamera(floatArrayOf(relief.vertexU(i), relief.vertexT(j), relief.z(i, j)).let { SceneMath.chainModel(emptyList(), w, h).apply(it[0], it[1], it[2]) })[2] }
        assertEquals(expected, d, eps)
    }

    @Test
    fun `a hidden relief is not a reference, and a visible source photo beside it counts too`() {
        val cam = frame(0f)
        val layers = reliefScene()
        val hidden = layers.map { if (it.id == "R") it.copy(isVisible = false) else it }
        assertNull(NearestEdge.depth(NearestEdge.outlines(hidden, w, h) { true }, cam))
        val photo = layer("S", z = -200f)
        val both = NearestEdge.depth(NearestEdge.outlines(layers + photo, w, h) { true }, cam)!!
        val reliefOnly = NearestEdge.depth(NearestEdge.outlines(layers, w, h) { true }, cam)!!
        val photoOnly = NearestEdge.depth(NearestEdge.outlines(listOf(photo), w, h) { true }, cam)!!
        assertEquals(minOf(reliefOnly, photoOnly), both, eps)
    }
}
