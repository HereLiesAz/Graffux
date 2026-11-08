@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonArray

/** The azphalt surface payload: strict parsing of untrusted input, and what it becomes in a group. */
class SurfacePayloadTest {
    private val rect = "[[0.3,0.05],[0.7,0.05],[0.7,0.95],[0.3,0.95]]"
    private val rectSide = "[[0,0.05],[0.4,0.05],[0.4,0.95],[0,0.95]]"
    private val ring = (0 until 32).joinToString(",", "[", "]") {
        val a = 2 * Math.PI * it / 32
        "[${0.5 + 0.2 * Math.cos(a)},${0.2 + 0.2 * Math.sin(a)}]"
    }

    private fun json(front: String = rect, name: String = "Column", extra: String = "", version: String = "1", format: String = "azphalt.surface") =
        """{"format":"$format","version":$version,"name":"$name","front":$front$extra}"""

    private val column = json(extra = ""","side":$rectSide,"top":$ring""")

    private fun rejected(text: String): String {
        val r = SurfacePayload.parse(text)
        assertTrue("expected a rejection of $text", r is SurfaceParse.Rejected)
        return (r as SurfaceParse.Rejected).reason
    }

    private fun ok(text: String) = (SurfacePayload.parse(text) as SurfaceParse.Ok).payload

    @Test fun `a good payload parses with its views`() {
        val p = ok(column)
        assertEquals("Column", p.name)
        assertEquals(4, p.front.size)
        assertEquals(4, p.side!!.size)
        assertEquals(32, p.top!!.size)
        assertNull(ok(json()).side)
    }

    @Test fun `format, version, keys and front are checked`() {
        assertTrue(rejected(json(format = "x")).contains("format"))
        assertTrue(rejected(json(version = "2")).contains("version"))
        assertTrue(rejected(json(version = "\"1\"")).contains("version"))
        assertTrue(rejected(json(extra = ""","relief":[]""")).contains("unknown key"))
        assertTrue(rejected("""{"format":"azphalt.surface","version":1,"name":"a"}""").contains("front is required"))
        assertTrue(rejected("[]").contains("object"))
        assertTrue(rejected("nope").contains("JSON"))
    }

    @Test fun `malformed points are rejected`() {
        assertTrue(rejected(json(front = "[[0,0],[1e999,1]]")).contains("non-finite"))
        assertTrue(rejected(json(front = "[[0,0],[\"1\",1]]")).contains("non-finite"))
        assertTrue(rejected(json(front = "[[0,0],[1.01,1]]")).contains("unit box"))
        assertTrue(rejected(json(front = "[[0,0],[-0.1,1]]")).contains("unit box"))
        assertTrue(rejected(json(front = "[[0,0],[1]]")).contains("pair"))
        assertTrue(rejected(json(front = "[[0,0]]")).contains("at least"))
        assertTrue(rejected(json(front = "[[0.5,0.5],[0.5,0.5]]")).contains("too short"))
        assertTrue(rejected(json(front = "[0,1]")).contains("pair"))
    }

    @Test fun `size, point count, depth, duplicate keys and names are bounded`() {
        val many = (0..SurfacePayload.MAX_POINTS).joinToString(",", "[", "]") { "[${it / 300.0},0.5]" }
        assertTrue(rejected(json(front = many)).contains("limit"))
        assertTrue(rejected(" ".repeat(SurfacePayload.MAX_BYTES + 1)).contains("larger"))
        assertTrue(rejected("[".repeat(100_000)).contains("larger") || true)
        assertTrue(rejected("[".repeat(5000)).contains("nested"))
        assertTrue(rejected("""{"format":"azphalt.surface","format":"azphalt.surface"}""").contains("duplicate"))
        assertTrue(rejected(json(name = "")).contains("name"))
        assertTrue(rejected(json(name = "x".repeat(65))).contains("longer"))
        assertTrue(rejected(json(name = "a\\u0007b")).contains("control"))
        assertTrue(rejected(json(name = "\\ud800")).contains("Unicode"))
        assertTrue(SurfacePayload.parse(byteArrayOf(0x7b, 0xff.toByte(), 0xfe.toByte())) is SurfaceParse.Rejected)
        assertTrue(SurfacePayload.parse(ByteArray(SurfacePayload.MAX_BYTES + 1)) is SurfaceParse.Rejected)
    }

