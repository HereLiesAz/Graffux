package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.StampStrokeRecord
import com.hereliesaz.graffitixr.common.azphalt.StrokeRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the recorded strokes (`strokes.json`) of every project opened in this process in step with its file.
 *
 * **One owner per project id.** Each project has ONE state ([PState]) for the process, however many times it is
 * closed and reopened: its restore (the initial read), its strokes, its pending snapshot and its writes all belong to
 * it. Everything runs in a scope this object owns (`SupervisorJob + io`), never the ViewModel's, so the ViewModel
 * being cleared cannot cancel a restore or a flush half way.
 *
 * States of a project, and what moves between them:
 *  - *restoring* (not loaded): the saved file is being read. Strokes recorded now are **held** in the state and
 *    nothing is written. Reopening the project (a switch away and back) joins the SAME restore, never starts another.
 *    When the read finishes: the saved strokes are merged in front of the held ones, the project becomes *loaded*, and
 *    if anything was held a corrected snapshot is scheduled. A read that fails leaves it unloaded and unwritten.
 *  - *loaded*: strokes are appended to the state's list, each change takes an immutable snapshot (strokes, document
 *    size, substrate) with an increasing sequence number, and a debounced write saves the latest one.
 *  - *closed* (another project is open): its pending snapshot is flushed at once under its own id; the state is kept
 *    (a few most recent) so a quick reopen sees its strokes without waiting for the file.
 *  - *flushing* (`flushAsync` / `flush`): writes pending snapshots; `flush` first waits for every restore still out.
 * Writes are ordered: one lock around encode and write, and a snapshot older than the last one written for its
 * project is skipped. If the original file could not be backed up, the project is *unpreserved*: nothing is written
 * over it until it is reopened.
 */
