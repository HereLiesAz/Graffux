package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
        val errors = ArrayList<Throwable>()
        val unreadable = ArrayList<String>()
        var writeDelayMs = 0L
        fun key(id: String, f: String) = "$id/$f"
    }

    private class Rig(
        scope: TestScope,
        io: kotlinx.coroutines.CoroutineDispatcher? = null,
        encoder: ((com.hereliesaz.graffitixr.common.azphalt.PaletteBoard) -> PaletteProjectSync.Encoded)? = null,
        val readOnly: () -> Boolean = { false },
        vmScope: kotlinx.coroutines.CoroutineScope? = null,
    ) {
        val disk = Disk()
        val board = newMixerBoard()
        val sync = PaletteProjectSync(
            board = board,
            scope = vmScope ?: scope,
            io = io ?: StandardTestDispatcher(scope.testScheduler),
            store = object : PaletteProjectSync.Store {
                override fun isReadOnly() = readOnly()
                override fun onError(error: Throwable) { disk.errors += error }
                override fun onUnreadable(projectId: String) { disk.unreadable += projectId }
                override suspend fun write(projectId: String, file: String, bytes: ByteArray) {
                    if (disk.writeDelayMs > 0) kotlinx.coroutines.delay(disk.writeDelayMs)
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

    private fun combined(board: com.hereliesaz.graffitixr.common.azphalt.PaletteBoard) = PaletteFile.pack(
        MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(board)),
        PaletteBoardPersistence.encodeBrushes(board),
    )

    private fun key(id: String, f: String = PaletteFile.COMBINED_FILE) = "$id/$f"

    @Test
    fun aPendingSaveForProjectAIsFlushedUnderAAndNeverWrittenUnderB() = runTest {
        val r = Rig(this)
        paint(r)
        r.sync.changed("A")
        advanceTimeBy(500)
        // the user opens project B before A's debounce fires: A's edit is saved under A, B starts empty
        r.sync.open("B")
        advanceUntilIdle()
        assertTrue("A's last edit was written under A", r.disk.files.containsKey(key("A")))
        assertFalse("and nothing under B", r.disk.files.keys.any { it.startsWith("B/") })
        assertEquals(0f, r.board.surface.amount.sum(), 0f)
        r.board.squeeze(4, 50f, 50f, 8f, 0.9f)
        r.sync.changed("B")
        advanceUntilIdle()
        assertTrue(r.disk.files.containsKey(key("B")))
        val a = requireNotNull(PaletteFile.unpack(requireNotNull(r.disk.files[key("A")])))
        val fresh = newMixerBoard()
        assertTrue(PaletteBoardPersistence.restore(fresh, requireNotNull(MaterialStateCodec.decode(a.first))))
        assertTrue("A's file holds A's paint (tube 2), not B's", fresh.surface.amount[20 * fresh.size + 20] > 0f)
        assertEquals("and not B's squeeze", 0f, fresh.surface.amount[50 * fresh.size + 50], 0f)
    }

    @Test
    fun closingTheProjectWritesItsPendingEditUnderItsOwnId() = runTest {
        val r = Rig(this)
        paint(r)
        r.sync.changed("A")
        r.sync.close()
        advanceUntilIdle()
        assertTrue(r.disk.files.containsKey(key("A")))
    }

    @Test
    fun flushWritesAPendingEditWithoutWaitingForTheDebounce() = runTest {
        val r = Rig(this)
        paint(r)
        r.sync.changed("A")
        r.sync.flush()
        assertTrue("written before 1.5 s passed", r.disk.files.containsKey(key("A")))
        advanceUntilIdle()
        assertEquals("and not written twice", 1, r.disk.writes.size)
    }

    @Test
    fun aReadOnlyProjectIsNotFlushed() = runTest {
        val r = Rig(this, readOnly = { true })
        paint(r)
        r.sync.changed("A")
        r.sync.close()
        r.sync.flush()
        advanceUntilIdle()
        assertTrue(r.disk.writes.isEmpty())
    }

    @Test
    fun aNewerChangeCancellingTheDebounceNeverSplitsOrFailsAWrite() = runTest {
        val r = Rig(this)
        r.disk.writeDelayMs = 300
        paint(r)
        r.sync.changed("A")
        advanceTimeBy(1600) // the write has started and is waiting
        r.board.squeeze(4, 50f, 50f, 8f, 0.9f)
        r.sync.changed("A") // cancels the (finished) debounce job; the in-flight write must still complete
        advanceUntilIdle()
        assertTrue("no spurious save failure from cancellation: ${r.disk.errors}", r.disk.errors.isEmpty())
        assertEquals("one file, so the pair cannot be split", listOf(key("A")), r.disk.files.keys.toList())
        assertTrue(PaletteFile.unpack(requireNotNull(r.disk.files[key("A")])) != null)
    }

    @Test
    fun aDamagedPaletteIsKeptAsABackupBeforeAnyEditOverwritesIt() = runTest {
        val r = Rig(this)
        val good = combined(newMixerBoard().also { it.squeeze(2, 20f, 20f, 8f, 0.9f) })
        val bad = good.also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        r.disk.files[key("A")] = bad
        r.sync.open("A")
        advanceUntilIdle()
        assertEquals(listOf("A"), r.disk.unreadable)
        val bak = key("A", PaletteFile.COMBINED_FILE + ".bak")
        assertTrue("kept as a backup", r.disk.files[bak]!!.contentEquals(bad))
        assertEquals(0f, r.board.surface.amount.sum(), 0f)
        // the next edit overwrites the live file but not the backup
        r.board.squeeze(4, 50f, 50f, 8f, 0.9f)
        r.sync.changed("A")
        advanceUntilIdle()
        assertTrue(PaletteFile.unpack(requireNotNull(r.disk.files[key("A")])) != null)
        assertTrue(r.disk.files[bak]!!.contentEquals(bad))
        assertEquals("the user is told once", 1, r.disk.unreadable.size)
    }

    @Test
    fun aBoardForADifferentTubeSetIsKeptAsABackupAndReported() = runTest {
        val r = Rig(this)
        val other = com.hereliesaz.graffitixr.common.azphalt.PaletteBoard(MIXER_BOARD_SIZE, MIXER_TUBES.reversed())
            .also { it.squeeze(2, 20f, 20f, 8f, 0.9f) }
        val bytes = combined(other)
        r.disk.files[key("A")] = bytes
        r.sync.open("A")
        advanceUntilIdle()
        assertEquals(listOf("A"), r.disk.unreadable)
        val bak = key("A", PaletteFile.COMBINED_FILE + ".bak")
        assertTrue(r.disk.files[bak]!!.contentEquals(bytes))
    }

    @Test
    fun aMissingPaletteIsNotAnError() = runTest {
        val r = Rig(this)
        r.sync.open("A")
        advanceUntilIdle()
        assertTrue(r.disk.unreadable.isEmpty() && r.disk.errors.isEmpty() && r.disk.writes.isEmpty())
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
        val bytes = requireNotNull(PaletteFile.unpack(requireNotNull(r.disk.files[key("A")]))).first
        val snap = requireNotNull(MaterialStateCodec.decode(bytes))
        assertTrue(PaletteBoardPersistence.restore(fresh, snap))
        assertTrue("the paint that was there at the change is what was saved", fresh.surface.amount.sum() > 0f)
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
        r.disk.files[key("A")] = combined(saved)
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
        r.disk.files[key("A")] = combined(saved)
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
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertTrue("on the IO thread: $callers", callers.single().startsWith("palette-io-test"))
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

    @Test
    fun aFlushHandedOffThenTheScopeCancelledIsStillWrittenByFlush() = runTest {
        val job = kotlinx.coroutines.SupervisorJob()
        val vmScope = kotlinx.coroutines.CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val r = Rig(this, vmScope = vmScope)
        paint(r)
        r.sync.changed("A")
        r.sync.flushAsync()
        vmScope.cancel() // the ViewModel clears before the queued write started
        r.sync.flush() // onCleared
        assertTrue("the edit was not lost", r.disk.files.containsKey(key("A")))
        assertEquals("written once", 1, r.disk.writes.size)
    }

    @Test
    fun anEditDuringTheReadDoesNotSkipTheBackupOfAnUnreadableFile() = runTest {
        val r = Rig(this)
        val good = combined(newMixerBoard().also { it.squeeze(2, 20f, 20f, 8f, 0.9f) })
        val bad = good.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        r.disk.files[key("A")] = bad
        r.sync.open("A")
        // the user edits before the read completes, and the edit is saved over the file
        r.board.squeeze(4, 50f, 50f, 8f, 0.9f)
        r.sync.changed("A")
        advanceUntilIdle()
        val bak = key("A", PaletteFile.COMBINED_FILE + ".bak")
        assertTrue("the unreadable file was preserved", r.disk.files[bak]!!.contentEquals(bad))
        assertEquals("and reported once", listOf("A"), r.disk.unreadable)
        val live = PaletteFile.unpack(r.disk.files[key("A")]!!)
        assertTrue("the edit is what is in the live file", live != null)
        assertTrue("the edit was not wiped", r.board.surface.amount[50 * r.board.size + 50] > 0f)
    }

    @Test
    fun aUsablePaletteReadDuringAnEditIsNotAppliedButNotReportedEither() = runTest {
        val r = Rig(this)
        r.disk.files[key("A")] = combined(newMixerBoard().also { it.squeeze(2, 20f, 20f, 8f, 0.9f) })
        r.sync.open("A")
        r.board.squeeze(4, 50f, 50f, 8f, 0.9f)
        r.sync.changed("A")
        advanceUntilIdle()
        assertTrue(r.disk.unreadable.isEmpty())
        assertEquals(0f, r.board.surface.amount[20 * r.board.size + 20], 0f)
    }
}
