package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialGuideSurface
import com.hereliesaz.graffitixr.common.mesh.SpatialView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneMathTest {
    private val w = 1080f
    private val h = 1920f
    private val eps = 1e-2f

    private fun pixel(hm: FloatArray, u: Float, v: Float) = SceneMath.applyHomography(hm, u, v)!!

    @Test
    fun `the default camera projects a flat layer exactly where its 2D transform puts it`() {
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val pose = PlanePose(offsetX = 40f, offsetY = -70f, rotationZ = 30f, scale = 1.5f)
        val hm = SceneMath.homography(SceneMath.model(pose, w, h), cam)
        // The 2D transform: about the centre, scale, rotate (clockwise in y-down), then offset.
        val a = Math.toRadians(30.0)
        for ((u, v) in listOf(0f to 0f, 300f to 900f, w to h)) {
            val dx = (u - w / 2) * 1.5f
            val dy = (v - h / 2) * 1.5f
            val ex = (w / 2 + 40f + dx * Math.cos(a) - dy * Math.sin(a)).toFloat()
            val ey = (h / 2 - 70f + dx * Math.sin(a) + dy * Math.cos(a)).toFloat()
            val (x, y) = pixel(hm, u, v)
            assertEquals(ex, x, eps)
            assertEquals(ey, y, eps)
        }
        // Affine: no perspective row.
        assertEquals(0f, hm[6], 1e-6f)
        assertEquals(0f, hm[7], 1e-6f)
    }

    @Test
    fun `depth shrinks a plane about the view centre`() {
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val f = SceneMath.focalLength(h)
        val hm = SceneMath.homography(SceneMath.model(PlanePose(positionZ = f), w, h), cam)
        // Twice as far: half the size about the centre.
        val (x, y) = pixel(hm, 0f, 0f)
        assertEquals(w / 4, x, eps)
        assertEquals(h / 4, y, eps)
    }

    @Test
    fun `tilt is a rotation, so a plane rotated 90 degrees about Y is seen edge-on`() {
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val hm = SceneMath.homography(SceneMath.model(PlanePose(rotationY = 90f), w, h), cam)
        val (x1, _) = pixel(hm, 100f, h / 2)
        val (x2, _) = pixel(hm, 400f, h / 2 + 300f)
        assertEquals(w / 2, x1, 0.5f)
        assertEquals(w / 2, x2, 0.5f)
    }

    @Test
    fun `a ray through a projected point lands back on the same layer pixel`() {
        val camera = SceneCamera(yaw = 0.5f, pitch = -0.3f, distanceScale = 1.4f, panX = 25f)
        val cam = SceneMath.frame(camera, w, h)
        val chain = listOf(
            PlanePose(offsetX = 30f, positionZ = 120f, rotationX = 15f),
            PlanePose(offsetX = -10f, offsetY = 60f, rotationX = 25f, rotationY = -40f, rotationZ = 10f, scale = 0.8f),
        )
        val model = SceneMath.chainModel(chain, w, h)
        val hm = SceneMath.homography(model, cam)
        for ((u, v) in listOf(540f to 960f, 200f to 300f, 900f to 1500f)) {
            val (x, y) = pixel(hm, u, v)
            val (lu, lv) = SceneMath.canvasToLocal(x, y, model, cam)!!
            assertEquals(u, lu, 0.05f)
            assertEquals(v, lv, 0.05f)
        }
    }

    @Test
    fun `painting maps a touch to the point the flat 2D pipeline expects`() {
        val camera = SceneCamera(yaw = -0.4f, pitch = 0.2f)
        val leaf = PlanePose(offsetX = 15f, offsetY = -5f, rotationX = 30f, rotationZ = 20f, scale = 2f)
        val cam = SceneMath.frame(camera, w, h)
        val hm = SceneMath.homography(SceneMath.model(leaf, w, h), cam)
        val (x, y) = pixel(hm, 700f, 800f)
        val (fx, fy) = SceneMath.worldToLayerFlat(x, y, listOf(leaf), camera, w, h)!!
        // The flat point of local (700, 800) under the leaf's 2D transform only.
        val flat = SceneMath.model(leaf.copy(rotationX = 0f), w, h).apply(700f, 800f, 0f)
        assertEquals(flat[0], fx, 0.05f)
        assertEquals(flat[1], fy, 0.05f)
    }

    @Test
    fun `at identity painting is unchanged`() {
        val (fx, fy) = SceneMath.worldToLayerFlat(123f, 456f, listOf(PlanePose(offsetX = 9f, scale = 3f)), SceneCamera(), w, h)!!
        assertEquals(123f, fx, eps)
        assertEquals(456f, fy, eps)
    }

    @Test
    fun `a ray that misses a plane behind the eye gives no point`() {
        val behind = PlanePose(positionZ = -SceneMath.focalLength(h) * 2f)
        assertNull(SceneMath.worldToLayerFlat(w / 2, h / 2, listOf(behind), SceneCamera(), w, h))
    }

    @Test
    fun `nested homographies compose back to the child's own`() {
        val cam = SceneMath.frame(SceneCamera(yaw = 0.3f), w, h)
        val parent = PlanePose(rotationY = 20f, positionZ = 100f)
        val child = PlanePose(offsetX = 50f, rotationX = -15f)
        val hp = SceneMath.homography(SceneMath.model(parent, w, h), cam)
        val hc = SceneMath.homography(SceneMath.chainModel(listOf(parent, child), w, h), cam)
        val rel = SceneMath.relativeHomography(hp, hc)!!
        val composed = SceneMath.mul3(hp, rel)
        val a = pixel(hc, 333f, 777f)
        val b = pixel(composed, 333f, 777f)
        assertEquals(a.first, b.first, 0.05f)
        assertEquals(a.second, b.second, 0.05f)
    }

    @Test
    fun `a plane crossing the eye is clipped to the part in front`() {
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val hm = SceneMath.homography(SceneMath.model(PlanePose(rotationX = 89f), w, h), cam)
        val poly = SceneMath.visiblePolygon(hm, cam, extent = 4f * h)
        assertNotNull(poly)
        assertTrue(poly!!.isNotEmpty())
        val near = SceneMath.nearPlane(cam.focal)
        poly.forEach { (u, v) -> assertTrue(hm[6] * u + hm[7] * v + hm[8] >= near - 1e-2f) }
        // A plane in plain view needs no clip.
        assertNull(SceneMath.visiblePolygon(SceneMath.homography(SceneMath.model(PlanePose(), w, h), cam), cam, w))
    }

    @Test
    fun `the GL clip matrix agrees with the canvas projection`() {
        val camera = SceneCamera(yaw = 0.7f, pitch = 0.25f, distanceScale = 0.9f)
        val cam = SceneMath.frame(camera, w, h)
        val model = SceneMath.model(PlanePose(positionZ = 50f, rotationY = 30f), w, h) * SceneMath.strokeToLocal(w, h)
        val m = SceneMath.clipMatrix(model, cam)
        val p = floatArrayOf(0.3f, -0.2f, 0.5f)
        val cx = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]
        val cy = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]
        val cw = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15]
        val (sx, sy) = cam.project(model.apply(p[0], p[1], p[2]))!!
        assertEquals(sx, (cx / cw + 1f) * w / 2f, 0.05f)
        assertEquals(sy, (1f - cy / cw) * h / 2f, 0.05f)
    }

    @Test
    fun `stroke units keep the old sketch's default view`() {
        // The old default: orbit camera at distance 3 looking at the origin; its centre and the
        // new scene's are the same pixel, and a unit right of the origin lands focal/3 px right.
        val cam = SceneMath.frame(SceneCamera(), w, h)
        val local = SceneMath.strokeToLocal(w, h)
        val (x0, y0) = cam.project(local.apply(0f, 0f, 0f))!!
        val (x1, y1) = cam.project(local.apply(1f, 1f, 0f))!!
        assertEquals(w / 2, x0, eps)
        assertEquals(h / 2, y0, eps)
        assertEquals(SceneMath.focalLength(h) / 3f, x1 - x0, eps)
        assertEquals(-SceneMath.focalLength(h) / 3f, y1 - y0, eps) // y up in stroke units
    }

    @Test
    fun `a view-plane guide hit through the ray API matches the orbit camera API`() {
        val camera = com.hereliesaz.graffitixr.common.mesh.OrbitCamera(pitch = 0f)
        val guide = SpatialGuide()
        val viaCamera = SpatialGuideSurface.intersect(guide, camera, 300f, 200f, 800f, 600f)!!
        val ray = com.hereliesaz.graffitixr.common.mesh.MeshPicker.unproject(
            300f, 200f, 800f, 600f, camera.viewMatrix(), camera.projectionMatrix(800f / 600f),
        )!!
        val viaRay = SpatialGuideSurface.intersectRay(guide, SpatialView.of(camera), ray.origin, ray.direction)!!
        assertEquals(viaCamera, viaRay)
    }

    @Test
    fun `camera orbit clamps pitch and dolly bounds distance`() {
        val c = SceneCamera().orbit(0f, 10f).dolly(1000f)
        assertEquals(SceneCamera.MAX_PITCH, c.pitch)
        assertEquals(SceneCamera.MIN_DISTANCE, c.distanceScale)
        assertTrue(SceneCamera().isDefault)
    }
}
