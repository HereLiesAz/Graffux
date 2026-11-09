package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.StampStrokeRecord
import com.hereliesaz.graffitixr.common.azphalt.StrokeRecording
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
    }

    private class Rig(scope: TestScope, vmScope: CoroutineScope? = null, val readOnly: () -> Boolean = { false }) {
        val disk = Disk()
        val sync = StrokeRecordSync(
            scope = vmScope ?: scope,
            io = StandardTestDispatcher(scope.testScheduler),
            store = object : StrokeRecordSync.Store {
                override fun isReadOnly() = readOnly()
                override fun onUnreadable(projectId: String, dropped: Int) { disk.unreadable += projectId to dropped }
                override suspend fun write(projectId: String, file: String, bytes: ByteArray) {
                    disk.files["$projectId/$file"] = bytes
                    disk.writes += "$projectId/$file"
                }
                override suspend fun read(projectId: String, file: String) = disk.files["$projectId/$file"]
            },
            limits = StrokeRecordSync.Limits(maxStrokes = 5, maxSamples = 1_000),
        )
    }

    private fun stroke(seed: Long) = StampStrokeRecord(
        brush = AzphaltBrush(name = "t"), seed = seed, diameterPx = 10f, colorArgb = 0,
        samples = BrushSampleBuilder().let { b -> (0..3).map { b.add(it * 3f, 4f, it * 8L, 0.5f) } },
    )

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
    fun flushHandedOffThenScopeCancelledIsStillWritten() = runTest {
        val vm = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val r = Rig(this, vm)
        r.sync.record("A", stroke(1), doc)
        r.sync.flushAsync()
        vm.cancel()
        r.sync.flush()
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
}
