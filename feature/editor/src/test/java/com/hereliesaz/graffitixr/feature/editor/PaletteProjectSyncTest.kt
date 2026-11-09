package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Palette persistence in the project: which project a board is written to, read-only, and the restore race. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaletteProjectSyncTest {
    private class Disk {
        val files = HashMap<String, ByteArray>()
        val writes = ArrayList<String>()
        fun key(id: String, f: String) = "$id/$f"
    }

    private class Rig(scope: TestScope, val readOnly: () -> Boolean = { false }) {
        val disk = Disk()
        val board = newMixerBoard()
        val sync = PaletteProjectSync(
            board = board,
            scope = scope,
            io = StandardTestDispatcher(scope.testScheduler),
            store = object : PaletteProjectSync.Store {
                override fun isReadOnly() = readOnly()
                override suspend fun write(projectId: String, file: String, bytes: ByteArray) {
                    disk.files[disk.key(projectId, file)] = bytes
                    disk.writes += disk.key(projectId, file)
                }
                override suspend fun read(projectId: String, file: String) = disk.files[disk.key(projectId, file)]
            },
        )
    }

    private fun TestScope.paint(r: Rig, slot: Int = 2) {
        r.board.squeeze(slot, 20f, 20f, 8f, 0.9f)
        r.board.dip(20f, 20f)
    }

    @Test
    fun aPendingSaveForProjectANeverWritesProjectBsBoard() = runTest {
        val r = Rig(this)
        paint(r)
        r.sync.changed("A")
        advanceTimeBy(500)
        // the user opens project B before A's debounce fires: B starts empty
        r.sync.open("B")
        advanceUntilIdle()
        assertTrue("nothing was written for A after the switch", r.disk.writes.isEmpty())
        assertEquals(0f, r.board.surface.amount.sum(), 0f)
        r.board.squeeze(4, 50f, 50f, 8f, 0.9f)
        r.sync.changed("B")
        advanceUntilIdle()
        assertTrue(r.disk.writes.all { it.startsWith("B/") })
        assertFalse(r.disk.files.keys.any { it.startsWith("A/") })
    }

    @Test
    fun theSavedBytesAreTheBoardAtTheMomentOfTheChange() = runTest {
        val r = Rig(this)
        paint(r, 2)
        r.sync.changed("A")
        // the board is then cleared by something else before the debounce fires; the snapshot was already taken
        r.board.clear()
        advanceUntilIdle()
        val fresh = newMixerBoard()
        val bytes = requireNotNull(r.disk.files["A/" + PaletteBoardPersistence.BOARD_FILE])
        val snap = requireNotNull(MaterialStateCodec.decode(bytes))
        assertTrue(PaletteBoardPersistence.restore(fresh, snap))
        assertTrue("the paint that was there at the change is what was saved", fresh.surface.amount.sum() > 0f)
    }

    @Test
    fun closingAProjectCancelsItsPendingSave() = runTest {
        val r = Rig(this)
        paint(r)
        r.sync.changed("A")
        r.sync.close()
        advanceUntilIdle()
        assertTrue(r.disk.writes.isEmpty())
    }

    @Test
    fun aReadOnlyProjectIsNeverWritten() = runTest {
        var ro = true
        val r = Rig(this) { ro }
        paint(r)
        r.sync.changed("A")
        advanceUntilIdle()
        assertTrue("read-only at the change", r.disk.writes.isEmpty())
        // read-only turns on between the change and the write
        ro = false
        r.sync.changed("A")
        ro = true
        advanceUntilIdle()
        assertTrue("read-only at the write", r.disk.writes.isEmpty())
    }

    @Test
    fun anEditMadeWhileTheFileIsBeingReadIsNotWipedByTheRestore() = runTest {
        val r = Rig(this)
        val saved = newMixerBoard().also { it.squeeze(4, 60f, 60f, 8f, 0.9f) }
        r.disk.files["A/" + PaletteBoardPersistence.BOARD_FILE] =
            MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(saved))
        r.sync.open("A")
        // the user squeezes a tube before the read completes
        r.board.squeeze(2, 20f, 20f, 8f, 0.9f)
        r.sync.changed("A")
        advanceUntilIdle()
        assertTrue("the edit survives", r.board.surface.amount[20 * r.board.size + 20] > 0f)
        assertEquals("and the stale file did not replace it", 0f, r.board.surface.amount[60 * r.board.size + 60], 0f)
    }

    @Test
    fun anUntouchedBoardIsRestoredFromTheProjectAndAStaleRestoreForAnotherProjectIsDropped() = runTest {
        val r = Rig(this)
        val saved = newMixerBoard().also { it.squeeze(4, 60f, 60f, 8f, 0.9f) }
        r.disk.files["A/" + PaletteBoardPersistence.BOARD_FILE] =
            MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(saved))
        r.sync.open("A")
        advanceUntilIdle()
        assertTrue(r.board.surface.amount[60 * r.board.size + 60] > 0f)
        // opening B while A's read is in flight: A's late result must not land
        r.sync.open("A")
        r.sync.open("B")
        advanceUntilIdle()
        assertEquals(0f, r.board.surface.amount.sum(), 0f)
    }
}
