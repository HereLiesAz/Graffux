package com.hereliesaz.graffitixr.common.mesh

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed camera geometry with independently calculated expected screen/world coordinates. */
class SpatialSketchTest {
    private val camera = OrbitCamera(
        target = floatArrayOf(0f, 0f, 0f),
        distance = 3f,
        yaw = 0f,
        pitch = 0f,
        fovYDegrees = 90f,
    )

    @Test
    fun `the centre of the screen meets the world origin`() {
        val p = SpatialSketchGeometry.onViewPlane(camera, 100f, 100f, 200f, 200f)!!
        assertEquals(0f, p.x, 0.0001f)
        assertEquals(0f, p.y, 0.0001f)
        assertEquals(0f, p.z, 0.0001f)
    }

    @Test
    fun `screen offsets follow a 90 degree perspective guide`() {
        // At distance 3 and vertical FOV 90°, half the guide spans 3 world units.
        // A touch a quarter of the viewport width right of centre spans half that: x=1.5.
        val p = SpatialSketchGeometry.onViewPlane(camera, 150f, 100f, 200f, 200f)!!
        assertEquals(1.5f, p.x, 0.001f)
        assertEquals(0f, p.y, 0.001f)
        assertEquals(0f, p.z, 0.001f)
        assertEquals(150f, SpatialSketchGeometry.project(camera, p, 200f, 200f)!!.first, 0.001f)
    }

    @Test
    fun `depth changes placement without changing the drawing camera`() {
        // Eye=(0,0,3). Negative depth moves the guide one world unit toward the viewer.
        val point = SpatialSketchGeometry.onViewPlane(camera, 100f, 100f, 200f, 200f, depth = -1f)!!
        assertEquals(1f, point.z, 0.001f)
        assertEquals(3f, camera.distance, 0f)
    }

    @Test
    fun `rotating changes projection without altering the stored spatial stroke`() {
        val stroke = SpatialStroke("a", listOf(SpatialPoint(1.5f, 0f, 0f)), 0xFF123456.toInt(), 0.1f)
        val before = SpatialSketchGeometry.project(camera, stroke.points.single(), 200f, 200f)!!
        val after = SpatialSketchGeometry.project(
            camera.orbit(kotlin.math.PI.toFloat() / 2f, 0f),
            stroke.points.single(), 200f, 200f,
        )!!
        assertEquals(150f, before.first, 0.001f)
        assertEquals(100f, after.first, 0.001f)
        assertEquals(1.5f, stroke.points.single().x, 0f)
    }

    @Test
    fun `eraser chooses visible stroke within touch radius`() {
        val a = SpatialStroke("middle", listOf(SpatialPoint(0f, 0f, 0f)), 0, 0.1f)
        val b = SpatialStroke("right", listOf(SpatialPoint(2.8f, 0f, 0f)), 0, 0.1f)
        assertEquals("middle", SpatialSketchGeometry.nearestStrokeId(
            listOf(a, b), camera, 101f, 100f, 200f, 200f,
        ))
        assertNull(SpatialSketchGeometry.nearestStrokeId(
            listOf(a), camera, 170f, 160f, 200f, 200f,
        ))
    }

    @Test
    fun `spatial ink survives JSON round trip with its pressure and width`() {
        val expected = SpatialStroke(
            "ink-1", listOf(SpatialPoint(0.2f, -0.8f, 1.25f, 0.45f)),
            0x66FF0088, 0.035f,
        )
        val stored = Json.encodeToString(expected)
        val restored = Json.decodeFromString<SpatialStroke>(stored)
        assertEquals(expected, restored)
        assertTrue(stored.contains("\"pressure\""))
    }

    @Test
    fun `touch inputs with invalid dimensions never create a point`() {
        assertNull(SpatialSketchGeometry.onViewPlane(camera, 1f, 1f, 0f, 200f))
        assertNull(SpatialSketchGeometry.onViewPlane(camera, 1f, 1f, 200f, 0f))
    }

    @Test
    fun `glTF encodes an independently counted tube mesh and RGBA colours`() {
        // Two centres, six vertices per ring => 12 vertices; six quads, two triangles each
        // => 36 32-bit indices. POSITION = 144 bytes, COLOR_0 = 192, indices = 144.
        val ink = SpatialStroke(
            "green", listOf(SpatialPoint(0f, 0f, 0f), SpatialPoint(1f, 0f, 0f)),
            0x8000FF00.toInt(), 0.2f,
        )
        val document = Json.parseToJsonElement(SpatialGltfExporter.encode(listOf(ink))).jsonObject
        val accessors = document.getValue("accessors").jsonArray
        assertEquals(12, accessors[0].jsonObject.getValue("count").jsonPrimitive.int)
        assertEquals(12, accessors[1].jsonObject.getValue("count").jsonPrimitive.int)
        assertEquals(36, accessors[2].jsonObject.getValue("count").jsonPrimitive.int)
        val encoded = document.getValue("buffers").jsonArray[0].jsonObject
            .getValue("uri").jsonPrimitive.content.substringAfter("base64,")
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        assertEquals(480, bytes.size)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, buffer.getFloat(144), 0.001f) // red
        assertEquals(1f, buffer.getFloat(148), 0.001f) // green
        assertEquals(0f, buffer.getFloat(152), 0.001f) // blue
        assertEquals(128f / 255f, buffer.getFloat(156), 0.001f) // alpha
    }

    @Test
    fun `glTF preserves a curve that returns to its starting point`() {
        val path = listOf(
            SpatialPoint(0f, 0f, 0f), SpatialPoint(1f, 0f, 0f),
            SpatialPoint(1f, 1f, 0f), SpatialPoint(0f, 0f, 0f),
        )
        val document = Json.parseToJsonElement(
            SpatialGltfExporter.encode(listOf(SpatialStroke("loop", path, -1, 0.2f))),
        ).jsonObject
        // All four rings remain; a global distinctBy would have silently dropped the last one.
        assertEquals(24, document.getValue("accessors").jsonArray[0].jsonObject
            .getValue("count").jsonPrimitive.int)
    }

    @Test
    fun `a single tap exports as geometry instead of disappearing`() {
        val dot = SpatialStroke("dot", listOf(SpatialPoint(0f, 0f, 0f)), -1, 0.2f)
        val document = Json.parseToJsonElement(SpatialGltfExporter.encode(listOf(dot))).jsonObject
        assertEquals(12, document.getValue("accessors").jsonArray[0].jsonObject
            .getValue("count").jsonPrimitive.int)
        assertEquals(36, document.getValue("accessors").jsonArray[2].jsonObject
            .getValue("count").jsonPrimitive.int)
    }
}
