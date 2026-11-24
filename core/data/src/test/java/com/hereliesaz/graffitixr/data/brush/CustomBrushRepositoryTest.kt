package com.hereliesaz.graffitixr.data.brush

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A glee audit flagged `save`/`delete` building a filesystem path directly from an unsanitized
 * `id` -- no current caller supplies an untrusted one (always a `UUID.randomUUID()` string round-
 * tripped through this same repository), so this is defensive hardening rather than a fix for a
 * reachable bug. These tests pin the contract the hardening is supposed to guarantee.
 */
class CustomBrushRepositoryTest {

    private lateinit var tempFilesDir: File
    private lateinit var repository: CustomBrushRepository

    @Before
    fun setup() {
        tempFilesDir = File(System.getProperty("java.io.tmpdir"), "custom_brush_repo_test_${System.nanoTime()}")
        tempFilesDir.mkdirs()
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempFilesDir
        repository = CustomBrushRepository(context)
    }

    private val brush = AzphaltBrush(name = "Test")

    @Test
    fun `save rejects a path-traversal id and writes nothing`() {
        val ok = repository.save("../../evil", brush)
        assertFalse(ok)
        assertTrue(
            "an unsafe id must write nothing at all, inside or outside the brushes directory",
            File(tempFilesDir, "brushes").listFiles()?.isEmpty() != false,
        )
    }

    @Test
    fun `save accepts an ordinary uuid-shaped id`() {
        val id = "3fa1c2b0-1234-4abc-9def-0123456789ab"
        assertTrue(repository.save(id, brush))
        assertTrue(File(tempFilesDir, "brushes/$id.json").exists())
    }

    @Test
    fun `delete silently ignores a path-traversal id`() {
        val id = "3fa1c2b0-1234-4abc-9def-0123456789ab"
        repository.save(id, brush)
        repository.delete("../$id")
        // The real file must survive: an unsafe id must never resolve to a real one's path.
        assertTrue(File(tempFilesDir, "brushes/$id.json").exists())
    }

    @Test
    fun `save leaves no stray temp file behind`() {
        // A glee audit found save() wrote "$id.json" in place with a truncating writeText, unlike
        // the write-then-rename this repository now uses -- pin that the rename actually happens
        // and doesn't leave the intermediate ".tmp" file sitting in the brushes directory.
        val id = "3fa1c2b0-1234-4abc-9def-0123456789ab"
        assertTrue(repository.save(id, brush))
        val files = File(tempFilesDir, "brushes").listFiles()!!.map { it.name }
        assertTrue("only the final file must remain", files == listOf("$id.json"))
    }

    @Test
    fun `imported assets survive reopening and editing then disappear with their brush`() {
        val id = "imported-brush"
        val shape = byteArrayOf(1, 2, 3)
        val grain = byteArrayOf(4, 5)
        assertTrue(repository.saveImported(id, brush, shape, grain))
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempFilesDir
        val reopened = CustomBrushRepository(context)
        val imported = requireNotNull(reopened.load(id))
        assertTrue(imported.shapePath == "shape.png" && imported.grainPath == "grain.png")
        assertTrue(reopened.save(id, imported.copy(name = "Renamed")))
        assertTrue(requireNotNull(reopened.assetFile(id, "shape.png")).readBytes().contentEquals(shape))
        assertTrue(requireNotNull(reopened.assetFile(id, "grain.png")).readBytes().contentEquals(grain))
        assertTrue(reopened.assetFile(id, "../shape.png") == null)
        assertFalse(reopened.saveImported(id, brush, shape, grain))
        reopened.delete(id)
        assertTrue(reopened.load(id) == null)
        assertTrue(reopened.assetFile(id, "shape.png") == null && reopened.assetFile(id, "grain.png") == null)
    }

    @Test
    fun `set identity and source order survive reopen and brush edits`() {
        val set = ImportedBrushSetMembership("set-a", "Ink", 0)
        assertTrue(repository.saveImported("z", brush.copy(name = "Zulu"), null, null, set))
        assertTrue(repository.saveImported("a", brush.copy(name = "Alpha"), null, null, set.copy(order = 1)))
        assertTrue(repository.saveImported("other", brush, null, null, set.copy(id = "set-b")))
        assertTrue(repository.save("loose", brush))
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempFilesDir
        val reopened = CustomBrushRepository(context)
        assertTrue(reopened.brushes.value.map { it.id } == listOf("loose", "z", "a", "other"))
        assertTrue(reopened.save("z", brush.copy(name = "Renamed")))
        assertTrue(reopened.brushes.value.first { it.id == "z" }.importedSet == set)
        assertTrue(reopened.brushes.value.map { it.id } == listOf("loose", "z", "a", "other"))
        reopened.delete("z")
        assertTrue(reopened.brushes.value.map { it.id } == listOf("loose", "a", "other"))
    }

    @Test
    fun `library set order survives reopen even when names sort differently`() {
        assertTrue(repository.saveImported("a", brush, null, null, ImportedBrushSetMembership("set-a", "Alpha", 0, "lib", 1)))
        assertTrue(repository.saveImported("z", brush, null, null, ImportedBrushSetMembership("set-z", "Zulu", 0, "lib", 0)))
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempFilesDir
        assertTrue(CustomBrushRepository(context).brushes.value.map { it.id } == listOf("z", "a"))
    }

