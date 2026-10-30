package com.hereliesaz.graffitixr.common.mesh

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent analytic fixtures: these expected coordinates do not call the guide under test. */
class SpatialGuideTest {
    private val front = OrbitCamera(
        target = floatArrayOf(0f, 0f, 0f), distance = 3f, yaw = 0f, pitch = 0f,
        fovYDegrees = 90f,
    )

    private fun hit(g: SpatialGuide, x: Float = 100f, y: Float = 100f,
        camera: OrbitCamera = front): SpatialPoint? =
        SpatialGuideSurface.intersect(g, camera, x, y, 200f, 200f)

    @Test
    fun `default guide retains the original camera-facing origin`() {
        val p = hit(SpatialGuide())!!
        assertEquals(0f, p.x, 1e-4f)
        assertEquals(0f, p.y, 1e-4f)
        assertEquals(0f, p.z, 1e-4f)
        assertEquals(1f, p.pressure, 0f)
    }

    @Test
    fun `view plane depth and position move the next drawing surface`() {
        val guide = SpatialGuide(depth = -1f, center = SpatialPoint(0f, 0f, 0.5f))
        // Forward is -Z. Negative depth moves the target plane towards the eye by one.
        assertEquals(1.5f, hit(guide)!!.z, 0.001f)
    }

    @Test
    fun `bounded fixed plane refuses points beyond its edge`() {
        val guide = SpatialGuide(kind = SpatialGuideKind.PLANE, radius = 1f)
        assertEquals(0f, hit(guide)!!.z, 0.001f)
        // FOV 90°, distance 3: x=150 corresponds to world x=+1.5 at z=0.
        assertNull(hit(guide, 150f))
    }

    @Test
    fun `yaw rotates a fixed plane into the world x normal`() {
        val camera = front.orbit((Math.PI / 2).toFloat(), 0f)
        val guide = SpatialGuide(
            kind = SpatialGuideKind.PLANE,
            center = SpatialPoint(1f, 0f, 0f), yaw = (Math.PI / 2).toFloat(),
        )
        val point = hit(guide, camera = camera)!!
        assertEquals(1f, point.x, 0.001f)
        assertEquals(0f, point.y, 0.001f)
        assertEquals(0f, point.z, 0.001f)
    }

    @Test
    fun `sphere paint hits near surface and rejects background`() {
        val near = hit(SpatialGuide(kind = SpatialGuideKind.SPHERE))!!
        assertEquals(1f, near.z, 0.001f)
        assertEquals(2f, hit(SpatialGuide(kind = SpatialGuideKind.SPHERE, radius = 2f))!!.z, 0.001f)
        assertNull(hit(SpatialGuide(kind = SpatialGuideKind.SPHERE), x = 175f))
    }

    @Test
    fun `cylinder paint follows the curved side and respects finite height`() {
        val guide = SpatialGuide(kind = SpatialGuideKind.CYLINDER, radius = 1f, height = 2f)
        val point = hit(guide)!!
        assertEquals(0f, point.x, 0.001f)
        assertEquals(0f, point.y, 0.001f)
        assertEquals(1f, point.z, 0.001f)
        // At a raised ray, the cylinder's cap is NOT painted as if it were the curved wall.
        assertNull(hit(guide, y = 25f))
    }

    @Test
    fun `fixed guides do not move when the camera orbits`() {
        val fixed = SpatialGuide(kind = SpatialGuideKind.PLANE, center = SpatialPoint(0.5f, 0f, 0f))
        val original = SpatialGuideSurface.wireframe(fixed, front).first()
        val rotated = SpatialGuideSurface.wireframe(fixed, front.orbit(0.5f, 0.2f)).first()
        assertEquals(original, rotated)
        val view = SpatialGuide()
        val before = SpatialGuideSurface.wireframe(view, front).first()
        val after = SpatialGuideSurface.wireframe(view, front.orbit(0.5f, 0f)).first()
        assertTrue(before != after)
    }

    @Test
    fun `sphere guide wireframe vertices lie on its analytic surface`() {
        val center = SpatialPoint(0.5f, -0.25f, 0.75f)
        val guide = SpatialGuide(kind = SpatialGuideKind.SPHERE, center = center, radius = 1.5f)
        val lines = SpatialGuideSurface.wireframe(guide, front)
        assertTrue(lines.isNotEmpty())
        for ((a, b) in lines) {
            for (p in listOf(a, b)) {
                val dist2 = (p.x - center.x) * (p.x - center.x) +
                    (p.y - center.y) * (p.y - center.y) + (p.z - center.z) * (p.z - center.z)
                assertEquals(2.25f, dist2, 0.002f)
            }
        }
    }

    @Test
    fun `cylinder wireframe vertices remain within its height`() {
        val guide = SpatialGuide(kind = SpatialGuideKind.CYLINDER, radius = 0.75f, height = 1.5f)
        for ((a, b) in SpatialGuideSurface.wireframe(guide, front)) {
            for (p in listOf(a, b)) {
                assertEquals(0.5625f, p.x * p.x + p.z * p.z, 0.002f)
                assertTrue(p.y >= -0.7501f && p.y <= 0.7501f)
            }
        }
    }

    @Test
    fun `invalid geometry and invisible guides cannot draw wireframes`() {
        val invalid = SpatialGuide(kind = SpatialGuideKind.SPHERE, radius = 0f)
        assertNull(hit(invalid))
        assertTrue(SpatialGuideSurface.wireframe(invalid, front).isEmpty())
        assertTrue(SpatialGuideSurface.wireframe(SpatialGuide(visible = false), front).isEmpty())
        assertNull(SpatialGuideSurface.intersect(SpatialGuide(), front, 0f, 0f, 0f, 200f))
    }

    @Test
    fun `guide setting serialization preserves the full editable transform`() {
        val expected = SpatialGuide(
            kind = SpatialGuideKind.CYLINDER, center = SpatialPoint(1f, -2f, 0.5f),
            radius = 1.25f, height = 3.5f, yaw = 0.9f, pitch = 0.4f, visible = false,
        )
        assertEquals(expected, Json.decodeFromString<SpatialGuide>(Json.encodeToString(expected)))
    }
}
