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
}
