package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The brush-preview export must stay inside its output root and always write valid JSON. */
class PackRenderExportTest {
    private fun tempRoot(): File = kotlin.io.path.createTempDirectory("brushpreviews").toFile()

    @Test
    fun hostilePackIdsAreRejected() {
        val root = tempRoot()
        for (id in listOf("", ".", "..", "../unrelated", "a/b", "a\\b", "/abs", "a\u0000b", "..\\x")) {
            assertNull(PackRenderHarness.packOutputDir(root, id), "id <$id> must be rejected")
        }
    }

    @Test
    fun aSafePackIdResolvesStrictlyBeneathTheRoot() {
        val root = tempRoot()
        val dir = assertNotNull(PackRenderHarness.packOutputDir(root, "com.example.pack-1"))
        assertTrue(dir.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertTrue(dir.canonicalPath != root.canonicalPath)
    }

    @Test
    fun aSymlinkedPackDirOutsideTheRootIsRejected() {
        val root = tempRoot()
        val outside = tempRoot()
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertNull(PackRenderHarness.packOutputDir(root, "link"))
    }

    @Test
    fun indexJsonSurvivesQuotesAndBackslashesInPathsAndLabels() {
        val nasty = "assets/tip\"one\\two.png"
        val text = PackRenderHarness.previewIndexJson("p", 7L, 26f, listOf(PackRenderHarness.Companion.IndexBrush(nasty, listOf("01/01.png" to "sw\"oop"))))
        val parsed = Json.parseToJsonElement(text) as JsonObject
        val brush = ((parsed["brushes"] as JsonArray)[0]) as JsonObject
        assertEquals(nasty, (brush["asset"] as JsonPrimitive).content)
        assertEquals("sw\"oop", (((brush["strokes"] as JsonArray)[0] as JsonObject)["label"] as JsonPrimitive).content)
    }
}
