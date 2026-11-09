package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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

    private class Rig(
        scope: TestScope,
        io: kotlinx.coroutines.CoroutineDispatcher? = null,
        encoder: ((com.hereliesaz.graffitixr.common.azphalt.PaletteBoard) -> PaletteProjectSync.Encoded)? = null,
        val readOnly: () -> Boolean = { false },
    ) {
        val disk = Disk()
        val board = newMixerBoard()
        val sync = PaletteProjectSync(
            board = board,
            scope = scope,
            io = io ?: StandardTestDispatcher(scope.testScheduler),
            store = object : PaletteProjectSync.Store {
                override fun isReadOnly() = readOnly()
                override suspend fun write(projectId: String, file: String, bytes: ByteArray) {
                    disk.files[disk.key(projectId, file)] = bytes
                    disk.writes += disk.key(projectId, file)
                }
                override suspend fun read(projectId: String, file: String) = disk.files[disk.key(projectId, file)]
            },
            encoder = encoder ?: { b ->
                PaletteProjectSync.Encoded(
                    MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(b)),
                    PaletteBoardPersistence.encodeBrushes(b),
                )
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
        val r = Rig(this, readOnly = { ro })
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

    @Test
    fun encodingNeverRunsOnTheCallingThreadAndRunsOnTheIoDispatcher() = runTest {
        val callers = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "palette-io-test") }
        try {
            val r = Rig(this, io = exec.asCoroutineDispatcher(), encoder = { _ ->
                callers += Thread.currentThread().name
                latch.countDown()
                PaletteProjectSync.Encoded(byteArrayOf(1), byteArrayOf(1))
            })
            paint(r)
            r.sync.changed("A")
            assertTrue("changed() encodes nothing on the calling thread", callers.isEmpty())
            advanceTimeBy(2000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf("palette-io-test"), callers.toList())
        } finally {
            exec.shutdown()
        }
    }

    @Test
    fun rapidChangesEncodeOnceAfterTheDebounceWithTheLatestBoard() = runTest {
        var encodes = 0
        var lastAmount = 0f
        val r = Rig(this, encoder = { b ->
            encodes++
            lastAmount = b.surface.amount.sum()
            PaletteProjectSync.Encoded(byteArrayOf(1), byteArrayOf(1))
        })
        paint(r)
        repeat(60) { i ->
            r.board.squeeze(2, 10f + i, 30f, 3f, 0.2f)
            r.sync.changed("A")
            advanceTimeBy(10)
        }
        assertEquals("nothing encoded while the burst is still going", 0, encodes)
        val final = r.board.surface.amount.sum()
        advanceUntilIdle()
        assertEquals(1, encodes)
        assertEquals("and it encoded the latest board", final, lastAmount, 1e-3f)
    }
}
