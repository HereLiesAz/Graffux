package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewControlTest {
    private val wall = SurfaceProfile(listOf(0f, 0f, 0f, 500f, 0f, 0f, 1000f, 0f, 400f))
    private val flat = Layer(id = "a", name = "a")
    private val group3d = Layer(id = "g", name = "g", type = LayerType.GROUP, surface = wall)
    private val groupPlain = Layer(id = "p", name = "p", type = LayerType.GROUP)

    @Test
    fun `a project has a 3D group only when a group has a surface`() {
        assertFalse(hasThreeDGroup(emptyList()))
        assertFalse(hasThreeDGroup(listOf(flat, groupPlain)))
        assertTrue(hasThreeDGroup(listOf(flat, group3d)))
    }

    @Test
    fun `two-finger drag orbits with a 3D group and pans without`() {
        assertEquals(TwoFingerViewMode.PAN, twoFingerViewMode(listOf(flat)))
        assertEquals(TwoFingerViewMode.ORBIT, twoFingerViewMode(listOf(flat, group3d)))
    }

    @Test
    fun `a full canvas width of drag is the named angle`() {
        val yaw = orbitAnglesForDrag(0f, 0f, 1000f).first
        assertEquals(0f, yaw, 0f)
        assertEquals(ORBIT_FULL_WIDTH_RADIANS, orbitAnglesForDrag(1000f, 0f, 1000f).first, 1e-5f)
        assertEquals(ORBIT_FULL_WIDTH_RADIANS / 2f, orbitAnglesForDrag(0f, 500f, 1000f).second, 1e-5f)
        assertEquals(0f, orbitAnglesForDrag(10f, 10f, 0f).first, 0f)
    }

    @Test
    fun `orbiting follows the fingers and keeps the pivot fixed on screen`() {
        val w = 1000f
        val h = 1600f
        val pivot = floatArrayOf(500f, 800f, 800f)
        val near = floatArrayOf(500f, 800f, 300f) // between the pivot and the eye
        fun screen(cam: SceneCamera, p: FloatArray): Pair<Float, Float> {
            val f = SceneMath.frame(cam, w, h)
            val c = f.toCamera(p)
            return Pair(w / 2f + c[0] * f.focal / c[2], h / 2f + c[1] * f.focal / c[2])
        }
        val start = SceneCamera()
        val (yaw, pitch) = orbitAnglesForDrag(100f, 0f, w)
        val turned = SceneMath.orbitAbout(start, pivot, yaw, pitch, w, h)
        assertEquals(screen(start, pivot).first, screen(turned, pivot).first, 0.5f)
        assertTrue("near side follows a rightward drag", screen(turned, near).first > screen(start, near).first)
        val (y2, p2) = orbitAnglesForDrag(0f, 100f, w)
        val down = SceneMath.orbitAbout(start, pivot, y2, p2, w, h)
        assertTrue("near side follows a downward drag", screen(down, near).second > screen(start, near).second)
    }

    @Test
    fun `pitch stays clamped however far the drag goes`() {
        val (y, p) = orbitAnglesForDrag(0f, 100000f, 1000f)
        assertEquals(SceneCamera.MAX_PITCH, SceneCamera().orbit(y, p).pitch, 0f)
    }

    @Test
    fun `a second tap close behind the first is a double-tap, a slow or distant one is not`() {
        val t = ViewTapTracker(300L, 100f)
        assertFalse(t.onTap(10f, 10f, 0L))
        assertTrue(t.onTap(20f, 20f, 200L))
        assertFalse("third tap starts afresh", t.onTap(20f, 20f, 300L))
        assertFalse("too slow", t.onTap(20f, 20f, 900L))
        assertFalse("too far", t.onTap(500f, 500f, 1000L))
    }

    @Test
    fun `an orbit step has the signs of the matching drag and a fixed size`() {
        val (dyaw, _) = orbitAnglesForDrag(1f, 0f, 1000f)
        val (_, dpitch) = orbitAnglesForDrag(0f, 1f, 1000f)
        assertEquals(Pair(ORBIT_STEP_RADIANS, 0f), orbitAnglesForStep(OrbitStep.RIGHT))
        assertEquals(Pair(-ORBIT_STEP_RADIANS, 0f), orbitAnglesForStep(OrbitStep.LEFT))
        assertEquals(Pair(0f, ORBIT_STEP_RADIANS), orbitAnglesForStep(OrbitStep.DOWN))
        assertEquals(Pair(0f, -ORBIT_STEP_RADIANS), orbitAnglesForStep(OrbitStep.UP))
        assertTrue(dyaw > 0f && dpitch > 0f)
        assertEquals(15.0, Math.toDegrees(ORBIT_STEP_RADIANS.toDouble()), 1e-4)
    }

    @Test
    fun `repeated pitch steps stop at the same clamp as a long drag`() {
        var cam = SceneCamera()
        repeat(100) { val (y, p) = orbitAnglesForStep(OrbitStep.DOWN); cam = cam.orbit(y, p) }
        assertEquals(SceneCamera.MAX_PITCH, cam.pitch, 0f)
    }
}
