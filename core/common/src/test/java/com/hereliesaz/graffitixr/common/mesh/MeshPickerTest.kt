package com.hereliesaz.graffitixr.common.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ray picking and UV resolution — the arithmetic that decides where paint lands on a model, and the
 * part of surface painting most likely to be subtly wrong in a way only a device would reveal.
 */
class MeshPickerTest {

    /**
     * A unit quad on the XY plane at z=0, UV-mapped corner to corner. Small enough to reason about
     * by hand: (0,0) is UV (0,1) after the parser's V flip, (1,1) is UV (1,0).
     */
    private val quad: Mesh = ObjParser.parse(
        """
        v 0 0 0
        v 1 0 0
        v 1 1 0
        v 0 1 0
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        vn 0 0 1
        f 1/1/1 2/2/1 3/3/1
        f 1/1/1 3/3/1 4/4/1
        """.trimIndent(),
    )

    /** A ray pointing straight down −Z at (x, y), i.e. looking at the quad face-on. */
    private fun rayAt(x: Float, y: Float) =
        MeshPicker.Ray(floatArrayOf(x, y, 5f), floatArrayOf(0f, 0f, -1f))

    // ── Raycasting ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a ray through the quad hits it`() {
        val hit = MeshPicker.raycast(quad, rayAt(0.5f, 0.5f))
        assertNotNull(hit)
        assertEquals(5f, hit!!.distance, 1e-4f)
    }

    @Test
    fun `a ray beside the quad misses`() {
        assertNull(MeshPicker.raycast(quad, rayAt(5f, 5f)))
        assertNull(MeshPicker.raycast(quad, rayAt(-0.5f, 0.5f)))
    }

    @Test
    fun `a ray pointing away from the quad misses`() {
        // Geometry behind the eye must never be picked, or a tap would paint something off-screen.
        val backwards = MeshPicker.Ray(floatArrayOf(0.5f, 0.5f, 5f), floatArrayOf(0f, 0f, 1f))
        assertNull(MeshPicker.raycast(quad, backwards))
    }

    @Test
    fun `a ray parallel to the surface misses rather than dividing by zero`() {
        val parallel = MeshPicker.Ray(floatArrayOf(-5f, 0.5f, 0f), floatArrayOf(1f, 0f, 0f))
        // Whatever it returns it must not be NaN or an exception.
        val hit = MeshPicker.raycast(quad, parallel)
        if (hit != null) assertTrue(hit.distance.isFinite())
    }

    @Test
    fun `the nearest surface wins when the ray passes through two`() {
        // Painting must land on the face you can see, not one behind it.
        val near = ObjParser.parse("v 0 0 1\nv 1 0 1\nv 0 1 1\nf 1 2 3")
        val far = ObjParser.parse("v 0 0 -1\nv 1 0 -1\nv 0 1 -1\nf 1 2 3")
        val both = Mesh(
            vertices = near.vertices + far.vertices,
            indices = near.indices + far.indices.map { it + near.vertexCount }.toIntArray(),
        )
        val hit = MeshPicker.raycast(both, rayAt(0.2f, 0.2f))!!
        // Origin z=5, near plane at z=1 → distance 4.
        assertEquals(4f, hit.distance, 1e-3f)
    }

    @Test
    fun `back faces are pickable too`() {
        // Scanned meshes routinely have inconsistent winding; skipping back faces would make the
        // brush silently stop working in patches.
        val backwardWound = ObjParser.parse("v 0 0 0\nv 0 1 0\nv 1 0 0\nf 1 2 3")
        assertNotNull(MeshPicker.raycast(backwardWound, rayAt(0.2f, 0.2f)))
    }

    @Test
    fun `an empty mesh is picked safely`() {
        assertNull(MeshPicker.raycast(Mesh(FloatArray(0), IntArray(0)), rayAt(0f, 0f)))
    }

    // ── UV resolution ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a hit at a corner resolves to that corner's uv`() {
        // Vertex (0,0) carries vt 0 0, which the parser flips to v=1.
        val hit = MeshPicker.raycast(quad, rayAt(0.001f, 0.001f))!!
        assertEquals(0f, hit.u, 0.01f)
        assertEquals(1f, hit.v, 0.01f)
    }

    @Test
    fun `a hit at the centre resolves to the middle of the uv space`() {
        val hit = MeshPicker.raycast(quad, rayAt(0.5f, 0.5f))!!
        assertEquals(0.5f, hit.u, 0.01f)
        assertEquals(0.5f, hit.v, 0.01f)
    }

    @Test
    fun `uv interpolates linearly across the surface`() {
        // The property painting depends on: moving the finger a quarter of the way across the model
        // must move the paint a quarter of the way across the texture.
        val quarter = MeshPicker.raycast(quad, rayAt(0.25f, 0.5f))!!
        val threeQuarter = MeshPicker.raycast(quad, rayAt(0.75f, 0.5f))!!
        assertEquals(0.25f, quarter.u, 0.02f)
        assertEquals(0.75f, threeQuarter.u, 0.02f)
    }

    @Test
    fun `every hit lands inside the unit uv square`() {
        for (i in 1..9) {
            for (j in 1..9) {
                val hit = MeshPicker.raycast(quad, rayAt(i / 10f, j / 10f)) ?: continue
                assertTrue("u out of range at $i,$j: ${hit.u}", hit.u in -0.001f..1.001f)
                assertTrue("v out of range at $i,$j: ${hit.v}", hit.v in -0.001f..1.001f)
            }
        }
    }

    // ── Unprojection ─────────────────────────────────────────────────────────────────────────

    private val camera = OrbitCamera(
        target = floatArrayOf(0.5f, 0.5f, 0f),
        distance = 4f,
        yaw = 0f,
        pitch = 0f,
    )

    @Test
    fun `a tap at the centre of the screen picks the centre of the model`() {
        val hit = MeshPicker.pick(
            quad, screenX = 500f, screenY = 500f,
            viewportWidth = 1000f, viewportHeight = 1000f,
            view = camera.viewMatrix(), projection = camera.projectionMatrix(1f),
        )
        assertNotNull("centre tap should hit the quad", hit)
        assertEquals(0.5f, hit!!.u, 0.03f)
        assertEquals(0.5f, hit.v, 0.03f)
    }

    @Test
    fun `screen Y is flipped so up on screen is up on the model`() {
        // Screen Y grows downward, NDC Y upward. Getting this backwards mirrors every stroke
        // vertically — a bug that looks almost right.
        val view = camera.viewMatrix()
        val proj = camera.projectionMatrix(1f)
        // Kept near the centre on purpose: at distance 4 with a 45° field of view the viewport
        // spans ~3.3 world units, so a tap 200px off centre would fall clean off a 1-unit quad.
        val upper = MeshPicker.pick(quad, 500f, 450f, 1000f, 1000f, view, proj)!!
        val lower = MeshPicker.pick(quad, 500f, 550f, 1000f, 1000f, view, proj)!!
        // Higher on screen = larger world Y = smaller texture V (the parser flipped V).
        assertTrue("expected upper tap to have smaller v, got ${upper.v} vs ${lower.v}", upper.v < lower.v)
    }

    @Test
    fun `a tap off the model resolves to no hit`() {
        val hit = MeshPicker.pick(
            quad, screenX = 5f, screenY = 5f,
            viewportWidth = 1000f, viewportHeight = 1000f,
            view = camera.viewMatrix(), projection = camera.projectionMatrix(1f),
        )
        assertNull(hit)
    }

    @Test
    fun `unprojection produces a normalised ray pointing into the scene`() {
        val ray = MeshPicker.unproject(
            500f, 500f, 1000f, 1000f, camera.viewMatrix(), camera.projectionMatrix(1f),
        )!!
        val d = ray.direction
        val len = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        assertEquals(1f, len, 1e-4f)
        // Camera sits at +Z looking toward the origin, so the ray runs in −Z.
        assertTrue("ray should point into the scene, got ${d.toList()}", d[2] < 0f)
    }

    @Test
    fun `a degenerate viewport is rejected rather than dividing by zero`() {
        assertNull(MeshPicker.unproject(0f, 0f, 0f, 100f, camera.viewMatrix(), camera.projectionMatrix(1f)))
        assertNull(MeshPicker.unproject(0f, 0f, 100f, 0f, camera.viewMatrix(), camera.projectionMatrix(1f)))
    }

    @Test
    fun `picking works from any orbit angle`() {
        // The whole point of painting on a model: rotate it, paint the other side.
        listOf(0f, 0.6f, -0.6f, 2.4f).forEach { yaw ->
            val cam = camera.copy(yaw = yaw)
            val hit = MeshPicker.pick(
                quad, 500f, 500f, 1000f, 1000f, cam.viewMatrix(), cam.projectionMatrix(1f),
            )
            // Edge-on the quad may genuinely not be hit; when it is, the UV must stay sane.
            if (hit != null) {
                assertTrue("yaw=$yaw u=${hit.u}", hit.u in -0.001f..1.001f)
                assertTrue("yaw=$yaw v=${hit.v}", hit.v in -0.001f..1.001f)
            }
        }
    }

    // ── Matrix helpers ───────────────────────────────────────────────────────────────────────

    @Test
    fun `inverting a matrix and multiplying back gives identity`() {
        val m = MeshPicker.multiply(camera.projectionMatrix(1.7f), camera.viewMatrix())
        val product = MeshPicker.multiply(m, MeshPicker.invert(m)!!)
        for (col in 0..3) {
            for (row in 0..3) {
                val expected = if (col == row) 1f else 0f
                assertEquals("[$row][$col]", expected, product[col * 4 + row], 1e-3f)
            }
        }
    }

    @Test
    fun `a singular matrix inverts to null rather than NaN`() {
        assertNull(MeshPicker.invert(FloatArray(16)))
    }

    // ── Scale independence ───────────────────────────────────────────────────────────────────

    /** The unit quad, authored at [scale] (ObjParser does not normalise it). */
    private fun quadAt(scale: Float): Mesh = ObjParser.parse(
        """
        v 0 0 0
        v $scale 0 0
        v $scale $scale 0
        v 0 $scale 0
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        vn 0 0 1
        f 1/1/1 2/2/1 3/3/1
        f 1/1/1 3/3/1 4/4/1
        """.trimIndent(),
    )

    @Test
    fun `a model authored at any scale can be picked`() {
        // Edges of 1e-4 give a determinant of about 1e-8, under the old absolute 1e-7 epsilon, so every
        // triangle of a small-unit model was rejected and it could not be painted at all.
        for (scale in floatArrayOf(1e-4f, 1e-3f, 1f, 1e3f)) {
            val mesh = quadAt(scale)
            val ray = MeshPicker.Ray(floatArrayOf(0.25f * scale, 0.5f * scale, 5f * scale), floatArrayOf(0f, 0f, -1f))
            val hit = MeshPicker.raycast(mesh, ray)
            assertNotNull("no hit at scale $scale", hit)
            assertEquals("u at scale $scale", 0.25f, hit!!.u, 1e-3f)
            assertEquals("v at scale $scale", 0.5f, 1f - hit.v, 1e-3f)
            assertEquals("distance at scale $scale", 5f * scale, hit.distance, 5f * scale * 1e-4f)
        }
    }

    @Test
    fun `a small model is still missed beside its edge`() {
        val mesh = quadAt(1e-4f)
        val miss = MeshPicker.Ray(floatArrayOf(2e-4f, 0.5e-4f, 5e-4f), floatArrayOf(0f, 0f, -1f))
        assertNull(MeshPicker.raycast(mesh, miss))
    }

    @Test
    fun `grazing rays hit at every scale`() {
        // A ray 0.5 degrees off the surface plane (about 9e-3 rad) is a valid, if flat, view of it.
        val angle = 0.5 * Math.PI / 180.0
        for (scale in floatArrayOf(1e-4f, 1e-3f, 1f, 1e3f)) {
            val mesh = quadAt(scale)
            val dir = floatArrayOf(0f, Math.cos(angle).toFloat(), -Math.sin(angle).toFloat())
            // Aim at the quad's centre from below and in front, travelling mostly along +y.
            val t = 0.4f * scale / Math.sin(angle).toFloat()
            val centre = floatArrayOf(0.5f * scale, 0.5f * scale, 0f)
            val origin = FloatArray(3) { centre[it] - dir[it] * t }
            val hit = MeshPicker.raycast(mesh, MeshPicker.Ray(origin, dir))
            assertNotNull("no grazing hit at scale $scale", hit)
            assertEquals("u at scale $scale", 0.5f, hit!!.u, 2e-2f)
        }
    }

    @Test
    fun `a ray exactly parallel to the surface misses at every scale`() {
        for (scale in floatArrayOf(1e-4f, 1f, 1e3f)) {
            val ray = MeshPicker.Ray(floatArrayOf(-scale, 0.5f * scale, 0f), floatArrayOf(1f, 0f, 0f))
            assertNull("scale $scale", MeshPicker.raycast(quadAt(scale), ray))
        }
    }

    @Test
    fun `a ray through a shared edge still lands on one of the triangles`() {
        // The quad's diagonal is the edge shared by its two triangles; a crack there would read as a
        // line the brush cannot cross.
        for (scale in floatArrayOf(1e-4f, 1f, 1e3f)) {
            val ray = MeshPicker.Ray(floatArrayOf(0.5f * scale, 0.5f * scale, 5f * scale), floatArrayOf(0f, 0f, -1f))
            assertNotNull("scale $scale", MeshPicker.raycast(quadAt(scale), ray))
        }
    }

    // ── Facing ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a hit reports whether the camera sees the front or the back`() {
        // The quad's normal is +z; rayAt looks down -z from +z, so it sees the front.
        assertTrue(MeshPicker.raycast(quad, rayAt(0.5f, 0.5f))!!.frontFacing)
        val fromBehind = MeshPicker.Ray(floatArrayOf(0.5f, 0.5f, -5f), floatArrayOf(0f, 0f, 1f))
        assertFalse(MeshPicker.raycast(quad, fromBehind)!!.frontFacing)
    }

    @Test
    fun `facing follows the vertex normals, not a scan's inconsistent winding`() {
        // Wound clockwise, but every vertex normal says +z: the outside is +z, so a camera on +z sees
        // the front however the triangle happens to be wound.
        val mesh = ObjParser.parse("v 0 0 0\nv 0 1 0\nv 1 0 0\nvn 0 0 1\nf 1//1 2//1 3//1")
        assertTrue(MeshPicker.facesRay(mesh, 0, floatArrayOf(0f, 0f, -1f)))
        assertFalse(MeshPicker.facesRay(mesh, 0, floatArrayOf(0f, 0f, 1f)))
    }
}
