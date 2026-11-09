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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the open project's recorded strokes (`strokes.json`) in step with the project, with the same discipline as
 * [PaletteProjectSync]:
 *  - every change takes an immutable **snapshot** (the strokes, the document size and the substrate as they were
 *    then) tagged with the project's id; the debounced write encodes that snapshot on [io], never later state;
 *  - opening or closing a project bumps a generation: a stale write or restore is dropped, and the closing
 *    project's last edit is **flushed under its own id**;
 *  - writes are **ordered**: one lock around encode and write, and a snapshot older than the last one written for
 *    that project is skipped, so an older save can never be the final file;
 *  - writes are **held while the initial restore is outstanding**, then the loaded strokes are merged in and the
 *    corrected snapshot is what is saved (a snapshot with only the new strokes never replaces the file);
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
    private val decoder: (String) -> StrokeRecording.Companion.Repaired? = StrokeRecording::fromJsonRepairing,
) {
    interface Store {
        fun isReadOnly(): Boolean
        fun onError(error: Throwable) {}

        /**
         * The saved strokes could not be read whole; [dropped] strokes were lost (-1: all). [backedUp] says whether the
         * original file was kept as a backup; when false, saving is refused for that project until it is reopened.
         */
        fun onUnreadable(projectId: String, dropped: Int, backedUp: Boolean) {}
        suspend fun write(projectId: String, file: String, bytes: ByteArray)
        suspend fun read(projectId: String, file: String): ByteArray?
    }

    class Limits(val maxStrokes: Int = 2_000, val maxSamples: Int = 300_000)

    /** The document the strokes were made on, captured with each change. */
    class Doc(val width: Int, val height: Int, val substrateId: String)

    private class Snapshot(val projectId: String, val strokes: List<StampStrokeRecord>, val doc: Doc, val seq: Long)

    /** The initial read of one project, and the newest snapshot made while it was outstanding (held, not written). */
    private class Restore(val projectId: String) {
        var held: Snapshot? = null
    }

    private val strokes = ArrayList<StampStrokeRecord>()
    private var pending: Snapshot? = null
    private var restore: Restore? = null
    private var restoreJob: Job? = null
    private var saveJob: Job? = null
    private var nextSeq = 0L

    /** Held across encode and write, with [lastWritten]: writes happen one at a time, newest-wins. */
    private val writeLock = Mutex()
    private val lastWritten = HashMap<String, Long>()
    private val unpreserved: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        StrokeRecordFiles.trim(strokes, limits)
        if (projectId == null || store.isReadOnly()) return
        val snapshot = Snapshot(projectId, strokes.toList(), doc, ++nextSeq)
        val restoring = restore?.takeIf { it.projectId == projectId }
        if (restoring != null) {
            restoring.held = snapshot // not written until the saved strokes are merged in
            return
        }
        pending = snapshot
        scheduleSave()
    }

    private fun takePending(): Snapshot? = pending.also { pending = null }

    private fun scheduleSave() {
        val mine = generation
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            if (mine != generation || store.isReadOnly()) return@launch
            takePending()?.let { write(it) }
        }
    }

    /** Encode and write [s], in order: never after a newer snapshot of the project, never over an unsaved original. */
    private suspend fun write(s: Snapshot) {
        withContext(NonCancellable + io) {
            writeLock.withLock {
                if (s.projectId in unpreserved || s.seq < (lastWritten[s.projectId] ?: 0L)) return@withLock
                runCatching {
                    val recording = StrokeRecording(
                        canvasWidth = s.doc.width.coerceAtLeast(1),
                        canvasHeight = s.doc.height.coerceAtLeast(1),
                        substrateId = s.doc.substrateId,
                        strokes = s.strokes,
                    )
                    store.write(s.projectId, FILE, recording.toJson().toByteArray())
                    lastWritten[s.projectId] = s.seq
                }.onFailure { if (it is CancellationException) throw it else store.onError(it) }
            }
        }
    }

    /** Writes any pending change now (the ViewModel ending), under its own project, after any write in flight. */
    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        inFlight.toList().joinAll()
        takePending()?.let { write(it) }
        restoreJob?.join() // a restore still reading finishes first; its corrected snapshot is written below
        takePending()?.let { write(it) }
    }

    /** [flush] without waiting (the app going to the background). Nothing is written while a restore is out. */
    fun flushAsync() {
        saveJob?.cancel()
        saveJob = null
        takePending()?.let { s -> launchWrite(s) }
    }

    private fun launchWrite(s: Snapshot) {
        val job = detached.launch { write(s) }
        inFlight += job
        job.invokeOnCompletion { inFlight -= job }
    }

    /** The open project is going away: its last edit is saved under its own id, and a pending restore must not land. */
    fun close() {
        flushAsync()
        restore = null
        generation++
    }

    /** Project [projectId] opened: start empty and load its strokes, keeping any edit made meanwhile. */
    fun open(projectId: String) {
        close()
        strokes.clear()
        unpreserved -= projectId // reopening retries the backup
        val mine = generation
        val startRevision = revision
        val r = Restore(projectId).also { restore = it }
        restoreJob = scope.launch {
            // read, decode and repair off the main thread; keep the original if anything was lost
            val loaded = withContext(io) { StrokeRecordFiles.load(store, projectId, decoder) }
            val repaired = loaded?.repaired
            if (loaded != null && loaded.needsBackup) {
                val kept = withContext(NonCancellable + io) {
                    StrokeRecordFiles.keepOriginal(store, projectId, loaded.bytes)
                }
                // if it could not be kept, this project's strokes are not saved over the original
                if (!kept) unpreserved += projectId
                store.onUnreadable(projectId, repaired?.dropped ?: -1, kept)
            }
            val base = repaired?.recording?.strokes.orEmpty()
            val held = r.held
            if (mine == generation) {
                // an edit made while the file was being read wins: it stays, with what was loaded before it
                val merged = ArrayList(base).also { it += strokes }
                StrokeRecordFiles.trim(merged, limits)
                strokes.clear()
                strokes += merged
                if (startRevision != revision) revision++
                restore = null
                if (held != null) {
                    // the held snapshot lacked the loaded strokes: what is saved is the corrected one
                    pending = Snapshot(projectId, strokes.toList(), held.doc, ++nextSeq)
                    scheduleSave()
                }
            } else if (held != null) {
                // the project was closed before the read finished: its last edit still goes to its own file, merged
                val merged = ArrayList(base).also { it += held.strokes }
                StrokeRecordFiles.trim(merged, limits)
                launchWrite(Snapshot(projectId, merged, held.doc, ++nextSeq))
            }
        }
    }

    companion object {
        const val FILE = "strokes.json"
        private const val DEBOUNCE_MS = 2_000L
    }
}
