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
    fun `triangle degrees become radians with fixed signs`() {
        val quarter = (PI / 2.0).toFloat()
        assertEquals(Pair(-quarter, 0f), OrbitRig.gizmoAngles(RotationAxis.Y, 90f))
        assertEquals(Pair(0f, quarter), OrbitRig.gizmoAngles(RotationAxis.X, 90f))
        assertNull(OrbitRig.gizmoAngles(RotationAxis.Z, 90f))
    }

    @Test
    fun `a triangle turn gives the camera that orbitAbout gives for the hand converted angles`() {
        repeat(100) {
            val camera = randomCamera()
            val pivot = randomPivot()
            val degrees = (rng.nextFloat() - 0.5f) * 120f
            val rad = Math.toRadians(degrees.toDouble()).toFloat()
            // Expected, worked out by hand and not through OrbitRig: Y yaws by -rad, X pitches by +rad.
            val yawExpected = SceneMath.orbitAbout(camera, pivot, -rad, 0f, w, h)
            val (gy, gp) = OrbitRig.gizmoAngles(RotationAxis.Y, degrees)!!
            assertCamera(yawExpected, OrbitRig.step(camera, pivot, gy, gp, w, h))
            val pitchExpected = SceneMath.orbitAbout(camera, pivot, 0f, rad, w, h)
            val (xy, xp) = OrbitRig.gizmoAngles(RotationAxis.X, degrees)!!
            assertCamera(pitchExpected, OrbitRig.step(camera, pivot, xy, xp, w, h))
            // A two-finger drag of dx pixels is dx * pi / W radians of yaw (the ViewControl mapping, restated).
            val dx = (rng.nextFloat() - 0.5f) * 400f
            val (fy, fp) = OrbitRig.angles(dx, 0f, w)
            assertCamera(
                SceneMath.orbitAbout(camera, pivot, dx * PI.toFloat() / w, 0f, w, h),
                OrbitRig.step(camera, pivot, fy, fp, w, h),
            )
        }
    }

    private fun assertCamera(a: SceneCamera, b: SceneCamera) {
        assertEquals(a.yaw, b.yaw, 1e-4f)
        assertEquals(a.pitch, b.pitch, 1e-4f)
        assertEquals(a.distanceScale, b.distanceScale, 1e-5f)
        assertEquals(a.panX, b.panX, 0.05f)
        assertEquals(a.panY, b.panY, 0.05f)
        assertEquals(a.panZ, b.panZ, 0.05f)
    }

    @Test
    fun `pitch stops at the clamp and the X leg matches the old object turn only face on`() {
        val pivot = floatArrayOf(500f, 800f, 454f)
        val nearPole = SceneCamera(pitch = SceneCamera.MAX_PITCH - 0.1f)
        val (y, p) = OrbitRig.gizmoAngles(RotationAxis.X, 40f)!!
        assertEquals(SceneCamera.MAX_PITCH, OrbitRig.step(nearPole, pivot, y, p, w, h).pitch, 1e-6f)
        // Camera-relative: with the view yawed, an X turn still pitches by exactly the angle, about the SCREEN's
        // horizontal, which is not the world X line the old object turn used.
        val yawed = SceneCamera(yaw = 0.8f)
        val turned = OrbitRig.step(yawed, pivot, y, p, w, h)
        assertEquals(Math.toRadians(40.0).toFloat(), turned.pitch, 1e-5f)
        val old = turnedAbout(corners, pivot, SceneMath.axisRotation(RotationAxis.X, 40f))
        val moved = corners.indices.maxOf { i ->
            val a = screen(turned, corners[i])
            val b = screen(yawed, old[i])
            abs(a.first - b.first) + abs(a.second - b.second)
        }
        assertTrue("off-axis the camera pitch differs from the old world-X turn ($moved px)", moved > 1f)
    }

    @Test
    fun `the default sheet pivot arithmetic and the two surface example worked from the rule`() {
        // Default sheet on a 1000 x 1600 canvas: 3/4 of 1000 by 2/3 of 1600 = 750 x 1066.67 (not the canvas size).
        val sheetW = 1000f * DefaultSurface.SHORT_FRACTION
        val sheetH = 1600f * DefaultSurface.LONG_FRACTION
        val n = ArtSheet.pivotDepth(sheetW, sheetH)
        assertEquals((750f + 1066.6667f) / 4f, n, 0.01f) // about 454.2
        assertEquals(454.17f, n, 0.01f)
        // The canvas-size formula would be a different number: (1000 + 1600) / 4 = 650. It is not what the rule uses.
        assertEquals(650f, ArtSheet.pivotDepth(1000f, 1600f), 0f)
        val lone = OrbitPivot.resolve(listOf(sheet("a", 125f, 266.6667f, sheetW, sheetH)), w, h)
        assertEquals(500f, lone[0], 0.01f)
        assertEquals(800f, lone[1], 0.01f)
        assertEquals(n, lone[2], 0.01f)
        // Swung a quarter turn the sheet's centre (n in front of the pivot) is n to one side: n / 1000 of the width.
        assertEquals(0.454f, n / 1000f, 0.001f)
        // With a second sheet at (600, 100, 300 x 500): x 125..900 -> 512.5, y 100..1333.33 -> 716.67, z 0..2n -> n.
        val two = OrbitPivot.resolve(
            listOf(sheet("a", 125f, 266.6667f, sheetW, sheetH), sheet("b", 600f, 100f, 300f, 500f)), w, h,
        )
        assertEquals(512.5f, two[0], 0.01f)
        assertEquals(716.6667f, two[1], 0.01f)
        assertEquals(n, two[2], 0.01f)
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
