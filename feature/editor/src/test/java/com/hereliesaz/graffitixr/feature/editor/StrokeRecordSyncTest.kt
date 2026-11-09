package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.StampStrokeRecord
import com.hereliesaz.graffitixr.common.azphalt.StrokeRecording
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

/** Stroke recordings in the project: which project and document a save belongs to, flush, restore races, backups. */
@OptIn(ExperimentalCoroutinesApi::class)
class StrokeRecordSyncTest {
    private class Disk {
        val files = HashMap<String, ByteArray>()
        val writes = ArrayList<String>()
        val unreadable = ArrayList<Pair<String, Int>>()
        val backedUp = ArrayList<Boolean>()
        val delays = ArrayDeque<Long>()
        var failBak = false
        var reads = 0
        val readOnlyIds = HashSet<String>()
        var readDelayMs = 0L
    }

    private class Rig(
        scope: TestScope,
        sharedDisk: Disk? = null,
        io: kotlinx.coroutines.CoroutineDispatcher? = null,
        decoder: ((String) -> StrokeRecording.Companion.Repaired?)? = null,
        val readOnly: () -> Boolean = { false },
    ) {
        val disk = sharedDisk ?: Disk()
        val sync = StrokeRecordSync(
            io = io ?: StandardTestDispatcher(scope.testScheduler),
            store = object : StrokeRecordSync.Store {
                override fun isReadOnly(projectId: String) = readOnly() || projectId in disk.readOnlyIds
                override fun onUnreadable(projectId: String, dropped: Int, backedUp: Boolean) {
                    disk.unreadable += projectId to dropped
                    disk.backedUp += backedUp
                }
                override suspend fun write(projectId: String, file: String, bytes: ByteArray) {
                    if (file.endsWith(".bak") && disk.failBak) error("no space left")
                    if (!file.endsWith(".bak")) disk.delays.removeFirstOrNull()?.let { kotlinx.coroutines.delay(it) }
                    disk.files["$projectId/$file"] = bytes
                    disk.writes += "$projectId/$file"
                }
                override suspend fun read(projectId: String, file: String): ByteArray? {
                    disk.reads++
                    if (disk.readDelayMs > 0) kotlinx.coroutines.delay(disk.readDelayMs)
                    return disk.files["$projectId/$file"]
                }
            },
            limits = StrokeRecordSync.Limits(maxStrokes = 5, maxSamples = 1_000),
            decoder = decoder ?: StrokeRecording::fromJsonRepairing,
        )
    }

    private fun stroke(seed: Long) = StampStrokeRecord(
        brush = AzphaltBrush(name = "t"), seed = seed, diameterPx = 10f, colorArgb = 0,
        samples = BrushSampleBuilder().let { b -> (0..3).map { b.add(it * 3f, 4f, it * 8L, 0.5f) } },
    )

    @org.junit.Before
    fun resetQueue() = StrokeWriteQueue.reset()

    private val doc = StrokeRecordSync.Doc(300, 200, "paper")
    private fun file(r: Rig, id: String) =
        StrokeRecording.fromJson(String(requireNotNull(r.disk.files["$id/strokes.json"])))

    private fun recording(vararg seeds: Long) =
        StrokeRecording(canvasWidth = 100, canvasHeight = 100, strokes = seeds.map { stroke(it) }).toJson()

    @Test
    fun theSaveIsTheSnapshotAtTheChangeNotLaterState() = runTest {
        val r = Rig(this)
        r.sync.record("A", stroke(1), doc)
        // the document changes and another stroke is drawn before the debounce: the write is the LAST change's snapshot
        r.sync.record("A", stroke(2), StrokeRecordSync.Doc(640, 480, "canvas"))
        advanceUntilIdle()
        val saved = requireNotNull(file(r, "A"))
        assertEquals(listOf(1L, 2L), saved.strokes.map { it.seed })
        assertEquals(640, saved.canvasWidth)
        assertEquals("canvas", saved.substrateId)
    }

