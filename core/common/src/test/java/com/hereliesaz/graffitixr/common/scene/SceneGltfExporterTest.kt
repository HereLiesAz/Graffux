package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.mesh.SpatialPoint
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SceneGltfExporterTest {
    private val w = 1000f
    private val h = 1200f
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun corners(pose: PlanePose): List<FloatArray> {
        val m = SceneMath.model(pose, w, h)
        return listOf(m.apply(0f, 0f, 0f), m.apply(w, 0f, 0f), m.apply(w, h, 0f), m.apply(0f, h, 0f))
    }

    private fun export(camera: SceneCamera? = SceneCamera()): JsonObject {
        val ink = SpatialStroke("s", listOf(SpatialPoint(0f, 0f, 0f), SpatialPoint(1f, 0f, 0f)), -1, 0.1f)
        val scene = SceneGltfExporter.Scene(
            w, h,
            quads = listOf(
                SceneGltfExporter.Quad("Back", png, corners(PlanePose(positionZ = 300f)), 1f),
                SceneGltfExporter.Quad("Tilted \"quote\"", png, corners(PlanePose(rotationY = 30f)), 0.5f),
            ),
            strokes = listOf(SceneGltfExporter.Strokes("3D strokes", listOf(ink), SceneMath.strokeToLocal(w, h))),
            camera = camera,
        )
        return Json.parseToJsonElement(SceneGltfExporter.encode(scene)).jsonObject
    }

    @Test
    fun `each paint layer is a textured quad and each strokes layer its tube mesh`() {
        val gltf = export()
        assertEquals("2.0", gltf["asset"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val nodes = gltf["nodes"]!!.jsonArray
        assertEquals(listOf("Back", "Tilted \"quote\"", "3D strokes", "Camera"), nodes.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(2, gltf["images"]!!.jsonArray.size)
        assertEquals("image/png", gltf["images"]!!.jsonArray[0].jsonObject["mimeType"]!!.jsonPrimitive.content)
        val meshes = gltf["meshes"]!!.jsonArray
        val quad = meshes[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject["attributes"]!!.jsonObject
        assertTrue("TEXCOORD_0" in quad)
        val tube = meshes[2].jsonObject["primitives"]!!.jsonArray[0].jsonObject["attributes"]!!.jsonObject
        assertTrue("COLOR_0" in tube)
        val opacity = gltf["materials"]!!.jsonArray[1].jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorFactor"]!!.jsonArray
        assertEquals(0.5f, opacity[3].jsonPrimitive.float)
    }

    @Test
    fun `the embedded PNG bytes are the layer's own`() {
        val gltf = export()
        val bytes = Base64.getDecoder().decode(
            gltf["buffers"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content.substringAfter("base64,"),
        )
        val view = gltf["bufferViews"]!!.jsonArray[gltf["images"]!!.jsonArray[0].jsonObject["bufferView"]!!.jsonPrimitive.int].jsonObject
        val offset = view["byteOffset"]!!.jsonPrimitive.int
        assertEquals(0, offset % 4)
        assertArrayEquals(png, bytes.copyOfRange(offset, offset + view["byteLength"]!!.jsonPrimitive.int))
    }

    @Test
    fun `a flat layer at depth 0 is the picture plane, y up, centred, in stroke units`() {
        val gltf = export()
        val pos = gltf["accessors"]!!.jsonArray[0].jsonObject // the first quad's positions
        val unit = SceneMath.focalLength(h) / 3f
        val back = 300f / unit // depth 300 px away = -z in glTF
        assertEquals(-back, pos["min"]!!.jsonArray[2].jsonPrimitive.float, 1e-3f)
        assertEquals(w / 2 / unit, pos["max"]!!.jsonArray[0].jsonPrimitive.float, 1e-3f)
        assertEquals(h / 2 / unit, pos["max"]!!.jsonArray[1].jsonPrimitive.float, 1e-3f)
    }

    @Test
    fun `the camera is optional and sits at the eye`() {
        val without = export(camera = null)
        assertTrue("cameras" !in without)
        val with = export()
        val camNode = with["nodes"]!!.jsonArray.last().jsonObject
        val t = camNode["translation"]!!.jsonArray.map { it.jsonPrimitive.float }
        // The default eye is focal in front of the picture plane: 3 units toward the viewer.
        assertEquals(listOf(0f, 0f, 3f), t.map { Math.round(it * 1000f) / 1000f })
        val q = camNode["rotation"]!!.jsonArray.map { it.jsonPrimitive.float }
        assertEquals(1f, q[3], 1e-4f) // looking down -Z already: identity
    }

    @Test
    fun `an empty scene is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneGltfExporter.encode(SceneGltfExporter.Scene(w, h, emptyList(), emptyList(), null))
        }
    }
}
