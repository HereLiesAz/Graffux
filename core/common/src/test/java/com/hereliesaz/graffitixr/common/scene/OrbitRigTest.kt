@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.RotationAxis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-finger drag and the rotation triangle turn the view through one step about one pivot. */
class OrbitRigTest {
    private val w = 1000f
    private val h = 1600f
    private val rng = Random(20260806)

    private fun sheet(id: String, x: Float, y: Float, sw: Float, sh: Float) = Layer(
        id = id, name = id, type = LayerType.GROUP,
        surface = DefaultSurface.profile(SceneRect(x, y, sw, sh), w, h, emptyList()),
    )

    private val twoSheets = listOf(sheet("a", 0f, 0f, 200f, 400f), sheet("b", 600f, 100f, 300f, 500f))

    private fun randomCamera() = SceneCamera(
        yaw = rng.nextFloat() * 2f - 1f, pitch = (rng.nextFloat() - 0.5f) * 1.2f,
        distanceScale = 0.6f + rng.nextFloat(), panX = rng.nextFloat() * 80f - 40f, panY = rng.nextFloat() * 80f - 40f,
        panZ = rng.nextFloat() * 80f - 40f,
    )

    private fun randomPivot() = floatArrayOf(rng.nextFloat() * 800f, rng.nextFloat() * 1200f, rng.nextFloat() * 900f)

    private fun screen(camera: SceneCamera, p: FloatArray) = SceneMath.frame(camera, w, h).project(p)!!

    @Test
    fun `the pivot stays at the same place on screen under any orbit step`() {
        repeat(200) {
            val camera = randomCamera()
            val pivot = randomPivot()
            val before = screen(camera, pivot)
            val turned = OrbitRig.step(camera, pivot, (rng.nextFloat() - 0.5f) * 3f, (rng.nextFloat() - 0.5f) * 3f, w, h)
            val after = screen(turned, pivot)
            assertEquals(before.first, after.first, 0.05f)
            assertEquals(before.second, after.second, 0.05f)
        }
    }

    @Test
    fun `a drag and a triangle turn that make the same angles make the same camera`() {
        repeat(100) {
            val camera = randomCamera()
            val pivot = randomPivot()
            val degrees = (rng.nextFloat() - 0.5f) * 120f
            val (gy, gp) = OrbitRig.gizmoAngles(RotationAxis.Y, degrees)!!
            val (fy, fp) = OrbitRig.angles(gy / orbitRadiansPerPixel(w), gp / orbitRadiansPerPixel(w), w)
            assertEquals(gy, fy, 1e-5f)
            assertEquals(gp, fp, 1e-5f)
            val a = OrbitRig.step(camera, pivot, gy, gp, w, h)
            val b = OrbitRig.step(camera, pivot, fy, fp, w, h)
            assertEquals(a.yaw, b.yaw, 1e-4f)
            assertEquals(a.pitch, b.pitch, 1e-4f)
            assertEquals(a.panX, b.panX, 0.05f)
            assertEquals(a.panY, b.panY, 0.05f)
            assertEquals(a.panZ, b.panZ, 0.05f)
        }
    }

