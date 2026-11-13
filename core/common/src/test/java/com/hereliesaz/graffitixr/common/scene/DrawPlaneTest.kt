@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawPlaneTest {
    private val w = 1000f
    private val h = 1600f
    private val eps = 0.5f

    private fun cam(c: SceneCamera = SceneCamera()) = SceneMath.frame(c, w, h)

    private fun depthOf(world: Affine3, cam: CameraFrame) = cam.toCamera(world.apply(w / 2f, h / 2f, 0f))[2]

    private fun normalOf(world: Affine3) = SceneMath.normalize(world.column(2))

    @Test
    fun `a drag up goes nearer, down goes farther, multiplicatively, 400dp for a factor of four`() {
        val focal = SceneMath.focalLength(h)
        val mid = focal * 3f
        assertEquals(mid * 4f, DrawPlane.depthAfterDrag(mid, 400f, focal), 0.01f)
        assertEquals(mid / 4f, DrawPlane.depthAfterDrag(mid, -400f, focal), 0.01f)
        assertEquals(mid * 2f, DrawPlane.depthAfterDrag(mid, 200f, focal), 0.01f)
        assertEquals(mid, DrawPlane.depthAfterDrag(mid, 0f, focal), 0.001f)
    }

    @Test
    fun `the depth stays between twice the near plane and a tenth of the far plane`() {
        val focal = SceneMath.focalLength(h)
        val range = DrawPlane.depthRange(focal)
        assertEquals(SceneMath.nearPlane(focal) * 2f, range.start, 1e-3f)
        assertEquals(SceneMath.farPlane(focal) / 10f, range.endInclusive, 1e-3f)
        assertEquals(range.start, DrawPlane.depthAfterDrag(focal, -100_000f, focal), 0f)
        assertEquals(range.endInclusive, DrawPlane.depthAfterDrag(focal, 100_000f, focal), 0f)
    }

    @Test
    fun `with no reference the plane faces the camera through the pivot at the asked depth`() {
        val c = cam()
        val world = DrawPlane.world(c, null, floatArrayOf(500f, 800f, 800f), 1200f)!!
        assertEquals(1200f, depthOf(world, c), eps)
        // Facing the camera: its normal is the view direction, and a pixel is one on screen (1:1 at that depth).
        val n = normalOf(world)
        for (i in 0..2) assertEquals(c.forward[i], n[i], 1e-3f)
        assertEquals(1200f / c.focal, SceneMath.poseOf(world, w, h)!!.scale, 1e-3f)
    }

    @Test
    fun `the automatic depth is the anchor's own distance, so a drag starts from where the plane is`() {
        val c = cam()
        val pivot = floatArrayOf(500f, 800f, 800f)
        val d = DrawPlane.anchorDepth(null, c, pivot)
        assertEquals(c.toCamera(pivot)[2], d, 1e-3f)
        val world = DrawPlane.world(c, null, pivot, d)!!
        for (i in 0..2) assertEquals(pivot[i], world.apply(w / 2f, h / 2f, 0f)[i], eps)
    }

    @Test
    fun `a reference plane is kept parallel, only its distance changes and the art is not touched`() {
        // A reference tilted 40 degrees about Y, seen from the default camera.
        val reference = SceneMath.model(PlanePose(rotationY = 40f, positionZ = 100f), w, h)
        val c = cam()
        val z0 = depthOf(reference, c)
        val moved = DrawPlane.world(c, reference, null, z0 * 0.5f)!!
        assertEquals(z0 * 0.5f, depthOf(moved, c), eps)
        // Parallel: the same normal direction up to sign, and the same in-plane axes.
        val a = normalOf(reference)
        val b = normalOf(moved)
        assertEquals(1f, kotlin.math.abs(SceneMath.dot(a, b)), 1e-3f)
        val u0 = SceneMath.normalize(reference.column(0))
        val u1 = SceneMath.normalize(moved.column(0))
        assertEquals(1f, SceneMath.dot(u0, u1), 1e-3f)
        // The reference itself is not changed (a model is immutable; its box centre still maps where it did).
        assertEquals(z0, depthOf(reference, c), eps)
        // The box keeps its size on screen at the new depth: scale grows with the distance.
        val k = (z0 * 0.5f) / z0
        assertEquals(SceneMath.poseOf(reference, w, h)!!.scale * k, SceneMath.poseOf(moved, w, h)!!.scale, 1e-3f)
    }

    @Test
    fun `a reference seen edge-on falls back to the camera-facing plane`() {
        val edgeOn = SceneMath.model(PlanePose(rotationY = 89f), w, h)
        val c = cam()
        val world = DrawPlane.world(c, edgeOn, floatArrayOf(500f, 800f, 800f), 1000f)!!
        val n = normalOf(world)
        for (i in 0..2) assertEquals(c.forward[i], n[i], 1e-3f)
    }

    @Test
    fun `an orbited camera gets the plane in its own frame, and a pose that rebuilds the same plane`() {
        val camera = SceneCamera().orbit(0.8f, 0.2f)
        val c = cam(camera)
        val pivot = floatArrayOf(500f, 800f, 800f)
        val pose = DrawPlane.pose(c, emptyList(), null, pivot, 1500f)
        assertNotNull(pose)
        val rebuilt = SceneMath.model(pose!!, w, h)
        assertEquals(1500f, depthOf(rebuilt, c), 1f)
    }

    @Test
    fun `a parent group's pose is divided out so the child lands on the plane in scene space`() {
        val c = cam()
        val parent = PlanePose(offsetX = 30f, positionZ = 50f, rotationY = 20f, scale = 1.3f)
        val pose = DrawPlane.pose(c, listOf(parent), null, floatArrayOf(500f, 800f, 800f), 1000f)!!
        val scene = SceneMath.chainModel(listOf(parent, pose), w, h)
        assertEquals(1000f, depthOf(scene, c), 1f)
    }

    @Test
    fun `the guide is a grid on the plane whose cells shrink as the plane moves away`() {
        val c = cam()
        val near = DrawPlane.world(c, null, null, 600f)!!
        val far = DrawPlane.world(c, null, null, 2400f)!!
        val nearLines = DrawPlane.guideSegments(near, c)
        val farLines = DrawPlane.guideSegments(far, c)
        assertTrue(nearLines.isNotEmpty() && farLines.isNotEmpty())
        // Cell pitch on screen: the gap between two adjacent vertical lines.
        fun pitch(lines: List<FloatArray>): Float {
            val xs = lines.filter { kotlin.math.abs(it[0] - it[2]) < 0.5f }.map { it[0] }.sorted()
            return xs.zipWithNext { a, b -> b - a }.filter { it > 0.5f }.min()
        }
        assertEquals(4f, pitch(nearLines) / pitch(farLines), 0.05f)
    }

    @Test
    fun `the effective depth is the automatic one times the factor, within the range`() {
        val focal = SceneMath.focalLength(h)
        val auto = focal * 2f
        assertEquals(auto, DrawPlane.effectiveDepth(auto, 1f, focal), 1e-3f)
        assertEquals(auto * 0.5f, DrawPlane.effectiveDepth(auto, 0.5f, focal), 1e-3f)
        assertEquals(DrawPlane.depthRange(focal).start, DrawPlane.effectiveDepth(auto, 0.0001f, focal), 0f)
        assertEquals(DrawPlane.depthRange(focal).endInclusive, DrawPlane.effectiveDepth(auto, 1e6f, focal), 0f)
    }

    @Test
    fun `an edge-on reference measures the automatic depth at the pivot, so a factor of one is no jump`() {
        val edgeOn = SceneMath.model(PlanePose(rotationY = 89f), w, h)
        val c = cam()
        val pivot = floatArrayOf(500f, 800f, 800f)
        val auto = DrawPlane.anchorDepth(edgeOn, c, pivot)
        assertEquals(c.toCamera(pivot)[2], auto, 1e-3f)
        val world = DrawPlane.world(c, edgeOn, pivot, auto)!!
        for (i in 0..2) assertEquals(pivot[i], world.apply(w / 2f, h / 2f, 0f)[i], eps)
    }

    @Test
    fun `a usable reference measures the automatic depth at its own centre and k of one leaves it in place`() {
        val reference = SceneMath.model(PlanePose(rotationY = 40f, positionZ = 100f), w, h)
        val c = cam()
        val auto = DrawPlane.anchorDepth(reference, c, floatArrayOf(500f, 800f, 800f))
        assertEquals(depthOf(reference, c), auto, 1e-3f)
        val world = DrawPlane.world(c, reference, null, auto)!!
        val at = reference.apply(w / 2f, h / 2f, 0f)
        for (i in 0..2) assertEquals(at[i], world.apply(w / 2f, h / 2f, 0f)[i], eps)
    }
}
