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
            val tasks = ids.map { id ->
                java.util.concurrent.Callable {
                    repository.save(id, brush)
                    repository.delete(id)
                    repository.save(id, brush)
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
            repository.refresh()

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