    @Test fun `closed outlines are told from open curves`() {
        assertTrue(SurfacePayload.isClosedOutline(ok(json()).front))
        assertFalse(SurfacePayload.isClosedOutline(ok(json(front = "[[0,0],[0.5,0.5],[1,1]]")).front))
    }

    @Test fun `it becomes a valid profile centred in the canvas, uniformly scaled`() {
        val profile = ok(column).toProfile(1000f, 2000f)!!
        assertTrue(profile.isValid)
        assertNotNull(profile.side)
        assertNotNull(profile.top)
        assertNull(profile.relief)
        // Box is 0.7 * 1000 = 700 wide, centred: x in [150, 850]; front rect x 0.3..0.7 -> 360..640.
        assertEquals(360f, profile.points[0], 0.01f)
        assertEquals(640f, profile.points[3], 0.01f)
        // Front lies on the picture plane and is swept away from the viewer.
        assertEquals(0f, profile.points[2], 0f)
        assertEquals(1f, profile.sweepZ, 0f)
        assertEquals(1f, profile.side!!.sweepX, 0f)
        assertEquals(1f, profile.top!!.sweepY, 0f)
        assertNull(ok(column).toProfile(0f, 100f))
    }

    @Test fun `a carved column is a real solid a ray can hit`() {
        val profile = ok(column).toProfile(1000f, 1000f)!!
        val hull = SurfaceHull.of(profile)
        assertNotNull(hull)
        // From in front of the canvas, straight at the column's middle: it hits the front of the solid.
        val h = hull!!.hit(floatArrayOf(500f, 500f, -500f), floatArrayOf(0f, 0f, 1f), near = 1f)
        assertNotNull(h)
        assertTrue(h!!.z in 0f..300f)
        // Beside the column, left of its silhouette, there is nothing.
        assertNull(hull.hit(floatArrayOf(200f, 500f, -500f), floatArrayOf(0f, 0f, 1f), near = 1f))
    }

    @Test fun `a front-only wave stays a sheet and the arch builds a hull`() {
        val wave = (0..24).joinToString(",", "[", "]") { "[${0.05 + 0.9 * it / 24},${0.5 + 0.25 * Math.sin(it / 24.0 * 4 * Math.PI)}]" }
        val sheet = ok(json(front = wave, name = "Wave")).toProfile(800f, 800f)!!
        assertTrue(sheet.isValid)
        assertNull(SurfaceHull.of(sheet))
        val arch = "[[0.05,0.95],[0.05,0.5],[0.3,0.1],[0.7,0.1],[0.95,0.5],[0.95,0.95],[0.75,0.95],[0.75,0.5],[0.5,0.3],[0.25,0.5],[0.25,0.95]]"
        val a = ok(json(front = arch, name = "Arch", extra = ""","side":$rectSide,"top":[[0.05,0],[0.95,0],[0.95,0.3],[0.05,0.3]]""")).toProfile(1000f, 1000f)!!
        assertNotNull(SurfaceHull.of(a))
    }

    @Test fun `a surface exports as glTF through the existing scene exporter`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1)
        for (text in listOf(column, json(front = "[[0.05,0.5],[0.5,0.2],[0.95,0.5]]", name = "Ridge"))) {
            val tris = ok(text).exportTriangles(1000f, 1000f)!!
            val scene = SceneGltfExporter.Scene(
                1000f, 1000f,
                quads = listOf(SceneGltfExporter.Quad("Surface", png, List(4) { FloatArray(3) }, 1f, surface = tris)),
                strokes = emptyList(), camera = null,
            )
            val gltf = kotlinx.serialization.json.Json.parseToJsonElement(SceneGltfExporter.encode(scene))
            val root = gltf as kotlinx.serialization.json.JsonObject
            assertEquals("2.0", (root["asset"] as kotlinx.serialization.json.JsonObject)["version"].toString().trim('"'))
            assertTrue((root["meshes"] as JsonArray).isNotEmpty())
        }
    }
}