    @Test
    fun `duplicate retains independent assets and appends to the source set`() {
        val set = ImportedBrushSetMembership("set", "Ink", 3)
        assertTrue(repository.saveImported("source", brush, byteArrayOf(1, 2), byteArrayOf(3), set))
        assertTrue(repository.saveImported("last", brush, null, null, set.copy(order = 7)))
        assertTrue(repository.duplicate("source", "copy"))
        val copy = repository.brushes.value.single { it.id == "copy" }
        assertTrue(copy.importedSet == set.copy(order = 8))
        assertTrue(copy.brush.name == "Test copy")
        assertFalse(repository.duplicate("source", "last"))
        assertFalse(repository.duplicate("source", "../outside"))
        repository.delete("source")
        assertTrue(repository.assetFile("copy", "shape.png")!!.readBytes().contentEquals(byteArrayOf(1, 2)))
        assertTrue(repository.assetFile("copy", "grain.png")!!.readBytes().contentEquals(byteArrayOf(3)))
    }

    @Test
    fun `duplicate fails when an owned image is missing`() {
        assertTrue(repository.saveImported("source", brush, byteArrayOf(1), null))
        repository.assetFile("source", "shape.png")!!.delete()
        assertFalse(repository.duplicate("source", "copy"))
        assertTrue(repository.load("copy") == null)
    }

    @Test
    fun `startup removes only orphan owned files and retains corrupt brush metadata assets`() {
        val dir = File(tempFilesDir, "brushes")
        assertTrue(repository.saveImported("valid", brush, byteArrayOf(1), null))
        File(dir, "orphan.shape.png").writeBytes(byteArrayOf(2))
        File(dir, "orphan.grain.png").writeBytes(byteArrayOf(3))
        File(dir, "orphan.json.tmp").writeText("interrupted")
        File(dir, "corrupt.json").writeText("not json")
        File(dir, "corrupt.shape.png").writeBytes(byteArrayOf(4))
        File(dir, "unknown.png").writeBytes(byteArrayOf(5))
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempFilesDir
        val reopened = CustomBrushRepository(context)
        assertTrue(reopened.load("valid") != null)
        assertTrue(dir.list()!!.toSet() == setOf(
            "valid.json", "valid.shape.png", "corrupt.json", "corrupt.shape.png", "unknown.png",
        ))
    }

    @Test
    fun `move changes membership without changing assets or brush ID`() {
        val target = ImportedBrushSetMembership("target", "Ink", 5, "library", 2)
        assertTrue(repository.saveImported("member", brush, null, null, target))
        assertTrue(repository.saveImported("loose", brush, byteArrayOf(9), null))
        assertFalse(repository.moveToSet("loose", "missing"))
        assertTrue(repository.moveToSet("loose", "target"))
        assertTrue(repository.brushes.value.single { it.id == "loose" }.importedSet == target.copy(order = 6))
        assertTrue(repository.moveToSet("loose", null))
        assertTrue(repository.brushes.value.single { it.id == "loose" }.importedSet == null)
        assertTrue(repository.assetFile("loose", "shape.png")!!.readBytes().contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `failed asset import leaves no visible or partial brush`() {
        val id = "failed-import"
        assertFalse(repository.saveImported(id, brush, byteArrayOf(1), byteArrayOf()))
        assertTrue(repository.load(id) == null)
        assertTrue(File(tempFilesDir, "brushes").listFiles()?.isEmpty() == true)
    }

    @Test
    fun `concurrent save and delete from different threads never corrupt the observed brush list`() {
        // A glee audit found refresh() unsynchronized: a save's directory scan (listFiles, then
        // decode each file) could still be in flight when an unrelated delete's own refresh()
        // finishes, and the earlier scan's stale, still-includes-the-deleted-brush result could
        // then overwrite _brushes.value afterward -- a last-writer-wins loss. This hammers save/
        // delete from real parallel threads (not a single-threaded dispatcher, which wouldn't
        // exercise the race at all) and asserts the final state is self-consistent: every id still
        // reported in `brushes` has a real file on disk, and every id NOT reported has none.
        val ids = (1..12).map { "id$it-3fa1c2b0-1234-4abc-9def-0123456789ab" }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            // Half the ids end deleted and half saved, so "reported == on disk" can't hold trivially.
            val tasks = ids.mapIndexed { index, id ->
                java.util.concurrent.Callable {
                    repository.save(id, brush)
                    repository.delete(id)
                    if (index % 2 == 0) repository.save(id, brush)
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
            // No refresh() here: that would rebuild the observed list from disk and make the
            // comparison below true by construction. The state the concurrent calls left behind
            // is what's under test.

            val reported = repository.brushes.value.map { it.id }.toSet()
            val onDisk = File(tempFilesDir, "brushes").listFiles()
                ?.map { it.nameWithoutExtension }
                ?.toSet()
                ?: emptySet()
            assertTrue(
                "every id CustomBrushRepository reports must have a real file on disk: " +
                    "reported=$reported onDisk=$onDisk",
                reported == onDisk,
            )
        } finally {
            executor.shutdown()
        }
    }
}
