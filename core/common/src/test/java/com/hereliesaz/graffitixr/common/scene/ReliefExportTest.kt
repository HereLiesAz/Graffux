@file:Suppress("MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** glTF export of a relief surface (spec/3d-space.md, "Relief surfaces"): an indexed mesh with the photo as its texture. */
class ReliefExportTest {
    private val w = 1000f
    private val h = 800f
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9)

    private fun relief(): ReliefSurface = ReliefSurface.build(
        FloatArray(12 * 9) { i -> 1f - kotlin.math.abs((i % 12) - 5.5f) / 6f }, 12, 9, 600, 450, 100f, 100f, 700f, 550f,
        ReliefOptions(smoothingRadius = 0, clipLow = 0f, clipHigh = 1f),
    )!!

    /** The relief's mesh in scene pixels with the uv as the fraction of the photo, as the editor's export builds it. */
    private fun sceneMesh(r: ReliefSurface): FloatArray {
        val mesh = ReliefMath.mesh(r, 0f, 5000f, 0f, 5000f)
        var i = 0
        while (i < mesh.size) {
            mesh[i + 3] = (mesh[i + 3] - r.left) / r.spanU
            mesh[i + 4] = (mesh[i + 4] - r.top) / r.spanT
            i += 5
        }
        return mesh
    }

    private fun export(r: ReliefSurface, opacity: Float = 0.6f): JsonObject {
        val scene = SceneGltfExporter.Scene(
            w, h, listOf(SceneGltfExporter.Quad("Relief photo", png, emptyList(), opacity, sceneMesh(r), sharedVertices = true)), emptyList(), null,
        )
        return Json.parseToJsonElement(SceneGltfExporter.encode(scene)).jsonObject
    }

    private fun accessor(gltf: JsonObject, i: Int) = gltf["accessors"]!!.jsonArray[i].jsonObject

    @Test fun theReliefIsOneIndexedMeshWithEachGridVertexOnce() {
        val r = relief()
        val gltf = export(r)
        val prim = gltf["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        val attrs = prim["attributes"]!!.jsonObject
        val pos = accessor(gltf, attrs["POSITION"]!!.jsonPrimitive.int)
        val uv = accessor(gltf, attrs["TEXCOORD_0"]!!.jsonPrimitive.int)
        val idx = accessor(gltf, prim["indices"]!!.jsonPrimitive.int)
        assertEquals(r.cols * r.rows, pos["count"]!!.jsonPrimitive.int)
        assertEquals(r.cols * r.rows, uv["count"]!!.jsonPrimitive.int)
        assertEquals((r.cols - 1) * (r.rows - 1) * 6, idx["count"]!!.jsonPrimitive.int)
    }

    @Test fun everyIndexPointsAtAVertexAndTheTrianglesAreTheMeshs() {
        val r = relief()
        val gltf = export(r)
        val bin = ByteBuffer.wrap(Base64.getDecoder().decode(gltf["buffers"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content.substringAfter("base64,")))
            .order(ByteOrder.LITTLE_ENDIAN)
        val prim = gltf["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        val idx = accessor(gltf, prim["indices"]!!.jsonPrimitive.int)
        val view = gltf["bufferViews"]!!.jsonArray[idx["bufferView"]!!.jsonPrimitive.int].jsonObject
        val offset = view["byteOffset"]!!.jsonPrimitive.int
        val n = idx["count"]!!.jsonPrimitive.int
        val count = r.cols * r.rows
        for (k in 0 until n) {
            val v = bin.getInt(offset + k * 4)
            assertTrue("index $v of $count", v in 0 until count)
        }
    }

    @Test fun theTexturePositionsAreTheFractionOfThePhoto() {
        val r = relief()
        val gltf = export(r)
        val prim = gltf["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        val uv = accessor(gltf, prim["attributes"]!!.jsonObject["TEXCOORD_0"]!!.jsonPrimitive.int)
        val bin = ByteBuffer.wrap(Base64.getDecoder().decode(gltf["buffers"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content.substringAfter("base64,")))
            .order(ByteOrder.LITTLE_ENDIAN)
        val view = gltf["bufferViews"]!!.jsonArray[uv["bufferView"]!!.jsonPrimitive.int].jsonObject
        val offset = view["byteOffset"]!!.jsonPrimitive.int
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (k in 0 until uv["count"]!!.jsonPrimitive.int * 2) {
            val f = bin.getFloat(offset + k * 4)
            lo = minOf(lo, f); hi = maxOf(hi, f)
        }
        assertEquals(0f, lo, 1e-4f)
        assertEquals(1f, hi, 1e-4f)
    }

    @Test fun theDepthIsInThePositionsAndTheMaterialCarriesThePhotoAndOpacity() {
        val r = relief()
        val gltf = export(r, opacity = 0.6f)
        val prim = gltf["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        val pos = accessor(gltf, prim["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int)
        val min = pos["min"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }
        val max = pos["max"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }
        val unit = SceneMath.focalLength(h) / SceneMath.STROKE_UNITS_AT_DEFAULT_DISTANCE
        // z is the relief's displacement, flipped into glTF's z toward the viewer, in metres: it spans the heights present.
        assertEquals(r.displacement / unit, max[2] - min[2], r.displacement / unit * 0.02f)
        assertTrue(max[2] > 0f && min[2] < 0f)
        assertEquals("image/png", gltf["images"]!!.jsonArray[0].jsonObject["mimeType"]!!.jsonPrimitive.content)
        val factor = gltf["materials"]!!.jsonArray[0].jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorFactor"]!!.jsonArray
        assertEquals(0.6f, factor[3].jsonPrimitive.content.toFloat(), 1e-6f)
        assertEquals(true, gltf["materials"]!!.jsonArray[0].jsonObject["doubleSided"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun otherSurfacesStillExportThreeVerticesPerTriangle() {
        val mesh = FloatArray(30) { (it * 7 % 11).toFloat() }
        val gltf = Json.parseToJsonElement(
            SceneGltfExporter.encode(SceneGltfExporter.Scene(w, h, listOf(SceneGltfExporter.Quad("S", png, emptyList(), 1f, mesh)), emptyList(), null)),
        ).jsonObject
        val prim = gltf["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        assertEquals(6, accessor(gltf, prim["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int)["count"]!!.jsonPrimitive.int)
    }

    @Test fun aLargeReliefExportsFarSmallerThanItsUnindexedTriangles() {
        val r = ReliefSurface.build(FloatArray(64 * 64) { (it % 64) / 63f }, 64, 64, 4000, 4000, 0f, 0f, 800f, 800f)!!
        val mesh = sceneMesh(r)
        val text = SceneGltfExporter.encode(SceneGltfExporter.Scene(w, h, listOf(SceneGltfExporter.Quad("R", png, emptyList(), 1f, mesh, sharedVertices = true)), emptyList(), null))
        val unindexedBytes = mesh.size / 5 * (12 + 8 + 4)
        assertTrue("${text.length} chars vs $unindexedBytes bytes unindexed", text.length < unindexedBytes * 4 / 3 * 0.6)
    }
}