internal class StrokeRecordSync(
    private val io: CoroutineDispatcher,
    private val store: Store,
    private val limits: Limits = Limits(),
    private val debounceMs: Long = DEBOUNCE_MS,
    private val decoder: (String) -> StrokeRecording.Companion.Repaired? = StrokeRecording::fromJsonRepairing,
) {
    interface Store {
        fun isReadOnly(projectId: String): Boolean
        fun onError(error: Throwable) {}

        /**
         * The saved strokes could not be read whole; [dropped] strokes were lost (-1: all). [backedUp] says whether the
         * original file was kept as a backup; when false, saving is refused for that project until it is reopened.
         */
        fun onUnreadable(projectId: String, dropped: Int, backedUp: Boolean) {}
        suspend fun write(projectId: String, file: String, bytes: ByteArray)
        suspend fun read(projectId: String, file: String): ArtifactRead
    }

    class Limits(val maxStrokes: Int = 2_000, val maxSamples: Int = 300_000)

    /** The document the strokes were made on, captured with each change. */
    class Doc(val width: Int, val height: Int, val substrateId: String)

    private class Snapshot(val projectId: String, val strokes: List<StampStrokeRecord>, val doc: Doc, val seq: Long)

    /** Everything about one project in this process (see the class comment). Guarded by [lock]. */
    private class PState(val projectId: String) {
        var loaded = false
        val strokes = ArrayList<StampStrokeRecord>()
        var doc: Doc? = null
        var edited = false
        var pending: Snapshot? = null
        var saveJob: Job? = null
        var restoreJob: Job? = null
        var writes = 0
        var readOnly = false

        fun takePending(): Snapshot? = pending.also { pending = null }
    }

    private val lock = Any()
    private val states = LinkedHashMap<String, PState>()
    private val anonymous = ArrayList<StampStrokeRecord>()
    private var current: String? = null
    private val detached = CoroutineScope(SupervisorJob() + io)

    /** The open project's strokes now (read-only copy; the held ones while its restore is out). */
    fun current(): List<StampStrokeRecord> = synchronized(lock) {
        current?.let { states[it]?.strokes?.toList() } ?: anonymous.toList()
    }

    /** [record] was made in [projectId] on [doc]. Bounded: the oldest strokes drop first. */
    fun record(projectId: String?, record: StampStrokeRecord, doc: Doc) {
        synchronized(lock) {
            if (projectId == null) {
                anonymous += record
                StrokeRecordFiles.trim(anonymous, limits)
                return
            }
            val s = states.getOrPut(projectId) { PState(projectId).also { it.loaded = true } }
            s.strokes += record
            StrokeRecordFiles.trim(s.strokes, limits)
            s.doc = doc
            s.edited = true
            // writability is a fact about THIS project, taken while it is the open one
            if (store.isReadOnly(projectId)) s.readOnly = true
            if (s.loaded) snapshotAndSchedule(s)
        }
    }

    /** Takes the snapshot of a loaded project's strokes and schedules its debounced write. Call under [lock]. */
    private fun snapshotAndSchedule(s: PState) {
        val doc = s.doc ?: return
        if (s.readOnly) return
        s.pending = Snapshot(s.projectId, s.strokes.toList(), doc, StrokeWriteQueue.nextSeq())
        s.saveJob?.cancel()
        s.saveJob = detached.launch {
            delay(debounceMs)
            if (s.readOnly) return@launch
            synchronized(lock) { s.takePending() }?.let { write(s, it) }
        }
    }

    /** Encode and write [snap], in order: never after a newer snapshot, never over an unsaved original. */
    private suspend fun write(
        s: PState,
        snap: Snapshot,
        mark: CompletableDeferred<Unit> = StrokeWriteQueue.begin(snap.projectId),
    ) {
        synchronized(lock) { s.writes++ }
        try {
            withContext(NonCancellable + io) {
                StrokeWriteQueue.lock.withLock {
                    val stale = snap.seq < (StrokeWriteQueue.lastWritten[snap.projectId] ?: 0L)
                    if (snap.projectId in StrokeWriteQueue.unpreserved || stale) return@withLock
                    runCatching {
                        val recording = StrokeRecording(
                            canvasWidth = snap.doc.width.coerceAtLeast(1),
                            canvasHeight = snap.doc.height.coerceAtLeast(1),
                            substrateId = snap.doc.substrateId,
                            strokes = snap.strokes,
                        )
                        store.write(snap.projectId, FILE, recording.toJson().toByteArray())
                        StrokeWriteQueue.lastWritten[snap.projectId] = snap.seq
                    }.onFailure { if (it is CancellationException) throw it else store.onError(it) }
                }
            }
        } finally {
            StrokeWriteQueue.finish(snap.projectId, mark)
            synchronized(lock) {
                s.writes--
                StrokeRecordFiles.evictIdle(states, isIdle, current, KEEP_CLOSED)
            }
        }
    }

    /** A project with no work outstanding, which may be dropped from memory (its file is the truth). */
    private val isIdle: (String) -> Boolean = { id ->
        states[id]?.let { it.loaded && it.pending == null && it.writes == 0 && it.restoreJob == null } == true
    }

    /** How many projects' states are held in memory (for tests: eviction must bound it). */
    val stateCount: Int get() = synchronized(lock) { states.size }

    /** Writes every pending change now and waits: for the end of the ViewModel's life. Waits for restores first. */
    suspend fun flush() {
        val restores = synchronized(lock) { states.values.mapNotNull { it.restoreJob } }
        restores.joinAll() // each finishes by scheduling its corrected snapshot, written just below
        val work = synchronized(lock) {
            states.values.mapNotNull { s ->
                s.saveJob?.cancel()
                s.takePending()?.let { s to it }
            }
        }
        work.forEach { (s, snap) -> write(s, snap) }
        StrokeWriteQueue.awaitAll() // every write in flight, whoever started it
    }

    /** [flush] without waiting (the app going to the background). Nothing is written for a project still restoring. */
    fun flushAsync() {
        synchronized(lock) { states.values.forEach { flushLocked(it) } }
    }

    private fun flushLocked(s: PState) {
        s.saveJob?.cancel()
        val snap = synchronized(lock) { s.takePending() } ?: return
        s.writes++ // counted from now, so the state cannot be dropped before the write has even started
        val mark = StrokeWriteQueue.begin(s.projectId) // registered now, so a final flush cannot miss it
        detached.launch { write(s, snap, mark) }.invokeOnCompletion { synchronized(lock) { s.writes-- } }
    }

    /** The open project is going away: its last edit is saved under its own id. Its state stays for a quick reopen. */
    fun close() {
        synchronized(lock) {
            current?.let { states[it]?.let(::flushLocked) }
            current = null
            StrokeRecordFiles.evictIdle(states, isIdle, current, KEEP_CLOSED)
        }
    }

    /** Project [projectId] opened: its state's restore is started once, or joined if one is already out. */
    fun open(projectId: String) {
        synchronized(lock) {
            current?.let { states[it]?.let(::flushLocked) }
            if (StrokeWriteQueue.unpreserved.remove(projectId)) states.remove(projectId) // reopening retries the backup
            val s = states.remove(projectId) ?: PState(projectId)
            states[projectId] = s // most recently opened goes last
            current = projectId
            // a project switched to directly (no close in between) must not leave the old one in memory forever
            StrokeRecordFiles.evictIdle(states, isIdle, current, KEEP_CLOSED)
            if (!s.loaded && s.restoreJob == null) s.restoreJob = detached.launch { restore(s) }
            anonymous.clear()
        }
    }

    /** The initial read of [s]'s file: decode and repair, keep the original if anything was lost, then merge. */
    private suspend fun restore(s: PState) {
        val id = s.projectId
        StrokeWriteQueue.awaitWrites(id) // another instance's write of this file may still be landing
        val loaded = runCatching { StrokeRecordFiles.load(store, id, decoder) }
            .onFailure { if (it is CancellationException) throw it else store.onError(it) }
        if (loaded.isFailure) {
            // Could not even read: leave it unloaded and unwritten, so a reopen tries again.
            synchronized(lock) { s.restoreJob = null }
            return
        }
        val read = loaded.getOrNull()
        val repaired = read?.repaired
        if (read?.unreadable != null) {
            // present but unreadable: no bytes to keep, so nothing may be written over it until the project is reopened
            StrokeWriteQueue.unpreserved += id
            store.onUnreadable(id, -1, false)
        } else if (read != null && read.needsBackup) {
            val kept = withContext(NonCancellable) { StrokeRecordFiles.keepOriginal(store, id, read.bytes) }
            if (!kept) StrokeWriteQueue.unpreserved += id
            store.onUnreadable(id, repaired?.dropped ?: -1, kept)
        }
        synchronized(lock) {
            // the saved strokes go in front of whatever was recorded while the read was out
            val merged = ArrayList(repaired?.recording?.strokes.orEmpty()).also { it += s.strokes }
            StrokeRecordFiles.trim(merged, limits)
            s.strokes.clear()
            s.strokes += merged
            s.loaded = true
            s.restoreJob = null
            if (s.edited) snapshotAndSchedule(s) // the corrected snapshot, never the held-only one
            StrokeRecordFiles.evictIdle(states, isIdle, current, KEEP_CLOSED)
        }
    }

    companion object {
        const val FILE = "strokes.json"
        private const val DEBOUNCE_MS = 2_000L
        private const val KEEP_CLOSED = 3
    }
}
