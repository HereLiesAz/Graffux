@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialGuideSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class ViewPlaneAdoptionTest {
    private val w = 1000f
    private val h = 1600f
    private val eps = 0.05f

    private fun assertPoint(e: FloatArray, a: FloatArray) {
        for (i in 0..2) assertEquals("axis $i", e[i], a[i], eps)
    }

    @Test
    fun `the adopted plane faces the camera through its target`() {
        val camera = SceneCamera(yaw = 0.7f, pitch = -0.4f, distanceScale = 1.6f, panX = 30f, panZ = -50f)
        val pose = SceneMath.viewPlanePose(camera, w, h)!!
        val model = SceneMath.model(pose, w, h)
        val cam = SceneMath.frame(camera, w, h)
        // The box centre is the orbit target.
        assertPoint(cam.target, model.apply(w / 2, h / 2, 0f))
        // Its normal is the view direction, so every point of it is at the target's depth.
        val depth = cam.toCamera(cam.target)[2]
        for ((u, v) in listOf(0f to 0f, w to 0f, 300f to 1200f)) {
            assertEquals(depth, cam.toCamera(model.apply(u, v, 0f))[2], eps)
        }
        // It projects as the flat layer would at the default view: pixel for pixel.
        val hm = SceneMath.homography(model, cam)
        val (x, y) = SceneMath.applyHomography(hm, 100f, 200f)!!
        assertEquals(100f, x, 0.5f)
        assertEquals(200f, y, 0.5f)
    }

    @Test
    fun `pose decomposition round-trips any rotation, scale and offset`() {
        val pose = PlanePose(offsetX = 12f, offsetY = -40f, positionZ = 77f, rotationX = 20f, rotationY = -35f, rotationZ = 50f, scale = 1.7f)
        val back = SceneMath.poseOf(SceneMath.model(pose, w, h), w, h)!!
        val m1 = SceneMath.model(pose, w, h).m
        val m2 = SceneMath.model(back, w, h).m
        for (i in m1.indices) assertEquals(m1[i], m2[i], 1e-2f)
    }

    @Test
    fun `at the default view the adopted pose is the identity plane`() {
        val pose = SceneMath.viewPlanePose(SceneCamera(), w, h)!!
        assertTrue(!pose.isPlacedIn3D)
        assertEquals(1f, pose.scale, 1e-5f)
        assertEquals(0f, pose.offsetX, eps)
        assertEquals(0f, pose.rotationZ, 1e-3f)
    }

    @Test
    fun `inside a group placed in 3D the pose is in the group's space`() {
        val group = PlanePose(positionZ = 300f, rotationY = 25f)
        val camera = SceneCamera(yaw = -0.5f)
        val pose = SceneMath.viewPlanePose(camera, w, h, listOf(group))!!
        val world = SceneMath.chainModel(listOf(group, pose), w, h)
        assertPoint(SceneMath.frame(camera, w, h).target, world.apply(w / 2, h / 2, 0f))
    }

    /**
     * The owner's case: a square drawn at the default view; the view orbited 90 degrees so the
     * square is edge-on; a second square drawn on a new layer; the view tilted up 90 degrees. The two
     * share an edge and form an L.
     */
    @Test
    fun `two squares drawn across a 90 degree orbit share an edge and form an L`() {
        val half = 200f
        val cx = w / 2
        val cy = h / 2
        // Square 1: flat layer, its right edge on the vertical centre line (x = cx).
        val first = PlanePose()
        val square1 = listOf(cx - 2 * half to cy - half, cx to cy - half, cx to cy + half, cx - 2 * half to cy + half)
        // Orbit 90 degrees: square 1 is edge-on, a line through the screen centre.
        val orbited = SceneCamera(yaw = (PI / 2).toFloat())
        val cam = SceneMath.frame(orbited, w, h)
        val edgeOn = square1.map { (u, v) -> cam.project(SceneMath.model(first, w, h).apply(u, v, 0f))!! }
        edgeOn.forEach { assertEquals(w / 2, it.first, 0.5f) }
        // Square 2 on a new, empty layer: it adopts the view plane, and is drawn with its left edge
        // on the screen centre line, where square 1 now is.
        val second = SceneMath.viewPlanePose(orbited, w, h)!!
        assertEquals(90f, abs(second.rotationY), 0.01f)
        val square2 = listOf(cx to cy - half, cx + 2 * half to cy - half, cx + 2 * half to cy + half, cx to cy + half)
        val world1 = square1.map { (u, v) -> SceneMath.model(first, w, h).apply(u, v, 0f) }
        val world2 = square2.map { (u, v) -> SceneMath.model(second, w, h).apply(u, v, 0f) }
        // The shared edge: square 1's right edge is square 2's left edge, in space.
        assertPoint(world1[1], world2[0])
        assertPoint(world1[2], world2[3])
        // The planes are perpendicular.
        val n1 = SceneMath.cross(SceneMath.model(first, w, h).column(0), SceneMath.model(first, w, h).column(1))
        val n2 = SceneMath.cross(SceneMath.model(second, w, h).column(0), SceneMath.model(second, w, h).column(1))
        assertEquals(0f, SceneMath.dot(SceneMath.normalize(n1), SceneMath.normalize(n2)), 1e-4f)
        // Tilt the view up 90 degrees (looking straight down): both squares are edge-on lines that
        // meet at the shared edge, at right angles: an L.
        val top = SceneMath.frame(orbited.orbit(0f, 10f), w, h) // clamped just short of 90 degrees
        val corner = top.project(world1[1])!!
        val end1 = top.project(world1[0])!!
        val end2 = top.project(world2[1])!!
        val a = floatArrayOf(end1.first - corner.first, end1.second - corner.second, 0f)
        val b = floatArrayOf(end2.first - corner.first, end2.second - corner.second, 0f)
        val cos = SceneMath.dot(a, b) / (kotlin.math.sqrt(SceneMath.dot(a, a)) * kotlin.math.sqrt(SceneMath.dot(b, b)))
        assertEquals(0f, cos, 0.05f)
        assertTrue(kotlin.math.sqrt(SceneMath.dot(a, a)) > 50f && kotlin.math.sqrt(SceneMath.dot(b, b)) > 50f)
    }

    @Test
    fun `a 3D strokes layer's default guide is the same view plane through the target`() {
        val camera = SceneCamera(yaw = 1.1f, pitch = 0.3f)
        val cam = SceneMath.frame(camera, w, h)
        val toScene = SceneMath.strokeToLocal(w, h)
        val inv = toScene.inverse()!!
        val view = com.hereliesaz.graffitixr.common.mesh.SpatialView(
            inv.apply(cam.target[0], cam.target[1], cam.target[2]),
            SceneMath.normalize(inv.applyVector(cam.right[0], cam.right[1], cam.right[2])),
            SceneMath.normalize(inv.applyVector(-cam.down[0], -cam.down[1], -cam.down[2])),
            SceneMath.normalize(inv.applyVector(cam.forward[0], cam.forward[1], cam.forward[2])),
        )
        val d = cam.rayDirection(300f, 900f)
        val origin = inv.apply(cam.eye[0], cam.eye[1], cam.eye[2])
        val hit = SpatialGuideSurface.intersectRay(SpatialGuide(), view, origin, SceneMath.normalize(inv.applyVector(d[0], d[1], d[2])))
        assertNotNull(hit)
        val scene = toScene.apply(hit!!.x, hit.y, hit.z)
        assertEquals(cam.toCamera(cam.target)[2], cam.toCamera(scene)[2], 0.5f)
    }
}
