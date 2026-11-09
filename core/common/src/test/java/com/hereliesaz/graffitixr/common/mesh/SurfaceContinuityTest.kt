package com.hereliesaz.graffitixr.common.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two hits on one UV island are not always one stretch of surface: a strip folded over itself is a
 * single island, and the camera ray can drop from the front panel onto the back one.
 */
class SurfaceContinuityTest {

    private class Face(val corners: List<List<Float>>)

    /**
     * A strip folded in two and mapped continuously, so it is ONE island: a front panel that stops at
     * x = 0.2 (u 0.4 down to 0 as x rises), the fold at x = -1, and a back panel behind it that runs
     * on to x = 1 (u 0.5..0.9). Looking down -z, a ray that crosses x = 0.2 drops from the front panel
     * onto the back one.
     */
    private fun foldedStrip(): Mesh {
        // Each corner is x, y, z, u, v.
        fun q(vararg c: Float) = Face(c.toList().chunked(5))
        val faces = listOf(
            q(
                0.2f, -1f, 0f, 0f, 0f,
                -1f, -1f, 0f, 0.4f, 0f,
                -1f, 1f, 0f, 0.4f, 1f,
                0.2f, 1f, 0f, 0f, 1f,
            ),
            q(
                -1f, -1f, 0f, 0.4f, 0f,
                -1f, -1f, -0.2f, 0.5f, 0f,
                -1f, 1f, -0.2f, 0.5f, 1f,
                -1f, 1f, 0f, 0.4f, 1f,
            ),
            q(
                -1f, -1f, -0.2f, 0.5f, 0f,
                1f, -1f, -0.2f, 0.9f, 0f,
                1f, 1f, -0.2f, 0.9f, 1f,
                -1f, 1f, -0.2f, 0.5f, 1f,
            ),
        )
        val verts = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (f in faces) {
            val base = verts.size / Mesh.FLOATS_PER_VERTEX
            for (c in f.corners) verts += listOf(c[0], c[1], c[2], 0f, 0f, 1f, c[3], c[4])
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return Mesh(verts.toFloatArray(), idx.toIntArray())
    }

    private fun hit(mesh: Mesh, x: Float, y: Float): MeshPicker.Hit {
        val h = MeshPicker.raycast(mesh, MeshPicker.Ray(floatArrayOf(x, y, 5f), floatArrayOf(0f, 0f, -1f)))
        assertNotNull("no hit at ($x, $y)", h)
        return h!!
    }

    @Test
    fun `the folded strip is one island, which is why island membership alone is not enough`() {
        val mesh = foldedStrip()
        assertEquals(1, UvIslands.of(mesh).count)
    }

    @Test
    fun `dropping from the front panel onto the back one is not continuous`() {
        val mesh = foldedStrip()
        val front = hit(mesh, 0.1f, 0f)
        val back = hit(mesh, 0.3f, 0f)
        assertEquals("front panel", 0.0333f, front.u, 1e-3f)
        assertEquals("back panel", 0.76f, back.u, 1e-3f)
        assertTrue(UvIslands.of(mesh).sameIsland(front.triangleIndex, back.triangleIndex))
        assertFalse(SurfaceContinuity.continuous(mesh, UvIslands.of(mesh), front, back))
        assertFalse(SurfaceContinuity.continuous(mesh, UvIslands.of(mesh), back, front))
    }

    @Test
    fun `neighbouring hits on one panel are continuous, even across a fast move`() {
        val mesh = foldedStrip()
        val a = hit(mesh, -0.9f, -0.5f)
        // Across most of the panel, on different triangles.
        val b = hit(mesh, 0.1f, 0.5f)
        assertTrue(a.triangleIndex != b.triangleIndex)
        assertTrue(SurfaceContinuity.continuous(mesh, UvIslands.of(mesh), a, b))
        assertTrue(SurfaceContinuity.continuous(mesh, UvIslands.of(mesh), a, a))
    }

    @Test
    fun `a ridge in a continuous surface does not split the stroke`() {
        // Two quads at 90 degrees, one island: the way over the ridge is 1.41x the straight line.
        val verts = floatArrayOf(
            -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 1f, 0.5f, 0f,
            0f, 1f, 0f, 0f, 0f, 1f, 0.5f, 1f,
            -1f, 1f, 0f, 0f, 0f, 1f, 0f, 1f,
            0f, 0f, -1f, 0f, 0f, 1f, 1f, 0f,
            0f, 1f, -1f, 0f, 0f, 1f, 1f, 1f,
        )
        val mesh = Mesh(verts, intArrayOf(0, 1, 2, 0, 2, 3, 1, 4, 5, 1, 5, 2))
        assertEquals(1, UvIslands.of(mesh).count)
        val down = floatArrayOf(0f, 0f, -1f)
        val left = floatArrayOf(-1f, 0f, 0f)
        val onFlat = MeshPicker.raycast(mesh, MeshPicker.Ray(floatArrayOf(-0.5f, 0.5f, 5f), down))!!
        val onWall = MeshPicker.raycast(mesh, MeshPicker.Ray(floatArrayOf(5f, 0.5f, -0.5f), left))!!
        assertTrue(SurfaceContinuity.continuous(mesh, UvIslands.of(mesh), onFlat, onWall))
    }

    @Test
    fun `uv per length is the stretched axis, not the average`() {
        // Right triangle with unit legs in space, UV u across 1 and v across 0.1.
        val verts = floatArrayOf(
            0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f,
            0f, 1f, 0f, 0f, 0f, 1f, 0f, 0.1f,
        )
        val mesh = Mesh(verts, intArrayOf(0, 1, 2))
        assertEquals(1.0, SurfaceContinuity.uvPerLength(mesh, 0), 1e-6)
        val half = Mesh(verts.copyOf().also { it[14] = 0.25f; it[23] = 0.25f }, intArrayOf(0, 1, 2))
        assertEquals(0.25, SurfaceContinuity.uvPerLength(half, 0), 1e-6)
    }

    @Test
    fun `a degenerate triangle has no scale and a hit with no position is never split`() {
        val flat = Mesh(FloatArray(24), intArrayOf(0, 1, 2))
        assertEquals(0.0, SurfaceContinuity.uvPerLength(flat, 0), 0.0)
        val a = MeshPicker.Hit(0, 1f, 0f, 0f)
        val b = MeshPicker.Hit(1, 1f, 0.9f, 0.9f)
        assertTrue(SurfaceContinuity.continuous(flat, UvIslands.of(flat), a, b))
    }

    /**
     * Coplanar and seamless, with a narrow strip (x -0.004..0.004) mapped much more densely than the
     * triangles either side of it: outer UV-per-length 0.1, strip 12.5. Codex's numbers.
     */
    private fun denseStripMesh(): Mesh {
        val xs = floatArrayOf(-1f, -0.004f, 0.004f, 1f)
        val us = floatArrayOf(0.3502f, 0.4498f, 0.5502f, 0.6498f)
        val verts = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (q in 0 until 3) {
            val base = verts.size / Mesh.FLOATS_PER_VERTEX
            for ((x, u, y) in listOf(Triple(xs[q], us[q], 0f), Triple(xs[q + 1], us[q + 1], 0f),
                Triple(xs[q + 1], us[q + 1], 1f), Triple(xs[q], us[q], 1f))) {
                verts += listOf(x, y, 0f, 0f, 0f, 1f, u, y * 0.05f)
            }
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        return Mesh(verts.toFloatArray(), idx.toIntArray())
    }

    @Test
    fun `a dense strip between the two hits does not make a seamless move discontinuous`() {
        val mesh = denseStripMesh()
        val islands = UvIslands.of(mesh)
        assertEquals(1, islands.count)
        val a = hit(mesh, -0.005f, 0.5f)
        val b = hit(mesh, 0.005f, 0.5f)
        assertEquals(0.4497f, a.u, 1e-4f)
        assertEquals(0.5503f, b.u, 1e-4f)
        // The end triangles are 0.1 per length: on their own they bound 0.1006 of UV by about 0.002.
        assertEquals(0.1, SurfaceContinuity.uvPerLength(mesh, a.triangleIndex), 1e-3)
        assertTrue(SurfaceContinuity.continuous(mesh, islands, a, b))
        assertTrue(SurfaceContinuity.continuous(mesh, islands, b, a))
    }

    @Test
    fun `the occlusion drop is still discontinuous when the surface between is searched`() {
        val mesh = foldedStrip()
        val islands = UvIslands.of(mesh)
        assertFalse(SurfaceContinuity.continuous(mesh, islands, hit(mesh, 0.1f, 0f), hit(mesh, 0.3f, 0f)))
    }
}
