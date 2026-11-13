package com.hereliesaz.graffitixr.data

import android.content.Context
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.LegacyVisuals
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.LayerType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@Suppress("LargeClass") // one test class per ProjectManager, as before; each test is small
class ProjectManagerTest {

    private lateinit var mockContext: Context
    private lateinit var tempFilesDir: File
    private lateinit var uriProvider: UriProvider
    private lateinit var manager: ProjectManager

    @Before
    fun setup() {
        tempFilesDir = File(System.getProperty("java.io.tmpdir"), "graffitixr_test_files")
        tempFilesDir.mkdirs()

        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempFilesDir
        every { mockContext.cacheDir } returns File(tempFilesDir, "cache").also { it.mkdirs() }

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)

        // The hardened import/spectator paths log skipped hostile entries; android.util.Log is
        // not available in plain JVM tests.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        uriProvider = mockk(relaxed = true)
        val projectRepositoryProvider = mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>(relaxed = true)
        manager = ProjectManager(mockContext, uriProvider, projectRepositoryProvider)
    }

    @After
    fun teardown() {
        tempFilesDir.deleteRecursively()
        unmockkStatic(Uri::class)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `getProjectList returns empty list when directory is missing or empty`() = runTest {
        val list = manager.getProjectList(mockContext)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `saveProject and loadProjectMetadata works correctly`() = runTest {
        val project = GraffitiProject(id = "test_project", name = "My Test Art")
        manager.saveProject(mockContext, project)

        val list = manager.getProjectList(mockContext)
        assertEquals(1, list.size)
        assertEquals("test_project", list[0])

        val loaded = manager.loadProjectMetadata(mockContext, "test_project")
        assertEquals("My Test Art", loaded?.name)
    }

    @Test
    fun `a legacy project file written before the AR stack was removed still loads and re-saves cleanly`() = runTest {
        val dir = File(tempFilesDir, "projects/legacy_ar").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """
            {"id":"legacy_ar","name":"Old AR wall","documentWidth":640,
             "fingerprint":{"keypoints":[],"descriptorsData":[],"descriptorsRows":0,"descriptorsCols":0,"descriptorsType":0},
             "targetFingerprintPath":"/f/projects/legacy_ar/fp.bin","mapPath":"/f/projects/legacy_ar/map.bin",
             "cloudPointsPath":"/f/projects/legacy_ar/cloud_points.bin","fingerprintIntrinsics":[1.0,2.0,3.0,4.0],
             "wallFeatureMap":{"points3d":[],"descriptorsData":[],"descriptorsRows":0,"descriptorsCols":0,"descriptorsType":0},
             "gpsData":{"latitude":1.0,"longitude":2.0,"altitude":3.0,"accuracy":4.0,"time":5}}
            """.trimIndent(),
        )

        val loaded = manager.loadProjectMetadata(mockContext, "legacy_ar")
        assertEquals("Old AR wall", loaded?.name)
        assertEquals(640, loaded?.documentWidth)

        manager.saveProject(mockContext, loaded!!)
        val rewritten = File(dir, "project.json").readText()
        assertFalse(rewritten.contains("fingerprint", ignoreCase = true))
        assertFalse(rewritten.contains("wallFeatureMap"))
        assertFalse(rewritten.contains("mapPath"))
        assertEquals("Old AR wall", manager.loadProjectMetadata(mockContext, "legacy_ar")?.name)
    }

    @Test
    fun `a format 14 file with the retired orbitPivot loads without it and any save drops it`() = runTest {
        val dir = File(tempFilesDir, "projects/legacy_pivot").also { it.mkdirs() }
        File(dir, "project.json").writeText(
            """{"formatVersion":14,"id":"legacy_pivot","name":"Pivoted","orbitPivot":[10.0,20.0,30.0]}""",
        )
        val loaded = manager.loadProjectMetadata(mockContext, "legacy_pivot")
        assertEquals("Pivoted", loaded?.name)
        assertEquals(null, loaded?.orbitPivot)
        // Saved as loaded (a rename or import copy of it carries nothing): the key's value is gone from the file.
        manager.saveProject(mockContext, loaded!!.copy(name = "Renamed"))
        assertFalse(File(dir, "project.json").readText().contains("[10.0"))
        assertEquals("Renamed", manager.loadProjectMetadata(mockContext, "legacy_pivot")?.name)
    }

    @Test
    fun `deleteProject removes directory`() = runTest {
        val project = GraffitiProject(id = "del_project", name = "To Be Deleted")
        manager.saveProject(mockContext, project)
        assertTrue(File(tempFilesDir, "projects/del_project").exists())

        manager.deleteProject(mockContext, "del_project")
        assertFalse(File(tempFilesDir, "projects/del_project").exists())
    }

    @Test
    fun `importProjectFromUri fails gracefully on bad URI`() = runTest {
        val mockUri = mockk<Uri>(relaxed = true)
        val mockResolver = mockk<android.content.ContentResolver>(relaxed = true)

        every { mockContext.contentResolver } returns mockResolver
        every { mockResolver.openInputStream(any()) } returns null

        val result = manager.importProjectFromUri(mockContext, mockUri)
        assertNull(result)
    }

    // --- Zip-Slip / hostile-archive hardening ---

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun projectJson(id: String) = """{"id":"$id","name":"evil"}""".toByteArray()

    private suspend fun importZip(zipBytes: ByteArray): GraffitiProject? {
        val mockUri = mockk<Uri>(relaxed = true)
        val mockResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { mockContext.contentResolver } returns mockResolver
        every { mockResolver.openInputStream(any()) } returns zipBytes.inputStream()
        return manager.importProjectFromUri(mockContext, mockUri)
    }

    @Test
    fun `import skips zip entries that escape the project directory`() = runTest {
        val evilTarget = File(tempFilesDir.parentFile, "gxr_zip_slip_escape.txt")
        evilTarget.delete()
        // Entry names get their first path segment stripped on import, so both hostile shapes
        // must be caught: a "../" chain surviving the strip, nested under a decoy segment.
        val depth = "../".repeat(6)
        val zip = zipOf(
            "project.json" to projectJson("safe_project"),
            "x/$depth${evilTarget.name}" to "pwned".toByteArray(),
            "innocent.png" to byteArrayOf(1, 2, 3),
        )

        val result = importZip(zip)

        assertFalse("hostile entry must not be written outside filesDir", evilTarget.exists())
        // The import itself succeeds minus the hostile entry.
        assertEquals("safe_project", result?.id)
        assertTrue(File(tempFilesDir, "projects/safe_project/innocent.png").exists())
        assertFalse(
            File(tempFilesDir, "projects/safe_project").walkTopDown().any { it.name == evilTarget.name },
        )
    }

    @Test
    fun `import never overwrites an existing project with the same id`() = runTest {
        val dir = File(tempFilesDir, "projects/dup_id").also { it.mkdirs() }
        File(dir, "project.json").writeText("""{"id":"dup_id","name":"Newer local"}""")
        File(dir, "layer.png").writeBytes(byteArrayOf(9, 9, 9))

        val result = importZip(
            zipOf(
                "project.json" to
                    """{"id":"dup_id","name":"Old export","modelPath":"/f/projects/dup_id/model.obj"}""".toByteArray(),
                "layer.png" to byteArrayOf(1, 2, 3),
            ),
        )

        assertTrue("collision must still import", result != null)
        val newId = result!!.id
        assertTrue(newId != "dup_id")
        // Local project untouched.
        assertEquals("""{"id":"dup_id","name":"Newer local"}""", File(dir, "project.json").readText())
        assertEquals(listOf<Byte>(9, 9, 9), File(dir, "layer.png").readBytes().toList())
        // Import landed beside it, with its own paths rebased to the new id.
        assertEquals(listOf<Byte>(1, 2, 3), File(tempFilesDir, "projects/$newId/layer.png").readBytes().toList())
        assertEquals("Old export", persisted(newId).name)
        // The old floating-window model came along as a 3D model layer naming its file.
        assertEquals("model.obj", persisted(newId).layers.single().model!!.objFile)
        // No staging debris.
        assertFalse(File(tempFilesDir, "import_staging").walkTopDown().any { it.isFile })
    }

    @Test
    fun `import refuses a project json nested absurdly deep instead of crashing`() = runTest {
        val deep = """{"id":"deep_json","name":"evil","extra":""" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertNull(importZip(zipOf("project.json" to deep.toByteArray())))
        assertFalse(File(tempFilesDir, "projects/deep_json").exists())
    }

    @Test
    fun `import refuses deep nesting that follows a string with an escaped quote`() = runTest {
        val deep = """{"id":"esc_quote","name":"a\"b","extra":""" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertNull(importZip(zipOf("project.json" to deep.toByteArray())))
        assertFalse(File(tempFilesDir, "projects/esc_quote").exists())
    }

    @Test
    fun `import still accepts a project json that is nested moderately`() = runTest {
        val nested = """{"id":"nested_ok","name":"fine","extra":""" + "[".repeat(20) + "1" + "]".repeat(20) + "}"
        assertNotNull(importZip(zipOf("project.json" to nested.toByteArray())))
    }

    @Test
    fun `import rejects archives with too many entries`() = runTest {
        val entries = (0..10_000).map { "f$it" to ByteArray(0) }.toTypedArray()
        val result = importZip(zipOf("project.json" to projectJson("many_entries"), *entries))
        assertNull(result)
        assertFalse(File(tempFilesDir, "projects/many_entries").exists())
    }

    @Test
    fun `import rejects archives with a path-traversal project id`() = runTest {
        val result = importZip(zipOf("project.json" to projectJson("../escape")))
        assertNull("hostile project.id must reject the whole import", result)
        assertFalse(File(tempFilesDir, "escape").exists())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadAsSpectator skips escaping entries and rejects hostile ids`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val evilTarget = File(tempFilesDir.parentFile, "gxr_spectator_escape.txt")
            evilTarget.delete()

            // Hostile id: nothing may be created for it.
            manager.loadAsSpectator(zipOf("project.json" to projectJson("../spec_escape")))
            assertFalse(File(tempFilesDir, "spec_escape").exists())

            // Escaping entry: skipped, rest of the project loads. Spectator zips keep raw entry
            // names (no first-segment strip), so a bare "../" chain is the attack shape here.
            val depth = "../".repeat(6)
            manager.loadAsSpectator(
                zipOf(
                    "project.json" to projectJson("spec_safe"),
                    "$depth${evilTarget.name}" to "pwned".toByteArray(),
                    "layer.png" to byteArrayOf(7),
                ),
            )
            assertFalse(evilTarget.exists())
            assertTrue(File(tempFilesDir, "projects/spec_safe/layer.png").exists())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loadProjectMetadata does not write the migrated project back to disk`() = runTest {
        // A "legacy" project: non-default legacyVisuals triggers the in-memory migration.
        val projectDir = File(tempFilesDir, "projects/legacy_project").also { it.mkdirs() }
        val legacyJson = """{"id":"legacy_project","name":"Old","legacyVisuals":{"scale":2.0}}"""
        val projectFile = File(projectDir, "project.json")
        projectFile.writeText(legacyJson)

        val metadata = manager.loadProjectMetadata(mockContext, "legacy_project")

        assertEquals("legacy_project", metadata?.id)
        // Read path must be side-effect free: bytes on disk untouched.
        assertEquals(legacyJson, projectFile.readText())
    }

    // --- legacyVisuals migration ---

    private val legacyOverlayJson =
        """{"id":"LEGACY_ID","name":"Old","overlayImageUri":"file:///o.png",""" +
            """"legacyVisuals":{"scale":2.0,"opacity":0.5}}"""

    private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun persisted(id: String): GraffitiProject =
        lenientJson.decodeFromString(
            GraffitiProject.serializer(),
            File(tempFilesDir, "projects/$id/project.json").readText(),
        )

    private fun assertMigrated(p: GraffitiProject?) {
        assertEquals(1, p?.layers?.size)
        assertEquals(2f, p!!.layers.first().scale)
        assertEquals(0.5f, p.layers.first().opacity)
        assertEquals(LegacyVisuals(), p.legacyVisuals)
    }

    @Test
    fun `importProjectFromUri migrates legacyVisuals and persists the migration`() = runTest {
        val id = "imported_legacy"
        val result = importZip(zipOf("project.json" to legacyOverlayJson.replace("LEGACY_ID", id).toByteArray()))
        assertMigrated(result)
        assertMigrated(persisted(id))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadAsSpectator migrates legacyVisuals and persists the migration`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val id = "spectator_legacy"
            manager.loadAsSpectator(zipOf("project.json" to legacyOverlayJson.replace("LEGACY_ID", id).toByteArray()))
            assertMigrated(persisted(id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `migration fills default layer fields and keeps conflicting legacy values`() {
        val uri = mockk<Uri>(relaxed = true)
        val layer = OverlayLayer(uri = uri, scale = 3f, opacity = 1f, brightness = 0.2f)
        val project = GraffitiProject(
            id = "p",
            layers = listOf(layer),
            legacyVisuals = LegacyVisuals(scale = 2f, opacity = 0.5f, brightness = 0.2f),
        )

        val migrated = manager.migrateInMemory(project)

        val first = migrated.layers.first()
        assertEquals("layer's own non-default value wins", 3f, first.scale)
        assertEquals("default layer field takes the legacy value", 0.5f, first.opacity)
        assertEquals(0.2f, first.brightness)
        // Only the value that could not be placed survives in legacyVisuals — nothing is lost.
        assertEquals(LegacyVisuals(scale = 2f), migrated.legacyVisuals)
        // Idempotent: a second pass is a no-op by identity, so it never re-persists.
        assertSame(migrated, manager.migrateInMemory(migrated))
    }

    @Test
    fun `migration keeps legacyVisuals when there is no layer to place them on`() {
        val project = GraffitiProject(id = "p", legacyVisuals = LegacyVisuals(scale = 2f))
        assertSame(project, manager.migrateInMemory(project))
    }

    @Test
    fun `migration of a default first layer consumes all legacy values`() {
        val uri = mockk<Uri>(relaxed = true)
        val second = OverlayLayer(uri = uri, name = "second", scale = 4f)
        val project = GraffitiProject(
            id = "p",
            layers = listOf(OverlayLayer(uri = uri), second),
            legacyVisuals = LegacyVisuals(scale = 2f, rotationZ = 45f),
        )
        val migrated = manager.migrateInMemory(project)
        assertEquals(2f, migrated.layers[0].scale)
        assertEquals(45f, migrated.layers[0].rotationZ)
        assertEquals(second, migrated.layers[1])
        assertEquals(LegacyVisuals(), migrated.legacyVisuals)
    }

    @Test
    fun `versions 9 10 and 11 surfaces load unchanged and become version 14`() {
        for (v in listOf(9, 10, 11)) {
            val text = """{"formatVersion":$v,"id":"s","layers":[{"id":"g","name":"G","uri":"","layerType":"GROUP",""" +
                """"surface":{"points":[0,0,0,100,0,0],"sweepZ":1.0}}]}"""
            val project = manager.decodeProject(text)
            assertEquals(14, project.formatVersion)
            val surface = project.layers.single().surface!!
            assertNull(surface.relief)
            assertTrue(surface.isValid)
            assertEquals(100f, surface.length, 0f)
        }
    }

    @Test
    fun `a relief surface survives a save and a load`() {
        val relief = com.hereliesaz.graffitixr.common.scene.ReliefSurface.build(
            FloatArray(16) { (it % 4) / 3f }, 4, 4, 400, 300, 10f, 20f, 210f, 170f,
        )!!.withInverted(true).withDisplacement(42f)
        val project = GraffitiProject(
            id = "p",
            layers = listOf(
                OverlayLayer(
                    id = "g", name = "G", uri = mockk(relaxed = true), layerType = LayerType.GROUP,
                    surface = relief.toProfile(),
                ),
            ),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = manager.decodeProject(json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(relief, back.layers.single().surface!!.relief)
        assertTrue(back.layers.single().surface!!.isValid)
    }

    @Test
    fun `a version 1 project opens and becomes the current version`() {
        // A project.json from before adjustment layers: no formatVersion key.
        val v1 = """{"id":"old","name":"Wall","layers":""" +
            """[{"id":"a","name":"Photo","uri":"file:///x.png","opacity":0.5}]}"""
        val project = manager.decodeProject(v1)
        assertEquals(com.hereliesaz.graffitixr.common.model.CURRENT_FORMAT_VERSION, project.formatVersion)
        assertEquals(listOf("a"), project.layers.map { it.id })
        assertEquals(0.5f, project.layers[0].opacity)
        assertNull(project.layers[0].adjustment)
    }

    @Test
    fun `an adjustment layer survives a save and a load`() {
        val adjustment = com.hereliesaz.graffitixr.common.photo.PhotoAdjustment(
            com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT,
            values = mapOf("exposure" to 0.35f, "contrast" to 12f),
        )
        val project = GraffitiProject(
            id = "p",
            layers = listOf(
                OverlayLayer(id = "adj", name = "Light", uri = mockk(relaxed = true), adjustment = adjustment),
            ),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(GraffitiProject.serializer(), project)
        assertTrue(text.contains("\"formatVersion\":14"))
        val back = manager.decodeProject(text)
        assertEquals(adjustment, back.layers.single().adjustment)
        assertEquals(14, back.formatVersion)
    }

    @Test
    fun `format five project persists spatial curves without changing older files`() {
        val stroke = com.hereliesaz.graffitixr.common.mesh.SpatialStroke(
            "line", listOf(
                com.hereliesaz.graffitixr.common.mesh.SpatialPoint(0f, 0f, 0f, 0.3f),
                com.hereliesaz.graffitixr.common.mesh.SpatialPoint(1f, 0f, 1f, 0.7f),
            ), 0xFFABCDEF.toInt(), 0.125f,
        )
        val project = GraffitiProject(id = "ink-project", spatialStrokes = listOf(stroke))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val payload = json.encodeToString(GraffitiProject.serializer(), project)
        assertEquals(listOf(stroke), manager.decodeProject(payload).spatialStrokes)

        // Independent historical fixture, not derived from the new serializer's output.
        val old = """{"formatVersion":4,"id":"old-project","name":"Old"}"""
        assertTrue(manager.decodeProject(old).spatialStrokes.isEmpty())
    }

    @Test
    fun `guide transforms persist in format six and old sketches keep a view plane`() {
        val storedGuide = com.hereliesaz.graffitixr.common.mesh.SpatialGuide(
            kind = com.hereliesaz.graffitixr.common.mesh.SpatialGuideKind.CYLINDER,
            center = com.hereliesaz.graffitixr.common.mesh.SpatialPoint(0.5f, 1f, -0.5f),
            radius = 1.4f, height = 3f, yaw = 0.6f, pitch = -0.25f, visible = false,
        )
        val project = GraffitiProject(id = "guide-scene", spatialGuide = storedGuide)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val restored = manager.decodeProject(json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(14, restored.formatVersion)
        assertEquals(storedGuide, restored.spatialGuide)

        // Independent historical format-5 fixture: no guide key was ever written in that format.
        val legacy = """{"formatVersion":5,"id":"old-sketch","spatialStrokes":[
            {"id":"old-line","points":[{"x":1.0,"y":0.0,"z":0.0}],
             "colorArgb":-1,"width":0.2}]}"""
        val reopened = manager.decodeProject(legacy)
        assertEquals(14, reopened.formatVersion)
        // Format 7 moves the sketch into a 3D strokes layer, with the original view-plane guide.
        val content = reopened.layers.single().spatial!!
        assertEquals(com.hereliesaz.graffitixr.common.mesh.SpatialGuide(), content.guide)
        assertEquals(1f, content.strokes.single().points.single().x, 0f)
        assertTrue(reopened.spatialStrokes.isEmpty())
    }

    @Test
    fun `a format six spatial sketch loads into a 3D strokes layer on top, untouched`() {
        // Independent historical fixture: a v6 project with a photo layer, ink and a fixed guide.
        val v6 = """{"formatVersion":6,"id":"s","layers":[{"id":"photo","name":"Photo","uri":"file:///p.png"}],
            "spatialStrokes":[{"id":"ink","points":[{"x":0.5,"y":-1.0,"z":2.0,"pressure":0.4}],"colorArgb":-65536,"width":0.1}],
            "spatialGuide":{"kind":"SPHERE","radius":1.5}}"""
        val project = manager.decodeProject(v6)
        assertEquals(14, project.formatVersion)
        assertEquals(listOf("photo", project.layers[1].id), project.layers.map { it.id })
        val photo = project.layers[0]
        assertEquals(0f, photo.positionZ)
        assertNull(photo.spatial)
        val ink = project.layers[1]
        assertEquals("3D strokes", ink.name)
        val point = ink.spatial!!.strokes.single().points.single()
        assertEquals(listOf(0.5f, -1.0f, 2.0f, 0.4f), listOf(point.x, point.y, point.z, point.pressure))
        assertEquals(com.hereliesaz.graffitixr.common.mesh.SpatialGuideKind.SPHERE, ink.spatial!!.guide.kind)
        assertEquals(1.5f, ink.spatial!!.guide.radius)
        assertTrue(project.spatialStrokes.isEmpty())
        assertEquals(com.hereliesaz.graffitixr.common.scene.SceneCamera(), project.sceneCamera)
    }

    @Test
    fun `a format seven floating-window model loads as a 3D model layer on top`() {
        val v7 = """{"formatVersion":7,"id":"m","layers":[{"id":"a","name":"A","uri":""}],
            "modelPath":"/data/user/0/app/files/projects/m/model.obj","modelName":"head.obj",
            "modelTexturePath":"/data/user/0/app/files/projects/m/model_texture.png"}"""
        val project = manager.decodeProject(v7)
        assertEquals(14, project.formatVersion)
        assertEquals(2, project.layers.size)
        val model = project.layers.last()
        assertEquals("head", model.name)
        assertEquals("model.obj", model.model!!.objFile)
        assertEquals("model_texture.png", model.model!!.textureFile)
        assertNull(project.modelPath)
        assertNull(project.modelTexturePath)
        // At the identity plane: facing the default camera through the canvas centre.
        assertEquals(0f, model.positionZ)
        assertEquals(0f, model.rotationX)
        assertEquals(0f, model.rotationY)
    }

    @Test
    fun `a format seven project without a model gains no layer, and a model layer round-trips`() {
        val v7 = """{"formatVersion":7,"id":"m","layers":[{"id":"a","name":"A","uri":""}]}"""
        assertEquals(listOf("a"), manager.decodeProject(v7).layers.map { it.id })
        val content = com.hereliesaz.graffitixr.common.scene.ModelLayerContent("model_x.obj", "model_tex_x.png")
        val project = GraffitiProject(
            id = "m",
            layers = listOf(OverlayLayer(id = "x", uri = mockk(relaxed = true), model = content)),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = manager.decodeProject(json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(content, back.layers.single().model)
        assertEquals(1, back.layers.size)
    }

    @Test
    fun `a format six project without ink gains no layer`() {
        val v6 = """{"formatVersion":6,"id":"s","layers":[{"id":"a","name":"A","uri":""}]}"""
        assertEquals(listOf("a"), manager.decodeProject(v6).layers.map { it.id })
    }

    @Test
    fun `plane placement, strokes layers and the camera survive a save and a load`() {
        val camera = com.hereliesaz.graffitixr.common.scene.SceneCamera(
            yaw = 0.4f, pitch = -0.2f, distanceScale = 1.7f, panX = 12f, panZ = -30f,
        )
        val point = com.hereliesaz.graffitixr.common.mesh.SpatialPoint(1f, 2f, 3f)
        val content = com.hereliesaz.graffitixr.common.scene.SpatialLayerContent(
            listOf(com.hereliesaz.graffitixr.common.mesh.SpatialStroke("s", listOf(point), -1, 0.2f)),
        )
        val project = GraffitiProject(
            id = "scene",
            sceneCamera = camera,
            layers = listOf(
                OverlayLayer(
                    id = "tilted", uri = mockk(relaxed = true), positionZ = -250f, rotationX = 20f, rotationY = -35f,
                ),
                OverlayLayer(id = "ink", uri = mockk(relaxed = true), spatial = content),
            ),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = manager.decodeProject(json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(camera, back.sceneCamera)
        assertEquals(-250f, back.layers[0].positionZ)
        assertEquals(20f, back.layers[0].rotationX)
        assertEquals(-35f, back.layers[0].rotationY)
        assertEquals(content, back.layers[1].spatial)
    }

    @Test
    fun `a project from a newer format is never overwritten`() = runTest {
        val dir = File(tempFilesDir, "projects/newer").also { it.mkdirs() }
        val newer = """{"id":"newer","name":"Future","formatVersion":99,"futureField":{"keep":true}}"""
        File(dir, "project.json").writeText(newer)

        val loaded = manager.loadProject(mockContext, "newer")
        assertEquals(99, loaded?.projectData?.formatVersion)
        assertFalse(manager.saveProject(mockContext, loaded!!.projectData.copy(name = "Edited")))

        assertEquals(newer, File(dir, "project.json").readText())
    }

    @Test
    fun `a current-format project still saves`() = runTest {
        assertTrue(manager.saveProject(mockContext, GraffitiProject(id = "cur", name = "Now")))
        assertTrue(manager.saveProject(mockContext, GraffitiProject(id = "cur", name = "Again")))
        assertEquals("Again", manager.loadProjectMetadata(mockContext, "cur")?.name)
    }

    @Test
    fun `a version 13 project becomes version 14 and nothing else changes`() {
        val v13 = GraffitiProject(id = "p", name = "Wall", formatVersion = 13)
        val migrated = manager.migrateFormat(v13, 13)
        assertEquals(14, migrated.formatVersion)
        assertEquals(v13.copy(formatVersion = 14), migrated)
    }

    @Test
    fun `a newer format is never lowered`() {
        val newer = GraffitiProject(id = "p", formatVersion = 15)
        assertEquals(15, manager.migrateFormat(newer, 15).formatVersion)
    }

    @Test
    fun `a version 3 sized group becomes a frame that looks the same`() {
        val v3 = """{"formatVersion":3,"id":"old","name":"Wall","layers":[""" +
            """{"id":"g","name":"Card","uri":"","layerType":"GROUP","layoutWidth":200.0,"layoutHeight":120.0},""" +
            """{"id":"plain","name":"Group","uri":"","layerType":"GROUP"},""" +
            """{"id":"c","name":"Child","uri":"","parentId":"g"}]}"""
        val project = manager.decodeProject(v3)
        assertEquals(com.hereliesaz.graffitixr.common.model.CURRENT_FORMAT_VERSION, project.formatVersion)
        val frame = project.layers.first { it.id == "g" }
        assertTrue(frame.isFrame)
        assertNull(frame.frameFillArgb)
        assertEquals(false, frame.clipsContent)
        assertEquals(200f, frame.layoutWidth)
        assertEquals(false, project.layers.first { it.id == "plain" }.isFrame)
        assertEquals("g", project.layers.first { it.id == "c" }.parentId)
    }

    @Test
    fun `a frame survives a save and a load and is written as a group`() {
        val project = GraffitiProject(
            id = "p",
            layers = listOf(
                OverlayLayer(
                    id = "f", name = "Pay", uri = mockk(relaxed = true),
                    layerType = com.hereliesaz.graffitixr.common.model.LayerType.GROUP,
                    isFrame = true, frameFillArgb = 0xFF1A1A1DL, clipsContent = true, frameRadius = 12f,
                    layoutWidth = 390f, layoutHeight = 844f,
                ),
            ),
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(GraffitiProject.serializer(), project)
        assertTrue(text.contains("\"layerType\":\"GROUP\""))
        val back = manager.decodeProject(text).layers.single()
        assertTrue(back.isFrame)
        assertEquals(0xFF1A1A1DL, back.frameFillArgb)
        assertTrue(back.clipsContent)
        assertEquals(12f, back.frameRadius)
    }

    @Test
    fun `an unreadable project json fails the save instead of passing as legacy or as newer`() = runTest {
        val dir = File(tempFilesDir, "projects/broken").also { it.mkdirs() }
        File(dir, "project.json").writeText("{ not json")
        val e = runCatching { manager.saveProject(mockContext, GraffitiProject(id = "broken", name = "Over")) }
        assertTrue(e.exceptionOrNull() is java.io.IOException)
        assertEquals("{ not json", File(dir, "project.json").readText())
    }

    @Test
    fun `a read error is an IO failure, never a read-only verdict, and is not cached`() = runTest {
        val dir = File(tempFilesDir, "projects/flaky").also { it.mkdirs() }
        // A directory where project.json should be: exists(), but every read throws IOException.
        val pj = File(dir, "project.json").also { it.mkdirs() }
        val first = runCatching { manager.saveProject(mockContext, GraffitiProject(id = "flaky", name = "A")) }
        assertTrue(first.exceptionOrNull() is java.io.IOException)
        val artifact = runCatching { manager.writeIfWritable(mockContext, "flaky") { "wrote" } }
        assertTrue(artifact.exceptionOrNull() is java.io.IOException)
        // The fault clears: the next write goes through (nothing remembered "read-only").
        pj.delete()
        File(dir, "project.json").writeText("""{"id":"flaky","name":"A"}""")
        assertEquals("wrote", manager.writeIfWritable(mockContext, "flaky") { "wrote" })
    }

    @Test
    fun `after a load, artifact writes and saves do not re-read project json`() = runTest {
        val project = GraffitiProject(id = "hot", name = "Hot")
        manager.saveProject(mockContext, project)
        val fresh = ProjectManager(mockContext, uriProvider, mockk(relaxed = true))
        assertEquals("Hot", fresh.loadProjectMetadata(mockContext, "hot")?.name)
        val pj = File(tempFilesDir, "projects/hot/project.json")
        // If the hot path parsed the file it would now fail; a cached verdict needs no read.
        pj.writeText("{ garbage")
        assertEquals("ok", fresh.writeIfWritable(mockContext, "hot") { "ok" })
        assertTrue(fresh.saveProject(mockContext, project.copy(name = "Saved")))
        assertEquals("Saved", fresh.loadProjectMetadata(mockContext, "hot")?.name)
    }

    @Test
    fun `a newer-format verdict is cached on load and dropped on delete`() = runTest {
        val dir = File(tempFilesDir, "projects/later").also { it.mkdirs() }
        File(dir, "project.json").writeText("""{"id":"later","name":"F","formatVersion":99}""")
        manager.loadProjectMetadata(mockContext, "later")
        assertNull(manager.writeIfWritable(mockContext, "later") { "x" })
        manager.deleteProject(mockContext, "later")
        assertTrue(manager.saveProject(mockContext, GraffitiProject(id = "later", name = "New")))
    }

    @Test
    fun `artifact writes into a newer-format project are refused`() = runTest {
        val dir = File(tempFilesDir, "projects/future").also { it.mkdirs() }
        File(dir, "project.json").writeText("""{"id":"future","name":"F","formatVersion":99}""")
        assertNull(manager.writeIfWritable(mockContext, "future") { File(dir, "layer.png").writeText("x") })
        assertFalse(File(dir, "layer.png").exists())
        assertEquals("ok", manager.writeIfWritable(mockContext, "fresh") { "ok" })
    }

    @Test
    fun `a save waits for an install of the same id and then sees its format`() = runTest {
        val dir = File(tempFilesDir, "projects/race")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holding = kotlinx.coroutines.CompletableDeferred<Unit>()
        // Stands in for installExtracted's move into place, which holds the id's lock.
        val install = launch(Dispatchers.IO) {
            manager.withProjectLock("race") {
                holding.complete(Unit)
                gate.await()
                dir.mkdirs()
                File(dir, "project.json").writeText("""{"id":"race","name":"Future","formatVersion":99}""")
            }
        }
        holding.await()
        val save = async(Dispatchers.IO) {
            manager.saveProject(mockContext, GraffitiProject(id = "race", name = "Old"))
        }
        Thread.sleep(200) // the save is parked on the lock, past nothing
        gate.complete(Unit)
        install.join()
        assertFalse(save.await())
        assertEquals("Future", manager.loadProjectMetadata(mockContext, "race")?.name)
    }

    @Test
    fun `format 11 persists a layer's view image link, and v9 and v10 files load into it`() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val image = OverlayLayer(
            id = "img", name = "A (side)", uri = mockk(relaxed = true),
            surfaceViewOf = "a", surfaceView = com.hereliesaz.graffitixr.common.scene.SurfaceView.SIDE,
        )
        val base = OverlayLayer(id = "a", name = "A", uri = mockk(relaxed = true))
        val project = GraffitiProject(id = "p", layers = listOf(base, image))
        val back = manager.decodeProject(json.encodeToString(GraffitiProject.serializer(), project))
        assertEquals(14, back.formatVersion)
        assertEquals("a", back.layers[1].surfaceViewOf)
        assertEquals(com.hereliesaz.graffitixr.common.scene.SurfaceView.SIDE, back.layers[1].surfaceView)
        assertNull(back.layers[0].surfaceViewOf)
        for (v in listOf(9, 10)) {
            val layers = """[{"id":"a","name":"A","uri":""}]"""
            val old = manager.decodeProject("""{"formatVersion":$v,"id":"old","name":"Old","layers":$layers}""")
            assertEquals(14, old.formatVersion)
            assertNull(old.layers.single().surfaceViewOf)
            assertNull(old.layers.single().surfaceView)
        }
    }
}