    @Test
    fun aPendingSaveForAIsFlushedUnderAAndNeverWrittenUnderB() = runTest {
        val r = Rig(this)
        r.sync.record("A", stroke(1), doc)
        advanceTimeBy(500)
        r.sync.open("B")
        advanceUntilIdle()
        assertEquals(listOf(1L), requireNotNull(file(r, "A")).strokes.map { it.seed })
        assertFalse(r.disk.files.keys.any { it.startsWith("B/") })
        assertTrue(r.sync.current().isEmpty())
    }

    @Test
    fun aHandedOffFlushThenTheFinalFlushWritesOnce() = runTest {
        val r = Rig(this)
        r.sync.record("A", stroke(1), doc)
        r.sync.flushAsync()
        r.sync.flush() // onCleared
        assertEquals(1, r.disk.writes.size)
    }

    @Test
    fun aReadOnlyProjectIsNeverWritten() = runTest {
        val r = Rig(this, readOnly = { true })
        r.sync.record("A", stroke(1), doc)
        r.sync.close()
        r.sync.flush()
        advanceUntilIdle()
        assertTrue(r.disk.writes.isEmpty())
    }

    @Test
    fun anEditDuringTheRestoreWinsAndKeepsWhatWasLoaded() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1, 2).toByteArray()
        r.sync.open("A")
        r.sync.record("A", stroke(9), doc)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 9L), r.sync.current().map { it.seed })
    }

    @Test
    fun aStaleRestoreForAnotherProjectIsDropped() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1).toByteArray()
        r.sync.open("A")
        r.sync.open("B")
        advanceUntilIdle()
        assertTrue(r.sync.current().isEmpty())
    }

    @Test
    fun theOldestStrokesDropFirstAtTheCap() = runTest {
        val r = Rig(this)
        r.sync.open("A")
        advanceUntilIdle()
        (1L..8L).forEach { r.sync.record("A", stroke(it), doc) }
        assertEquals((4L..8L).toList(), r.sync.current().map { it.seed })
    }

    @Test
    fun oneBadStrokeKeepsTheRestAndTheOriginalFileIsBackedUp() = runTest {
        val r = Rig(this)
        val good = recording(1, 2)
        val damaged = good.replace("\"strokes\":[", "\"strokes\":[42,").toByteArray()
        r.disk.files["A/strokes.json"] = damaged
        r.sync.open("A")
        // an edit during the read must not bypass the backup
        r.sync.record("A", stroke(9), doc)
        advanceUntilIdle()
        assertTrue(r.disk.files["A/strokes.json.bak"]!!.contentEquals(damaged))
        assertEquals(listOf("A" to 1), r.disk.unreadable)
        assertEquals("and the user is told it was kept", listOf(true), r.disk.backedUp)
        assertEquals(listOf(1L, 2L, 9L), requireNotNull(file(r, "A")).strokes.map { it.seed })
    }

    @Test
    fun aWholeFileThatCannotBeReadIsKeptAndTheListStartsEmpty() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = "garbage".toByteArray()
        r.sync.open("A")
        advanceUntilIdle()
        assertEquals("garbage", String(r.disk.files["A/strokes.json.bak"]!!))
        assertEquals(listOf("A" to -1), r.disk.unreadable)
        assertTrue(r.sync.current().isEmpty())
    }

    @Test
    fun anOlderSlowWriteCanNeverBeTheFinalFile() = runTest {
        val r = Rig(this)
        r.disk.delays += listOf(1_000L, 10L) // the first write is slow, the second fast
        r.sync.record("A", stroke(1), doc)
        advanceTimeBy(2_100) // the debounced write of [1] has started and is waiting on IO
        r.sync.record("A", stroke(2), doc)
        r.sync.flush() // the newest snapshot, while the older one is still in flight
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), requireNotNull(file(r, "A")).strokes.map { it.seed })
    }

    @Test
    fun theRepairingDecodeRunsOnTheIoDispatcherNotTheCaller() = runTest {
        val threads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "stroke-io-test") }
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            val r = Rig(this, io = exec.asCoroutineDispatcher(), decoder = { text ->
                threads += Thread.currentThread().name
                latch.countDown()
                StrokeRecording.fromJsonRepairing(text)
            })
            r.disk.files["A/strokes.json"] = recording(1).toByteArray()
            r.sync.open("A")
            advanceUntilIdle()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertTrue("decoded on the IO thread: $threads", threads.single().startsWith("stroke-io-test"))
        } finally {
            exec.shutdown()
        }
    }

    @Test
    fun aFailedBackupBlocksTheOverwriteAndTheMessageSaysSo() = runTest {
        val r = Rig(this)
        val damaged = recording(1, 2).replace("\"strokes\":[", "\"strokes\":[42,").toByteArray()
        r.disk.files["A/strokes.json"] = damaged
        r.disk.failBak = true
        r.sync.open("A")
        advanceUntilIdle()
        assertEquals("the user is told the backup did NOT happen", listOf(false), r.disk.backedUp)
        r.sync.record("A", stroke(9), doc)
        r.sync.flush()
        advanceUntilIdle()
        assertTrue("the original is untouched", r.disk.files["A/strokes.json"]!!.contentEquals(damaged))
        // a reopen with a working disk can back up and then save
        r.disk.failBak = false
        r.sync.open("A")
        advanceUntilIdle()
        assertTrue(r.disk.files["A/strokes.json.bak"]!!.contentEquals(damaged))
        r.sync.record("A", stroke(10), doc)
        r.sync.flush()
        assertEquals(listOf(true), r.disk.backedUp.takeLast(1))
        assertTrue(!r.disk.files["A/strokes.json"]!!.contentEquals(damaged))
    }

    @Test
    fun aFlushDuringTheInitialRestoreDoesNotReplaceTheFileWithOnlyTheNewStroke() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1, 2).toByteArray()
        r.sync.open("A")
        r.sync.record("A", stroke(9), doc) // before the read completes
        r.sync.flushAsync() // the app goes to the background
        assertTrue("nothing written while the restore is out", r.disk.writes.isEmpty())
        advanceUntilIdle()
        val saved = requireNotNull(file(r, "A")).strokes.map { it.seed }
        assertEquals("the corrected snapshot is what is saved", listOf(1L, 2L, 9L), saved)
    }

    @Test
    fun closingDuringTheInitialRestoreStillSavesTheMergedStrokesUnderTheirOwnProject() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1, 2).toByteArray()
        r.sync.open("A")
        r.sync.record("A", stroke(9), doc)
        r.sync.open("B") // switched before A's read finished
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 9L), requireNotNull(file(r, "A")).strokes.map { it.seed })
        assertTrue(r.sync.current().isEmpty())
    }

    @Test
    fun switchingAwayAndBackWhileTheRestoreIsOutJoinsTheSameRestoreAndKeepsEveryEdit() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1, 2).toByteArray()
        r.disk.readDelayMs = 5_000
        r.sync.open("A")
        r.sync.record("A", stroke(9), doc) // first session, held
        r.sync.open("B")
        r.sync.open("A") // second session while the first restore is still out
        r.sync.record("A", stroke(10), doc) // held too
        advanceUntilIdle()
        assertEquals("one restore, not two", 1, r.disk.reads - 1) // B's read is the other one
        assertEquals(listOf(1L, 2L, 9L, 10L), requireNotNull(file(r, "A")).strokes.map { it.seed })
        assertEquals(listOf(1L, 2L, 9L, 10L), r.sync.current().map { it.seed })
    }

    @Test
    fun theFinalFlushDuringARestoreStillSavesTheHeldEdits() = runTest {
        // onCleared: the ViewModel scope is already gone, so nothing may depend on it
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1, 2).toByteArray()
        r.disk.readDelayMs = 5_000
        r.sync.open("A")
        r.sync.record("A", stroke(9), doc)
        r.sync.flush()
        assertEquals(listOf(1L, 2L, 9L), requireNotNull(file(r, "A")).strokes.map { it.seed })
    }

    @Test
    fun aReopenedProjectSeesItsStrokesEvenBeforeItsLastWriteLanded() = runTest {
        val r = Rig(this)
        r.disk.delays += 3_000L // the close-flush write is slow
        r.sync.open("A")
        advanceUntilIdle()
        r.sync.record("A", stroke(1), doc)
        r.sync.open("B") // flushes A's [1] (slowly)
        r.sync.open("A") // reopened before that write landed: the kept state, not a stale re-read
        assertEquals(listOf(1L), r.sync.current().map { it.seed })
        r.sync.record("A", stroke(2), doc)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), requireNotNull(file(r, "A")).strokes.map { it.seed })
    }

    @Test
    fun theFinalFlushWaitsForADebouncedWriteAlreadyInFlight() = runTest {
        val r = Rig(this)
        r.disk.delays += 5_000L
        r.sync.record("A", stroke(1), doc)
        advanceTimeBy(2_100) // the debounce fired: the write has taken the snapshot and is waiting on IO
        assertTrue(r.disk.files.isEmpty())
        r.sync.flush() // onCleared
        val seeds = requireNotNull(file(r, "A")).strokes.map { it.seed }
        assertEquals("flush returned only after that write landed", listOf(1L), seeds)
    }

    @Test
    fun aNewInstanceRestoresOnlyAfterTheOldInstancesWriteHasLanded() = runTest {
        val first = Rig(this)
        first.disk.delays += 5_000L
        first.sync.record("A", stroke(1), doc)
        advanceTimeBy(2_100) // the old ViewModel's write is in flight; it is cleared without waiting
        val second = Rig(this, sharedDisk = first.disk) // the next ViewModel, a new sync on the same project
        second.sync.open("A")
        advanceUntilIdle()
        assertEquals("it saw the previous session's stroke", listOf(1L), second.sync.current().map { it.seed })
        second.sync.record("A", stroke(2), doc)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), requireNotNull(file(second, "A")).strokes.map { it.seed })
    }

    @Test
    fun aRestoreFinishingAfterASwitchToAReadOnlyProjectStillSavesTheWritableOnesEdits() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1, 2).toByteArray()
        r.disk.readDelayMs = 5_000
        r.disk.readOnlyIds += "B"
        r.sync.open("A")
        r.sync.record("A", stroke(9), doc)
        r.sync.open("B") // newer-format, read-only
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 9L), requireNotNull(file(r, "A")).strokes.map { it.seed })
        assertTrue("and nothing was written for the read-only one", r.disk.files.keys.none { it.startsWith("B/") })
    }

    @Test
    fun aReadOnlyProjectStaysUnwrittenEvenAfterItIsNoLongerTheOpenOne() = runTest {
        val r = Rig(this)
        r.disk.readOnlyIds += "R"
        r.sync.open("R")
        advanceUntilIdle()
        r.sync.record("R", stroke(1), doc)
        r.sync.open("W")
        advanceUntilIdle()
        r.sync.flush()
        assertTrue(r.disk.files.keys.none { it.startsWith("R/") })
    }

    @Test
    fun directProjectSwitchesWithoutACloseDoNotGrowMemoryWithoutBound() = runTest {
        val r = Rig(this)
        (1..20).forEach { i ->
            r.sync.open("P$i") // File > Open: a direct switch, never a close
            advanceUntilIdle()
            r.sync.record("P$i", stroke(i.toLong()), doc)
            advanceUntilIdle()
        }
        assertTrue("states held: ${r.sync.stateCount}", r.sync.stateCount <= 5)
        r.sync.flush()
        // eviction lost nothing: every project's strokes are on disk
        assertEquals(20, r.disk.files.keys.count { it.endsWith("/strokes.json") })
    }

    @Test
    fun aProjectWithOutstandingWorkIsNeverEvicted() = runTest {
        val r = Rig(this)
        r.disk.files["A/strokes.json"] = recording(1).toByteArray()
        r.disk.readDelayMs = 60_000
        r.sync.open("A") // restoring for a long time
        (1..10).forEach { r.sync.open("Q$it"); advanceTimeBy(10) }
        assertTrue(r.sync.stateCount >= 2)
        advanceUntilIdle()
        r.sync.open("A")
        advanceUntilIdle()
        assertEquals(listOf(1L), r.sync.current().map { it.seed })
    }
}
