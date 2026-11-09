package com.hereliesaz.graffitixr.common.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UV islands: where a stroke may be joined, and what a dab is clipped to. */
class UvIslandsTest {

    private class Builder {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Int>()

        fun vertex(x: Float, y: Float, u: Float, v: Float): Int {
            verts += listOf(x, y, 0f, 0f, 0f, 1f, u, v)
            return verts.size / Mesh.FLOATS_PER_VERTEX - 1
        }

        /**
         * A unit-tall quad from x [x0] to [x1], its UVs running u [u0] to [u1] across v 0..1; its own
         * vertices.
         */
        fun quad(x0: Float, x1: Float, u0: Float, u1: Float) {
            val a = vertex(x0, 0f, u0, 0f)
            val b = vertex(x1, 0f, u1, 0f)
            val c = vertex(x1, 1f, u1, 1f)
            val d = vertex(x0, 1f, u0, 1f)
            idx += listOf(a, b, c, a, c, d)
        }

        fun mesh() = Mesh(verts.toFloatArray(), idx.toIntArray())
    }

    @Test
    fun `the two triangles of a quad are one island even with no shared vertices`() {
        val b = Builder()
        val p = listOf(
            Triple(0f, 0f, 0f to 0f), Triple(1f, 0f, 1f to 0f), Triple(1f, 1f, 1f to 1f), Triple(0f, 1f, 0f to 1f),
        )
        fun v(i: Int) = b.vertex(p[i].first, p[i].second, p[i].third.first, p[i].third.second)
        b.idx += listOf(v(0), v(1), v(2))
        b.idx += listOf(v(0), v(2), v(3)) // same corners again, as new vertices
        val islands = UvIslands.of(b.mesh())
        assertEquals(1, islands.count)
        assertTrue(islands.sameIsland(0, 1))
    }

    @Test
    fun `quads that touch in space but not in uv are separate islands`() {
        val b = Builder()
        b.quad(0f, 1f, 0.0f, 0.4f)
        b.quad(1f, 2f, 0.6f, 1.0f)
        val islands = UvIslands.of(b.mesh())
        assertEquals(2, islands.count)
        assertTrue(islands.sameIsland(0, 1))
        assertTrue(islands.sameIsland(2, 3))
        assertFalse(islands.sameIsland(1, 2))
    }

    @Test
    fun `a continuous uv across a shared edge is one island`() {
        val b = Builder()
        b.quad(0f, 1f, 0.0f, 0.5f)
        b.quad(1f, 2f, 0.5f, 1.0f)
        val islands = UvIslands.of(b.mesh())
        assertEquals(1, islands.count)
        assertTrue(islands.sameIsland(0, 3))
    }

    @Test
    fun `out of range triangles are in no island`() {
        val b = Builder()
        b.quad(0f, 1f, 0f, 1f)
        val islands = UvIslands.of(b.mesh())
        assertEquals(-1, islands.islandOf(99))
        assertFalse(islands.sameIsland(-1, -1))
        assertFalse(islands.sameIsland(0, 99))
    }

    @Test
    fun `an empty mesh has no islands`() {
        assertEquals(0, UvIslands.of(Mesh(FloatArray(0), IntArray(0))).count)
    }

    @Test
    fun `a tiled island is not in the unit square`() {
        val b = Builder()
        b.quad(0f, 1f, 0f, 3f)
        b.quad(2f, 3f, 0.1f, 0.9f)
        val islands = UvIslands.of(b.mesh())
        assertFalse(islands.inUnitSquare(islands.islandOf(0)))
        assertTrue(islands.inUnitSquare(islands.islandOf(2)))
    }

    @Test
    fun `triangles near a rectangle are only those of the island that reach it`() {
        val b = Builder()
        b.quad(0f, 1f, 0.0f, 0.4f)   // island 0, u in 0..0.4
        b.quad(1f, 2f, 0.6f, 1.0f)   // island 1, u in 0.6..1
        val islands = UvIslands.of(b.mesh())
        val near = islands.trianglesNear(0, UvIslands.UvBox(0.3f, 0.4f, 0.7f, 0.6f))
        assertEquals("both triangles of island 0 reach u 0.3..0.4", 12, near.size)
        // None of island 1's triangles come back for island 0, though the rectangle overlaps them.
        assertTrue(near.indices.filter { it % 2 == 0 }.all { near[it] <= 0.4f + 1e-6f })
        assertEquals(0, islands.trianglesNear(0, UvIslands.UvBox(0.5f, 0f, 0.55f, 1f)).size)
        assertEquals(0, islands.trianglesNear(7, UvIslands.UvBox(0f, 0f, 1f, 1f)).size)
    }

    @Test
    fun `triangles sharing an edge that agrees at only one end are separate islands`() {
        // Both triangles have the edge P0-P1 in space. At P0 their UVs agree (0,0); at P1 one says
        // (0.4, 0) and the other (1, 1), so the UV runs off in different directions: a seam.
        val b = Builder()
        val a0 = b.vertex(0f, 0f, 0f, 0f)
        val a1 = b.vertex(1f, 0f, 0.4f, 0f)
        val a2 = b.vertex(0f, 1f, 0f, 0.5f)
        val c0 = b.vertex(0f, 0f, 0f, 0f)
        val c1 = b.vertex(1f, 0f, 1f, 1f)
        val c2 = b.vertex(0f, -1f, 0.2f, 0.5f)
        b.idx += listOf(a0, a1, a2, c0, c1, c2)
        val islands = UvIslands.of(b.mesh())
        assertEquals(2, islands.count)
        assertFalse(islands.sameIsland(0, 1))
    }

    @Test
    fun `triangles sharing only a corner are not joined`() {
        val b = Builder()
        val a = listOf(b.vertex(0f, 0f, 0f, 0f), b.vertex(1f, 0f, 0.2f, 0f), b.vertex(0f, 1f, 0f, 0.2f))
        val c = listOf(b.vertex(0f, 0f, 0f, 0f), b.vertex(-1f, 0f, 0.2f, 0f), b.vertex(0f, -1f, 0f, 0.2f))
        b.idx += a + c
        assertEquals(2, UvIslands.of(b.mesh()).count)
    }
}