    /** A point set (a sheet's corners) turned about [pivot] by [rotation], as the old object turn did to a group. */
    private fun turnedAbout(points: List<FloatArray>, pivot: FloatArray, rotation: FloatArray) = points.map { p ->
        val d = floatArrayOf(p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2])
        FloatArray(3) { i -> pivot[i] + rotation[i * 3] * d[0] + rotation[i * 3 + 1] * d[1] + rotation[i * 3 + 2] * d[2] }
    }

    private val corners = listOf(
        floatArrayOf(100f, 200f, 0f), floatArrayOf(900f, 200f, 0f),
        floatArrayOf(100f, 1400f, 0f), floatArrayOf(900f, 1400f, 700f),
    )

    @Test
    fun `the triangle's Y leg looks exactly like turning the content about the pivot, from any camera`() {
        repeat(100) {
            val camera = randomCamera()
            val pivot = randomPivot()
            val degrees = (rng.nextFloat() - 0.5f) * 120f
            val (yaw, pitch) = OrbitRig.gizmoAngles(RotationAxis.Y, degrees)!!
            val orbited = OrbitRig.step(camera, pivot, yaw, pitch, w, h)
            val moved = turnedAbout(corners, pivot, SceneMath.axisRotation(RotationAxis.Y, degrees))
            for (i in corners.indices) {
                val viaCamera = screen(orbited, corners[i])
                val viaContent = screen(camera, moved[i])
                assertEquals("x$i", viaContent.first, viaCamera.first, 0.2f)
                assertEquals("y$i", viaContent.second, viaCamera.second, 0.2f)
            }
        }
    }

    @Test
    fun `the triangle's X leg looks exactly like turning the content about the pivot, face on`() {
        repeat(100) {
            val camera = randomCamera().copy(yaw = 0f)
            val pivot = randomPivot()
            val degrees = (rng.nextFloat() - 0.5f) * 120f
            val (yaw, pitch) = OrbitRig.gizmoAngles(RotationAxis.X, degrees)!!
            if (abs(camera.pitch + pitch) >= SceneCamera.MAX_PITCH) return@repeat
            val orbited = OrbitRig.step(camera, pivot, yaw, pitch, w, h)
            val moved = turnedAbout(corners, pivot, SceneMath.axisRotation(RotationAxis.X, degrees))
            for (i in corners.indices) {
                val viaCamera = screen(orbited, corners[i])
                val viaContent = screen(camera, moved[i])
                assertEquals("x$i", viaContent.first, viaCamera.first, 0.2f)
                assertEquals("y$i", viaContent.second, viaCamera.second, 0.2f)
            }
        }
    }

    @Test
    fun `Z does not orbit, it twists the canvas about the pivot's own screen position`() {
        assertNull(OrbitRig.gizmoAngles(RotationAxis.Z, 30f))
        repeat(50) {
            val camera = randomCamera()
            val pivot = randomPivot()
            val ox = rng.nextFloat() * 100f
            val oy = rng.nextFloat() * 100f
            val zoom = 0.5f + rng.nextFloat()
            val rot = rng.nextFloat() * 90f - 45f
            val at = OrbitRig.pivotOnScreen(camera, pivot, w, h, ox, oy, zoom, rot)!!
            // A canvas twist by d about `at` (what onViewportPanZoom does) leaves `at` on screen.
            val d = rng.nextFloat() * 60f - 30f
            val k = 1f
            val rad = Math.toRadians(d.toDouble())
            val c = kotlin.math.cos(rad).toFloat()
            val s = kotlin.math.sin(rad).toFloat()
            val dx = at.first - ox
            val dy = at.second - oy
            val newOx = at.first - k * (dx * c - dy * s)
            val newOy = at.second - k * (dx * s + dy * c)
            val after = OrbitRig.pivotOnScreen(camera, pivot, w, h, newOx, newOy, zoom, rot + d)!!
            assertEquals(at.first, after.first, 0.05f)
            assertEquals(at.second, after.second, 0.05f)
        }
    }

    @Test
    fun `an orbit never changes the pivot, because it never touches a layer`() {
        // The derived pivot depends on the layers alone; the step takes none, so no sequence of steps can move it.
        val pivot = OrbitPivot.resolve(twoSheets, w, h)
        var camera = SceneCamera()
        repeat(50) {
            camera = OrbitRig.step(camera, pivot, (rng.nextFloat() - 0.5f), (rng.nextFloat() - 0.5f), w, h)
            val again = OrbitPivot.resolve(twoSheets, w, h)
            for (i in 0..2) assertEquals(pivot[i], again[i], 1e-4f)
        }
        assertNotNull(camera)
    }

    @Test
    fun `turning one of two surfaces about the pivot does move the derived pivot, which is why the triangle must not`() {
        // The reproduction of the bug: the old triangle turned one group about the pivot (a rigid turn of part of the content),
        // and the pivot is the centre of the box holding all of it, so the next turn revolved about a different point.
        val p0 = OrbitPivot.resolve(twoSheets, w, h)
        val a = twoSheets[0]
        val pose = SceneMath.rotatePoseAbout(a.planePose, RotationAxis.Y, 37f, p0, w, h)
        val turned = a.copy(
            offset = Offset(pose.offsetX, pose.offsetY), positionZ = pose.positionZ,
            rotationX = pose.rotationX, rotationY = pose.rotationY, rotationZ = pose.rotationZ,
        )
        val p1 = OrbitPivot.resolve(listOf(turned, twoSheets[1]), w, h)
        val drift = kotlin.math.sqrt((0..2).sumOf { ((p1[it] - p0[it]) * (p1[it] - p0[it])).toDouble() }).toFloat()
        assertTrue("the derived pivot drifted by $drift", drift > 1f)
        assertTrue(PI > 0)
    }
}
