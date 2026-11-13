@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneSnapTest {
    private val w = 1000f
    private val h = 1600f
    private val cam = SceneMath.frame(SceneCamera(), w, h)

    private fun deg(d: Float) = (d * PI / 180.0).toFloat()

    // ---- depth ----

    @Test
    fun `a candidate inside the range pulls the depth to exactly its own`() {
        val hit = PlaneSnap.snapDepth(1000f, floatArrayOf(1050f))!!
        assertEquals(1050f, hit.depth, 0f)
        assertEquals(0, hit.index)
    }

    @Test
    fun `the range is six percent of the effective distance, edge included, nothing beyond`() {
        assertNotNull(PlaneSnap.snapDepth(1000f, floatArrayOf(1060f)))
        assertNotNull(PlaneSnap.snapDepth(1000f, floatArrayOf(940f)))
        assertNull(PlaneSnap.snapDepth(1000f, floatArrayOf(1060.5f)))
        assertNull(PlaneSnap.snapDepth(1000f, floatArrayOf(939f)))
        // Relative to the effective distance: the same gap is outside it farther out... and inside nearer in.
        assertNull(PlaneSnap.snapDepth(2000f, floatArrayOf(2121f)))
        assertNotNull(PlaneSnap.snapDepth(100f, floatArrayOf(105f)))
        assertNull(PlaneSnap.snapDepth(100f, floatArrayOf(107f)))
    }

    @Test
    fun `no candidates, or none in range, is no snap`() {
        assertNull(PlaneSnap.snapDepth(1000f, floatArrayOf()))
        assertNull(PlaneSnap.snapDepth(1000f, floatArrayOf(300f, 3000f)))
        assertNull(PlaneSnap.snapDepth(0f, floatArrayOf(10f)))
        assertNull(PlaneSnap.snapDepth(Float.NaN, floatArrayOf(10f)))
        assertNull(PlaneSnap.snapDepth(1000f, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `the nearest of several wins`() {
        val hit = PlaneSnap.snapDepth(1000f, floatArrayOf(1055f, 3000f, 1020f, 970f))!!
        assertEquals(1020f, hit.depth, 0f)
        assertEquals(2, hit.index)
    }

    @Test
    fun `a tie goes to the nearer plane, then to the lower index`() {
        val tie = PlaneSnap.snapDepth(1000f, floatArrayOf(1030f, 970f))!!
        assertEquals(970f, tie.depth, 0f)
        assertEquals(1, tie.index)
        val same = PlaneSnap.snapDepth(1000f, floatArrayOf(1010f, 1010f))!!
        assertEquals(0, same.index)
    }

    @Test
    fun `there is no hysteresis, the answer is a function of the raw depth alone`() {
        val c = floatArrayOf(1000f)
        assertEquals(1000f, PlaneSnap.snapDepth(1050f, c)!!.depth, 0f)
        // One step beyond the range and the snap is gone, however it got there.
        assertNull(PlaneSnap.snapDepth(1000f / 0.94f + 1f, c))
        assertEquals(1000f, PlaneSnap.snapDepth(1000f / 0.94f - 1f, c)!!.depth, 0f)
    }

    @Test
    fun `with no reference the plane depth is the centre's own camera depth`() {
        val centre = floatArrayOf(500f, 800f, 700f)
        assertEquals(cam.toCamera(centre)[2], PlaneSnap.planeDepth(null, cam, null, centre)!!, 1e-3f)
        assertNull("behind the eye", PlaneSnap.planeDepth(null, cam, null, floatArrayOf(500f, 800f, -5000f)))
    }

    @Test
    fun `with a reference the plane depth is measured along its normal from its own centre`() {
        // A reference tilted 40 degrees about Y, its centre in front of the eye.
        val model = SceneMath.model(PlanePose(positionZ = 300f, rotationY = 40f), w, h)
        val cam = SceneMath.frame(SceneCamera(yaw = 0.2f), w, h)
        val at = model.apply(w / 2f, h / 2f, 0f)
        val n = SceneMath.normalize(model.column(2))
        val toward = if (SceneMath.dot(n, cam.forward) > 0f) FloatArray(3) { -n[it] } else n
        // A point on the reference's own line along the normal: the depth is the camera depth of that point.
        val s = 120f
        val onLine = FloatArray(3) { at[it] + toward[it] * s }
        assertEquals(cam.toCamera(onLine)[2], PlaneSnap.planeDepth(model, cam, null, onLine)!!, 0.05f)
        // Sliding along the plane itself (perpendicular to the normal) does not change which plane it is on.
        val inPlane = SceneMath.normalize(model.column(0))
        val slid = FloatArray(3) { onLine[it] + inPlane[it] * 400f }
        assertEquals(
            PlaneSnap.planeDepth(model, cam, null, onLine)!!,
            PlaneSnap.planeDepth(model, cam, null, slid)!!,
            0.05f,
        )
        // The reference's own centre is at its own depth.
        assertEquals(cam.toCamera(at)[2], PlaneSnap.planeDepth(model, cam, null, at)!!, 0.05f)
    }

    @Test
    fun `a candidate plane's depth is exactly where DrawPlane puts a draw plane that coincides with it`() {
        val reference = SceneMath.model(PlanePose(positionZ = 100f, rotationY = 25f), w, h)
        val cam = SceneMath.frame(SceneCamera(yaw = 0.1f), w, h)
        val at = reference.apply(w / 2f, h / 2f, 0f)
        val n = SceneMath.normalize(reference.column(2))
        val toward = if (SceneMath.dot(n, cam.forward) > 0f) FloatArray(3) { -n[it] } else n
        val centre = FloatArray(3) { at[it] + toward[it] * 250f }
        val depth = PlaneSnap.planeDepth(reference, cam, null, centre)!!
        val world = DrawPlane.world(cam, reference, null, depth)!!
        val got = world.apply(w / 2f, h / 2f, 0f)
        for (i in 0..2) assertEquals(centre[i], got[i], 0.5f)
    }

    // ---- orbit ----

    @Test
    fun `an orbit within five degrees of face-on snaps to exactly face-on`() {
        val normals = listOf(floatArrayOf(0f, 0f, -1f))
        val hit = PlaneSnap.snapOrbit(deg(3f), deg(-2f), normals)!!
        assertEquals(0f, hit.pitch, 1e-6f)
        // The plane faces away from the eye here, so the view is the one from the nearer side: yaw 0 looks along +z.
        assertEquals(0f, hit.yaw, 1e-5f)
        // The snapped view looks straight along the normal (either way round).
        val f = PlaneSnap.forwardOf(hit.yaw, hit.pitch)
        assertEquals(1f, abs(SceneMath.dot(f, normals[0])), 1e-5f)
    }

    @Test
    fun `the range is five degrees, and the constant says so`() {
        assertEquals(5f, PlaneSnap.ORBIT_CAPTURE_DEGREES, 0f)
        assertEquals(deg(5f), PlaneSnap.ORBIT_CAPTURE_RADIANS, 1e-6f)
        val normals = listOf(floatArrayOf(0f, 0f, 1f))
        assertNotNull(PlaneSnap.snapOrbit(deg(4.9f), 0f, normals))
        assertNull(PlaneSnap.snapOrbit(deg(5.2f), 0f, normals))
        assertNull(PlaneSnap.snapOrbit(0f, deg(-6f), normals))
    }

    @Test
    fun `no candidates is no snap`() {
        assertNull(PlaneSnap.snapOrbit(0f, 0f, emptyList()))
        assertNull(PlaneSnap.snapOrbit(0f, 0f, listOf(floatArrayOf(0f, 0f, 0f))))
    }

    @Test
    fun `either side of a plane is face-on, and the nearer one is taken`() {
        val normals = listOf(floatArrayOf(0f, 0f, 1f))
        val front = PlaneSnap.snapOrbit(deg(2f), 0f, normals)!!
        assertEquals(0f, front.yaw, 1e-6f)
        val back = PlaneSnap.snapOrbit(PI.toFloat() - deg(2f), 0f, normals)!!
        assertEquals(PI.toFloat(), back.yaw, 1e-5f)
    }

    @Test
    fun `the nearest of several planes wins, a tie goes to the lower index`() {
        val a = floatArrayOf(0f, 0f, 1f)
        val tilted = floatArrayOf(kotlin.math.sin(deg(8f)), 0f, kotlin.math.cos(deg(8f)))
        val hit = PlaneSnap.snapOrbit(deg(4f), 0f, listOf(a, tilted))!!
        assertEquals(1, hit.index)
        assertEquals(deg(8f), hit.yaw, 1e-4f)
        val tie = PlaneSnap.snapOrbit(0f, 0f, listOf(a, a))!!
        assertEquals(0, tie.index)
    }

    @Test
    fun `there is no hysteresis in the orbit either`() {
        val normals = listOf(floatArrayOf(0f, 0f, 1f))
        assertNotNull(PlaneSnap.snapOrbit(deg(4.9f), 0f, normals))
        assertNull(PlaneSnap.snapOrbit(deg(5.1f), 0f, normals))
        assertNotNull(PlaneSnap.snapOrbit(deg(4.9f), 0f, normals))
    }

    @Test
    fun `yaw wraps, a view past a half turn snaps by the short way round`() {
        val normals = listOf(floatArrayOf(0f, 0f, 1f))
        val twoPi = (2 * PI).toFloat()
        val hit = PlaneSnap.snapOrbit(twoPi + deg(3f), 0f, normals)!!
        assertEquals(twoPi, hit.yaw, 1e-4f)
    }

    @Test
    fun `a plane facing straight up is not offered, the pitch limit forbids that view`() {
        assertNull(PlaneSnap.snapOrbit(0f, SceneCamera.MAX_PITCH, listOf(floatArrayOf(0f, 1f, 0f))))
    }

    @Test
    fun `the snapped view puts the camera exactly along a tilted plane's normal`() {
        val model = SceneMath.model(PlanePose(rotationX = 20f, rotationY = 30f), w, h)
        val n = SceneMath.normalize(SceneMath.cross(model.column(0), model.column(1)))
        val near = floatArrayOf(0f, 0f)
        val start = PlaneSnap.forwardOf(0f, 0f)
        assertTrue(start.size == 3 && near.size == 2)
        // Start a couple of degrees from that view and snap.
        val yaw0 = kotlin.math.atan2(n[0], n[2]) + deg(2f)
        val pitch0 = kotlin.math.asin(n[1].coerceIn(-1f, 1f)) - deg(1f)
        val hit = PlaneSnap.snapOrbit(yaw0, pitch0, listOf(n))
        assertNotNull(hit)
        val f = PlaneSnap.forwardOf(hit!!.yaw, hit.pitch)
        assertEquals(1f, abs(SceneMath.dot(f, n)), 1e-4f)
    }
}
