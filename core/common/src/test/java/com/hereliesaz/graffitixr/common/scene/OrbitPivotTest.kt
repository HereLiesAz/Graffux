@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitPivotTest {
    private val w = 1000f
    private val h = 1600f
    private val eps = 0.05f

    private fun layer(id: String, z: Float = 0f, ry: Float = 0f) =
        Layer(id = id, name = id, positionZ = z, rotationY = ry)

    private fun assertPoint(x: Float, y: Float, z: Float, p: FloatArray) {
        assertEquals("x", x, p[0], eps)
        assertEquals("y", y, p[1], eps)
        assertEquals("z", z, p[2], eps)
    }

    @Test
    fun `the assumed depth is the layer's longer side`() {
        assertEquals(1600f, OrbitPivot.assumedDepth(1000f, 1600f), 0f)
        assertEquals(1000f, OrbitPivot.assumedDepth(1000f, 600f), 0f)
    }

    @Test
    fun `a lone flat layer's pivot is its centre, half its depth behind it`() {
        // Behind is away from the viewer: +z.
        assertPoint(500f, 800f, 800f, OrbitPivot.of(listOf(layer("a")), w, h))
    }

    @Test
    fun `a lone layer's pivot rides with its pose so a turn about it cannot move it`() {
        val before = layer("a", z = 40f)
        val p0 = OrbitPivot.of(listOf(before), w, h)
        assertPoint(500f, 800f, 840f, p0)
        val pose = SceneMath.rotatePoseAbout(
            before.planePose, com.hereliesaz.graffitixr.common.model.RotationAxis.X, 33f, p0, w, h,
        )
        val after = before.copy(
            offset = androidx.compose.ui.geometry.Offset(pose.offsetX, pose.offsetY), positionZ = pose.positionZ,
            rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ,
        )
        val p1 = OrbitPivot.of(listOf(after), w, h)
        for (i in 0..2) assertEquals(p0[i], p1[i], 0.1f)
    }

    @Test
    fun `a second layer in a perpendicular plane moves the pivot to the centre of the union`() {
        // Layer b stands edge-on (turned 90 degrees about Y) at depth 300: x collapses to 500,
        // z spans 300 +- 500 = -200..800. The union with layer a: x 0..1000, y 0..1600, z -200..800.
        val pivot = OrbitPivot.of(listOf(layer("a"), layer("b", z = 300f, ry = 90f)), w, h)
        assertPoint(500f, 800f, 300f, pivot)
    }

    @Test
    fun `flat layers in parallel planes leave the depth to layer 1's assumed box`() {
        assertPoint(500f, 800f, 800f, OrbitPivot.of(listOf(layer("a"), layer("b")), w, h))
    }

    // ── Surfaces: the pivot follows ALL of them ──

    private fun group(id: String, vararg pts: Float, parent: String? = null) = Layer(
        id = id, name = id, type = com.hereliesaz.graffitixr.common.model.LayerType.GROUP, parentId = parent,
        surface = SurfaceProfile(pts.toList(), 0f, 0f, 1f),
    )

    private val floor = group("floor", 200f, 1000f, 0f, 800f, 1000f, 0f)
    private val wall = group("wall", 500f, 200f, 0f, 500f, 1000f, 0f)
    private val post = group("post", 900f, 100f, 0f, 900f, 300f, 0f)

    @Test
    fun `one surface's pivot is the centre of its own box, not of the picture plane`() {
        // x 200..800, y flat at 1000, z swept 0..600 (its width): (500, 1000, 300).
        val paint = Layer(id = "paint", name = "paint", parentId = "floor")
        assertPoint(500f, 1000f, 300f, OrbitPivot.of(listOf(floor, paint), w, h))
    }

    @Test
    fun `two then three surfaces recompute the pivot from the union of all`() {
        assertPoint(500f, 600f, 400f, OrbitPivot.of(listOf(floor, wall), w, h))
        assertPoint(550f, 550f, 400f, OrbitPivot.of(listOf(floor, wall, post), w, h))
    }

    @Test
    fun `removing a surface (undo) returns the earlier pivot, and none falls back to the plane rule`() {
        assertPoint(500f, 600f, 400f, OrbitPivot.of(listOf(floor, wall, post).dropLast(1), w, h))
        assertPoint(500f, 800f, 800f, OrbitPivot.of(listOf(Layer(id = "a", name = "a")), w, h))
        assertPoint(500f, 800f, 800f, OrbitPivot.of(emptyList(), w, h))
    }

    @Test
    fun `resolve is the derived pivot, with nothing stored to override it`() {
        val layers = listOf(floor, wall)
        assertPoint(500f, 600f, 400f, OrbitPivot.resolve(layers, w, h))
        assertPoint(500f, 600f, 400f, OrbitPivot.of(layers, w, h))
    }

    private fun turned(vararg steps: Pair<Char, Float>): SceneCamera {
        var cam = SceneCamera()
        val pivot = floatArrayOf(500f, 600f, 400f)
        for ((axis, a) in steps) {
            val (yaw, pitch) = if (axis == 'X') 0f to a else a to 0f
            cam = SceneMath.orbitAbout(cam, pivot, yaw, pitch, w, h)
        }
        return cam
    }

    @Test
    fun `X then Y then X more equals X by the sum with Y applied, whatever the interleaving`() {
        val a = turned('X' to 0.2f, 'Y' to 0.5f, 'X' to 0.3f)
        val b = turned('Y' to 0.5f, 'X' to 0.5f)
        val c = turned('X' to 0.1f, 'X' to 0.4f, 'Y' to 0.25f, 'Y' to 0.25f)
        for (cam in listOf(a, b, c)) {
            assertEquals(0.5f, cam.yaw, 1e-5f)
            assertEquals(0.5f, cam.pitch, 1e-5f)
        }
        // The pivot stays where it was on screen through every one of them.
        val before = SceneMath.frame(SceneCamera(), w, h).project(floatArrayOf(500f, 600f, 400f))!!
        for (cam in listOf(a, b, c)) {
            val after = SceneMath.frame(cam, w, h).project(floatArrayOf(500f, 600f, 400f))!!
            assertEquals(before.first, after.first, 0.1f)
            assertEquals(before.second, after.second, 0.1f)
        }
    }

    @Test
    fun `the X turn keeps its own axis after a Y turn - the screen's horizontal`() {
        val y = turned('Y' to 0.7f)
        val yx = turned('Y' to 0.7f, 'X' to 0.4f)
        val r0 = SceneMath.frame(y, w, h).right
        val r1 = SceneMath.frame(yx, w, h).right
        for (i in 0..2) assertEquals(r0[i], r1[i], 1e-5f)
    }
}
