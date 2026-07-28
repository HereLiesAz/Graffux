package com.hereliesaz.graffitixr.common.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * OBJ parsing. Real files come out of a dozen exporters, so most of these cover the spellings and
 * omissions that actually turn up rather than the happy path.
 */
class ObjParserTest {

    private val triangle = """
        v 0 0 0
        v 1 0 0
        v 0 1 0
        vt 0 0
        vt 1 0
        vt 0 1
        vn 0 0 1
        f 1/1/1 2/2/2 3/3/3
    """.trimIndent()

    private fun pos(mesh: Mesh, vertex: Int) = floatArrayOf(
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX],
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + 1],
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + 2],
    )

    private fun normal(mesh: Mesh, vertex: Int) = floatArrayOf(
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + Mesh.NORMAL_OFFSET],
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + Mesh.NORMAL_OFFSET + 1],
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + Mesh.NORMAL_OFFSET + 2],
    )

    private fun uv(mesh: Mesh, vertex: Int) = floatArrayOf(
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + Mesh.UV_OFFSET],
        mesh.vertices[vertex * Mesh.FLOATS_PER_VERTEX + Mesh.UV_OFFSET + 1],
    )

    @Test
    fun `parses a triangle with positions, uvs and normals`() {
        val mesh = ObjParser.parse(triangle)
        assertEquals(3, mesh.vertexCount)
        assertEquals(1, mesh.triangleCount)
        assertEquals(0f, pos(mesh, 0)[0], 0f)
        assertEquals(1f, pos(mesh, 1)[0], 0f)
        assertEquals(1f, normal(mesh, 0)[2], 0f)
    }

    @Test
    fun `V is flipped into GL texture convention`() {
        // OBJ's V axis points up; GL's points down. Getting this wrong renders every texture
        // upside down, and would put paint in the wrong place later.
        val mesh = ObjParser.parse(triangle)
        assertEquals(1f, uv(mesh, 0)[1], 1e-5f)   // vt 0 0 -> v = 1
        assertEquals(0f, uv(mesh, 2)[1], 1e-5f)   // vt 0 1 -> v = 0
    }

    @Test
    fun `handles every f corner spelling`() {
        // v, v/vt, v//vn, v/vt/vn all appear in the wild.
        listOf(
            "f 1 2 3",
            "f 1/1 2/2 3/3",
            "f 1//1 2//1 3//1",
            "f 1/1/1 2/2/1 3/3/1",
        ).forEach { face ->
            val mesh = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nvn 0 0 1\n$face")
            assertEquals("for '$face'", 1, mesh.triangleCount)
        }
    }

    @Test
    fun `negative indices count backwards from the end`() {
        val mesh = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf -3 -2 -1")
        assertEquals(1, mesh.triangleCount)
        assertEquals(0f, pos(mesh, 0)[0], 0f)
        assertEquals(1f, pos(mesh, 1)[0], 0f)
    }

    @Test
    fun `a quad is triangulated into two triangles`() {
        val quad = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1 2 3 4
        """.trimIndent()
        assertEquals(2, ObjParser.parse(quad).triangleCount)
    }

    @Test
    fun `an n-gon fans into n-2 triangles`() {
        val pentagon = (1..5).joinToString("\n") { "v $it 0 0" } + "\nf 1 2 3 4 5"
        assertEquals(3, ObjParser.parse(pentagon).triangleCount)
    }

    @Test
    fun `corners sharing a full triple are emitted once`() {
        // Two triangles over four positions sharing an edge: 4 unique corners, not 6.
        val quad = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vn 0 0 1
            f 1//1 2//1 3//1
            f 1//1 3//1 4//1
        """.trimIndent()
        val mesh = ObjParser.parse(quad)
        assertEquals(4, mesh.vertexCount)
        assertEquals(2, mesh.triangleCount)
        assertEquals(6, mesh.indices.size)
    }

    @Test
    fun `the same position with different uvs stays two vertices`() {
        // A seam: GL indexes one attribute set per vertex, so a split UV must split the vertex.
        val seam = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vt 0 0
            vt 0.5 0.5
            f 1/1 2/1 3/1
            f 1/2 2/1 3/1
        """.trimIndent()
        assertEquals(4, ObjParser.parse(seam).vertexCount)
    }

    @Test
    fun `normals are generated when the file has none`() {
        // A model exported without normals must still shade, not render flat black.
        val mesh = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3")
        val n = normal(mesh, 0)
        assertEquals(1f, sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), 1e-4f)
        // This triangle lies in the XY plane wound counter-clockwise, so +Z.
        assertEquals(1f, n[2], 1e-4f)
    }

    @Test
    fun `generated normals are smoothed across shared vertices`() {
        // Two faces meeting at a fold: the shared vertices average, so the surface reads as curved
        // rather than faceted.
        val fold = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            v 1 1 1
            f 1 2 3
            f 2 4 3
        """.trimIndent()
        val mesh = ObjParser.parse(fold)
        val shared = normal(mesh, 1)
        assertEquals(1f, sqrt(shared[0] * shared[0] + shared[1] * shared[1] + shared[2] * shared[2]), 1e-4f)
        // Averaged, so it can't still be the first face's pure +Z.
        assertTrue("expected a blended normal, got ${shared.toList()}", abs(shared[2] - 1f) > 1e-3f)
    }

    @Test
    fun `missing uvs default to zero rather than failing`() {
        val mesh = ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3")
        assertEquals(0f, uv(mesh, 0)[0], 0f)
        assertEquals(0f, uv(mesh, 0)[1], 0f)
    }

    @Test
    fun `comments, blank lines and unknown directives are skipped`() {
        val messy = """
            # exported by something
            mtllib scene.mtl
            o Cube
            g mesh1
            s off
            usemtl Material

            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3   # trailing comment
        """.trimIndent()
        assertEquals(1, ObjParser.parse(messy).triangleCount)
    }

    @Test
    fun `a vertex with a trailing weight still parses`() {
        assertEquals(1, ObjParser.parse("v 0 0 0 1.0\nv 1 0 0 1.0\nv 0 1 0 1.0\nf 1 2 3").triangleCount)
    }

    @Test(expected = ObjParser.ObjParseException::class)
    fun `a file with no faces is rejected`() {
        ObjParser.parse("v 0 0 0\nv 1 0 0")
    }

    @Test(expected = ObjParser.ObjParseException::class)
    fun `a face referencing a missing vertex is rejected`() {
        ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 99")
    }

    @Test(expected = ObjParser.ObjParseException::class)
    fun `non-OBJ text is rejected rather than yielding an empty mesh`() {
        ObjParser.parse("this is not a model at all")
    }

    // ── Mesh measurements, which the camera frames against ───────────────────────────────────

    @Test
    fun `bounds, centre and radius describe the model`() {
        val box = """
            v -1 -2 -3
            v 1 2 3
            v 0 0 0
            f 1 2 3
        """.trimIndent()
        val mesh = ObjParser.parse(box)
        val b = mesh.bounds()!!
        assertEquals(-1f, b[0], 0f); assertEquals(-2f, b[1], 0f); assertEquals(-3f, b[2], 0f)
        assertEquals(1f, b[3], 0f); assertEquals(2f, b[4], 0f); assertEquals(3f, b[5], 0f)

        val c = mesh.center()
        assertEquals(0f, c[0], 1e-5f); assertEquals(0f, c[1], 1e-5f); assertEquals(0f, c[2], 1e-5f)

        // Longest edge is Z (6), so radius 3.
        assertEquals(3f, mesh.radius(), 1e-5f)
    }

    @Test
    fun `an empty mesh reports no bounds and a safe radius`() {
        val empty = Mesh(FloatArray(0), IntArray(0))
        assertNull(empty.bounds())
        assertEquals(1f, empty.radius(), 0f)
        assertNotNull(empty.center())
    }
}
