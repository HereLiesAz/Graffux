package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GraffitiProjectTest {

    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        mockkStatic(android.net.Uri::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `serialization preserves all fields`() {
        // Use a mock URI that returns a valid string representation
        val mockUri = mockk<android.net.Uri>()
        every { mockUri.toString() } returns "file://test"
        every { android.net.Uri.parse("file://test") } returns mockUri

        val project = GraffitiProject(
            id = "test-id",
            name = "Test Project",
            layers = listOf(
                OverlayLayer(
                    id = "layer-1",
                    uri = mockUri,
                    warpMesh = listOf(0f, 1f, 2f, 3f),
                    isImageLocked = true
                )
            )
        )

        assertEquals("test-id", project.id)
        assertEquals("Test Project", project.name)
        assertEquals(true, project.layers[0].isImageLocked)
        assertEquals(listOf(0f, 1f, 2f, 3f), project.layers[0].warpMesh)

        // Actually exercise serialization — the test name promised a round-trip but only ever
        // checked the constructed object's fields, so a serialization regression went uncaught.
        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))
        assertEquals("test-id", decoded.id)
        assertEquals("Test Project", decoded.name)
        assertEquals(true, decoded.layers[0].isImageLocked)
        assertEquals(listOf(0f, 1f, 2f, 3f), decoded.layers[0].warpMesh)
    }

    @Test
    fun `a project saved with the removed AR wall-tracking fields still loads`() {
        // Back-compat: the AR / wall-fingerprint stack (Fingerprint, WallFeatureMap, SLAM map and
        // point-cloud paths, camera intrinsics/anchor, GPS/sensor calibration snapshots) was deleted,
        // but project.json files written by older builds still carry those keys. They must be
        // ignored -- not rejected -- and everything the app still understands must survive.
        val legacy = """
            {
              "id": "legacy-1",
              "name": "Old wall mural",
              "documentWidth": 2048,
              "documentHeight": 1024,
              "railExpansion": { "host.design": true },
              "fingerprint": {
                "keypoints": [ { "x": 1.0, "y": 2.0, "size": 3.0, "angle": 4.0, "response": 5.0, "octave": 1, "class_id": 0 } ],
                "points3d": [0.0, 0.0, 1.0],
                "descriptorsData": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32],
                "descriptorsRows": 1,
                "descriptorsCols": 32,
                "descriptorsType": 0,
                "patchData": [],
                "markCenterLocal": [0.1, 0.2, 0.3]
              },
              "targetFingerprintPath": "/data/projects/legacy-1/fingerprint.bin",
              "targetFingerprint": "/data/projects/legacy-1/target.fp",
              "mapPath": "/data/projects/legacy-1/map.bin",
              "cloudPointsPath": "/data/projects/legacy-1/cloud_points.bin",
              "cloudAnchorId": "anchor-42",
              "fingerprintIntrinsics": [500.0, 500.0, 320.0, 240.0],
              "fingerprintAnchor": [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0],
              "wallFeatureMap": {
                "points3d": [0.0, 0.0, 1.0],
                "descriptorsData": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32],
                "descriptorsRows": 1,
                "descriptorsCols": 32,
                "descriptorsType": 0,
                "confidence": [0.9],
                "obsCount": [4],
                "anchor": [],
                "intrinsics": []
              },
              "gpsData": { "latitude": 1.0, "longitude": 2.0, "altitude": 3.0, "accuracy": 4.0, "time": 5 },
              "sensorData": { "azimuth": 1.0, "pitch": 2.0, "roll": 3.0 },
              "calibrationSnapshots": [
                { "gpsData": null, "sensorData": { "azimuth": 1.0, "pitch": 2.0, "roll": 3.0 }, "poseMatrix": [1.0], "timestamp": 7 }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<GraffitiProject>(legacy)

        assertEquals("legacy-1", decoded.id)
        assertEquals("Old wall mural", decoded.name)
        assertEquals(2048, decoded.documentWidth)
        assertEquals(1024, decoded.documentHeight)
        assertEquals(mapOf("host.design" to true), decoded.railExpansion)

        // Re-saving drops the dead keys instead of carrying them forward.
        val reencoded = json.encodeToString(decoded)
        for (key in listOf(
            "fingerprint", "targetFingerprintPath", "targetFingerprint", "mapPath", "cloudPointsPath",
            "cloudAnchorId", "fingerprintIntrinsics", "fingerprintAnchor", "wallFeatureMap", "gpsData",
            "sensorData", "calibrationSnapshots",
        )) {
            assertEquals("\"$key\" should no longer be written", false, reencoded.contains("\"$key\""))
        }
    }

    @Test
    fun `serialization preserves per-host rail expansion`() {
        val expansion = mapOf("host.design" to true, "design.layers" to false)
        val project = GraffitiProject(id = "id", name = "n", railExpansion = expansion)

        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertEquals(expansion, decoded.railExpansion)
    }

    @Test
    fun `serialization preserves the 3D model and its paint`() {
        // These three point at files inside the project folder. Dropping them here wouldn't fail
        // anything visibly — the model and its paint are still on disk — the project would simply
        // reopen with no model, and the work would look deleted. That's the same silent shape as
        // the parentId and componentId losses this suite exists to catch.
        val project = GraffitiProject(
            id = "id",
            name = "n",
            modelPath = "/data/projects/id/model.obj",
            modelName = "head.obj",
            modelTexturePath = "/data/projects/id/model_texture.png",
        )

        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertEquals("/data/projects/id/model.obj", decoded.modelPath)
        assertEquals("head.obj", decoded.modelName)
        assertEquals("/data/projects/id/model_texture.png", decoded.modelTexturePath)
    }

    @Test
    fun `a project saved before models existed still decodes`() {
        // Back-compat: every existing project on every device predates these fields.
        val old = """{"id":"id","name":"n"}"""

        val decoded = json.decodeFromString<GraffitiProject>(old)

        assertEquals(null, decoded.modelPath)
        assertEquals(null, decoded.modelName)
        assertEquals(null, decoded.modelTexturePath)
    }

    @Test
    fun `serialization preserves the canvas substrate`() {
        val project = GraffitiProject(id = "id", name = "n", canvasSubstrateId = "canvas_weave")

        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertEquals("canvas_weave", decoded.canvasSubstrateId)
    }

    @Test
    fun `a project saved before substrates existed opens as smooth`() {
        val decoded = json.decodeFromString<GraffitiProject>("""{"id":"id","name":"n"}""")

        assertEquals("smooth", decoded.canvasSubstrateId)
        assertEquals("smooth", GraffitiProject().canvasSubstrateId)
    }
}
