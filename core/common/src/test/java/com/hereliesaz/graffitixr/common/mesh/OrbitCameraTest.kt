package com.hereliesaz.graffitixr.common.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** The turntable camera: the invariants that keep it from ending up somewhere unusable. */
class OrbitCameraTest {

    private fun length(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dist(a: FloatArray, b: FloatArray) =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    @Test
    fun `the eye always sits at exactly the orbit distance from the target`() {
        // The defining property: whatever the rotation, the camera is on a sphere around the target.
        val cam = OrbitCamera(target = floatArrayOf(1f, 2f, 3f), distance = 7f)
        listOf(0f, 0.7f, 2.1f, -1.3f).forEach { yaw ->
            listOf(0f, 0.9f, -0.9f).forEach { pitch ->
                val moved = cam.copy(yaw = yaw, pitch = pitch)
                assertEquals("yaw=$yaw pitch=$pitch", 7f, dist(moved.eye(), moved.target), 1e-3f)
            }
        }
    }

    @Test
    fun `pitch is clamped short of the poles`() {
        // At exactly the pole the view direction is parallel to world up and the look-at basis
        // collapses — the camera would flip over at the top of a drag.
        val up = OrbitCamera().orbit(0f, 100f)
        val down = OrbitCamera().orbit(0f, -100f)
        assertTrue(up.pitch < PI.toFloat() / 2f)
        assertTrue(down.pitch > -PI.toFloat() / 2f)
        // Still a usable basis at the clamp.
        assertEquals(1f, length(rightAxis(up)), 1e-3f)
        assertEquals(1f, length(rightAxis(down)), 1e-3f)
    }

    private fun rightAxis(cam: OrbitCamera): FloatArray {
        val m = cam.viewMatrix()
        return floatArrayOf(m[0], m[4], m[8])
    }

    @Test
    fun `yaw is free to wrap without clamping`() {
        val spun = OrbitCamera().orbit(10f, 0f)
        assertEquals(10f, spun.yaw, 1e-5f)
    }

    @Test
    fun `dolly is multiplicative and never reaches the target`() {
        val cam = OrbitCamera(distance = 10f)
        assertEquals(5f, cam.dolly(2f).distance, 1e-4f)
        assertEquals(20f, cam.dolly(0.5f).distance, 1e-4f)
        // Pinching in hard must not put the camera on (or through) the target.
        assertTrue(cam.dolly(1e9f).distance > 0f)
        // A degenerate factor must not divide by zero.
        assertTrue(cam.dolly(0f).distance > 0f)
    }

    @Test
    fun `pan moves the target and carries the eye with it`() {
        val cam = OrbitCamera(distance = 5f)
        val panned = cam.pan(100f, 0f)
        assertTrue("target should have moved", dist(cam.target, panned.target) > 0f)
        // Panning slides the whole rig — the orbit distance is unchanged.
        assertEquals(5f, dist(panned.eye(), panned.target), 1e-3f)
    }

    @Test
    fun `pan scales with distance so dragging feels the same at any zoom`() {
        val near = OrbitCamera(distance = 1f).pan(100f, 0f)
        val far = OrbitCamera(distance = 100f).pan(100f, 0f)
        val nearMoved = dist(floatArrayOf(0f, 0f, 0f), near.target)
        val farMoved = dist(floatArrayOf(0f, 0f, 0f), far.target)
        assertTrue("far pan should cover more world distance", farMoved > nearMoved * 50f)
    }

    @Test
    fun `the view matrix is orthonormal`() {
        // If the basis isn't orthonormal the model renders sheared.
        val m = OrbitCamera(distance = 4f, yaw = 0.8f, pitch = 0.5f).viewMatrix()
        val right = floatArrayOf(m[0], m[4], m[8])
        val up = floatArrayOf(m[1], m[5], m[9])
        val back = floatArrayOf(m[2], m[6], m[10])
        assertEquals(1f, length(right), 1e-4f)
        assertEquals(1f, length(up), 1e-4f)
        assertEquals(1f, length(back), 1e-4f)
        assertEquals(0f, right[0] * up[0] + right[1] * up[1] + right[2] * up[2], 1e-4f)
        assertEquals(0f, right[0] * back[0] + right[1] * back[1] + right[2] * back[2], 1e-4f)
    }

    @Test
    fun `the view matrix maps the target to the negative z axis`() {
        // Looking at the target means the target lands straight ahead, at -distance in view space.
        val cam = OrbitCamera(target = floatArrayOf(2f, -1f, 4f), distance = 6f, yaw = 1.1f, pitch = 0.4f)
        val m = cam.viewMatrix()
        val t = cam.target
        val vx = m[0] * t[0] + m[4] * t[1] + m[8] * t[2] + m[12]
        val vy = m[1] * t[0] + m[5] * t[1] + m[9] * t[2] + m[13]
        val vz = m[2] * t[0] + m[6] * t[1] + m[10] * t[2] + m[14]
        assertEquals(0f, vx, 1e-3f)
        assertEquals(0f, vy, 1e-3f)
        assertEquals(-6f, vz, 1e-3f)
    }

    @Test
    fun `the projection matrix has the expected perspective shape`() {
        val p = OrbitCamera(fovYDegrees = 90f, near = 1f, far = 101f).projectionMatrix(aspect = 2f)
        // tan(45°) = 1, so f = 1; divided by aspect 2.
        assertEquals(0.5f, p[0], 1e-4f)
        assertEquals(1f, p[5], 1e-4f)
        // Perspective divide comes from the -1 in the w row.
        assertEquals(-1f, p[11], 1e-4f)
        assertEquals(0f, p[15], 1e-4f)
    }

    @Test
    fun `projection tolerates a degenerate aspect`() {
        val p = OrbitCamera().projectionMatrix(aspect = 0f)
        assertTrue("should not be NaN or infinite", p.all { it.isFinite() })
    }

    // ── Framing ──────────────────────────────────────────────────────────────────────────────

    private fun meshSpanning(halfExtent: Float): Mesh = ObjParser.parse(
        """
        v ${-halfExtent} ${-halfExtent} ${-halfExtent}
        v $halfExtent ${-halfExtent} ${-halfExtent}
        v ${-halfExtent} $halfExtent $halfExtent
        f 1 2 3
        """.trimIndent(),
    )

    @Test
    fun `framing puts the whole model in view at any authored scale`() {
        // A model in millimetres and the same model in metres must both open filling roughly the
        // same amount of screen — otherwise one is a speck and the other engulfs the camera.
        listOf(0.001f, 1f, 1000f).forEach { scale ->
            val mesh = meshSpanning(scale)
            val cam = OrbitCamera.framing(mesh)
            val radius = mesh.radius()
            assertTrue("scale=$scale: camera should sit outside the model", cam.distance > radius)
            // The ratio of distance to size is what stays constant across scales.
            assertEquals("scale=$scale", 3.38f, cam.distance / radius, 0.2f)
        }
    }

    @Test
    fun `framing aims at the model's centre, not the origin`() {
        val offCentre = ObjParser.parse("v 100 100 100\nv 102 100 100\nv 100 102 100\nf 1 2 3")
        val cam = OrbitCamera.framing(offCentre)
        assertTrue("should target the model", abs(cam.target[0] - 101f) < 1.5f)
    }

    @Test
    fun `framing brackets the clip planes around the model`() {
        // Fixed planes would clip a huge model through the far plane and z-fight a tiny one.
        listOf(0.01f, 1f, 500f).forEach { scale ->
            val cam = OrbitCamera.framing(meshSpanning(scale))
            assertTrue("scale=$scale: near must be positive", cam.near > 0f)
            assertTrue("scale=$scale: near before far", cam.near < cam.far)
            assertTrue("scale=$scale: model must fit inside the frustum", cam.far > cam.distance)
        }
    }
}
