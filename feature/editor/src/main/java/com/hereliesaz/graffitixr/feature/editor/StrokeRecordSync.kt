package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.StampStrokeRecord
import com.hereliesaz.graffitixr.common.azphalt.StrokeRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the open project's recorded strokes (`strokes.json`) in step with the project, with the same discipline as
 * [PaletteProjectSync]:
 *  - every change takes an immutable **snapshot** (the strokes, the document size and the substrate as they were
 *    then) tagged with the project's id; the debounced write encodes that snapshot on [io], never later state;
 *  - opening or closing a project bumps a generation: a stale write or restore is dropped, and the closing
 *    project's last edit is **flushed under its own id**;
 *  - a restore that finds an edit already made keeps the edit and puts the loaded strokes before it;
 *  - a file that cannot be read whole is read **repairing** (only bad strokes dropped); if anything was lost or the
 *    file is unusable, the original bytes are kept as `strokes.json.bak` before a later save can overwrite them.
 */
internal class StrokeRecordSync(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val store: Store,
    private val limits: Limits = Limits(),
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    interface Store {
        fun isReadOnly(): Boolean
        fun onError(error: Throwable) {}

        /** The saved strokes could not be read whole; [dropped] strokes were lost (-1: all). The file was kept. */
        fun onUnreadable(projectId: String, dropped: Int) {}
        suspend fun write(projectId: String, file: String, bytes: ByteArray)
        suspend fun read(projectId: String, file: String): ByteArray?
    }

    class Limits(val maxStrokes: Int = 2_000, val maxSamples: Int = 300_000)

    /** The document the strokes were made on, captured with each change. */
    class Doc(val width: Int, val height: Int, val substrateId: String)

    private class Snapshot(val projectId: String, val strokes: List<StampStrokeRecord>, val doc: Doc)

    private val strokes = ArrayList<StampStrokeRecord>()
    private var pending: Snapshot? = null
    private var saveJob: Job? = null

    /** Flushes run in a scope of their own, so the ViewModel clearing cannot stop one that has not started. */
    private val detached = CoroutineScope(SupervisorJob() + io)
    private val inFlight = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private var generation = 0L
    private var revision = 0L

    /** The strokes held now (read-only copy). */
    fun current(): List<StampStrokeRecord> = strokes.toList()

    /** [record] was made in [projectId] on [doc]. Bounded: the oldest strokes drop first. */
    fun record(projectId: String?, record: StampStrokeRecord, doc: Doc) {
        revision++
        strokes += record
        trim(strokes)
        if (projectId == null || store.isReadOnly()) return
        pending = Snapshot(projectId, strokes.toList(), doc)
        val mine = generation
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            if (mine != generation || store.isReadOnly()) return@launch
            take()?.let { write(it) }
        }
    }

    private fun trim(list: MutableList<StampStrokeRecord>) {
        var samples = list.sumOf { it.samples.size }
        while (list.size > limits.maxStrokes || samples > limits.maxSamples) samples -= list.removeAt(0).samples.size
    }

    private fun take(): Snapshot? = pending.also { pending = null }

    private suspend fun write(s: Snapshot) {
        withContext(NonCancellable + io) {
            runCatching {
                val recording = StrokeRecording(
                    canvasWidth = s.doc.width.coerceAtLeast(1),
                    canvasHeight = s.doc.height.coerceAtLeast(1),
                    substrateId = s.doc.substrateId,
                    strokes = s.strokes,
                )
                store.write(s.projectId, FILE, recording.toJson().toByteArray())
            }.onFailure { if (it is CancellationException) throw it else store.onError(it) }
        }
    }

    /** Writes any pending change now (the ViewModel ending), under its own project. */
    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        take()?.let { write(it) }
        inFlight.toList().joinAll()
    }

    /** [flush] without waiting (the app going to the background). */
    fun flushAsync() {
        saveJob?.cancel()
        saveJob = null
        take()?.let { s ->
            val job = detached.launch { write(s) }
            inFlight += job
            job.invokeOnCompletion { inFlight -= job }
        }
    }

    /** The open project is going away: its last edit is saved under its own id, and a pending restore must not land. */
    fun close() {
        flushAsync()
        generation++
    }

    /** Project [projectId] opened: start empty and load its strokes, keeping any edit made meanwhile. */
    fun open(projectId: String) {
        close()
        strokes.clear()
        val mine = generation
        val startRevision = revision
        scope.launch {
            val bytes = withContext(io) { store.read(projectId, FILE) } ?: return@launch
            val repaired = runCatching { StrokeRecording.fromJsonRepairing(String(bytes)) }.getOrNull()
            if (repaired == null || repaired.dropped > 0) {
                // Anything lost (or the whole file): keep the original bytes before a save can overwrite them,
                // whatever has been edited or opened since, and tell the user.
                withContext(NonCancellable + io) {
                    runCatching { store.write(projectId, "$FILE.bak", bytes) }
                        .onFailure { if (it is CancellationException) throw it else store.onError(it) }
                }
                store.onUnreadable(projectId, repaired?.dropped ?: -1)
            }
            if (repaired == null || mine != generation) return@launch
            // an edit made while the file was being read wins: it stays, with what was loaded before it
            val merged = ArrayList(repaired.recording.strokes).also { it += strokes }
            trim(merged)
            strokes.clear()
            strokes += merged
            // the save already waiting was taken before the loaded strokes were put back: take it again, or it would
            // overwrite the file with only the new strokes
            pending?.takeIf { it.projectId == projectId }?.let {
                pending = Snapshot(projectId, strokes.toList(), it.doc)
            }
            if (startRevision != revision) revision++
        }
    }

    companion object {
        const val FILE = "strokes.json"
        private const val DEBOUNCE_MS = 2_000L
    }
}
