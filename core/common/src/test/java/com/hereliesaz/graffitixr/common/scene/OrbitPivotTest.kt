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
}
