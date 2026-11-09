package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
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
 * Keeps the palette board in step with the open project. All entry points run on [scope]'s (main) thread; the
 * file reads and writes run on [io].
 *
 *  - **Save** copies the board's state when the change happens (so the bytes written are that moment's board,
 *    never a later one, and only the latest change is kept), debounces, then encodes on [io]; the project id travels
 *    with the copy. Opening or closing a project
 *    cancels a pending write, so project A's board is never written under project B.
 *  - A **read-only** (newer-format) project is never written.
 *  - **Restore** empties the board when a project opens and then loads the saved one only if nothing has been
 *    edited since: an edit made while the file was being read wins over the file.
 */
internal class PaletteProjectSync(
    private val board: PaletteBoard,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val store: Store,
    private val encoder: (PaletteBoard) -> Encoded = ::encodeBoard,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    /** Where the project's files live and whether it may be written. */
    interface Store {
        fun isReadOnly(): Boolean

        /** A save failed (it is also the place to tell the user, once). */
        fun onError(error: Throwable) {}

        /** The saved palette exists but could not be read; the file was kept as a backup. */
        fun onUnreadable(projectId: String) {}
        suspend fun write(projectId: String, file: String, bytes: ByteArray)
        suspend fun read(projectId: String, file: String): ByteArray?
    }

    private var saveJob: Job? = null

    /**
     * Flushes run here, not in [scope]: cancelling [scope] (the ViewModel clearing) must not stop a flush that has
     * been handed off but has not started, and [flush] waits for every one still in flight.
     */
    private val detached = CoroutineScope(SupervisorJob() + io)
    private val inFlight = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private var generation = 0L
    private var revision = 0L

    /** The two parts of the saved palette: the board and the brushes. */
    class Encoded(val board: ByteArray, val brushes: ByteArray)

    /** The latest change not yet written: an owned copy of the board's state, and the project it belongs to. */
    private var pending: PaletteBoard? = null
    private var pendingProject: String? = null

    /**
     * The board or a brush changed in project [projectId]. Cheap on the calling thread: the board's raw state is
     * copied (a few array copies, no allocation after the first change of a burst) into the one pending copy, so
     * only the latest change is kept. Quantising, encoding and compressing happen later on [io].
     */
    fun changed(projectId: String?) {
        revision++
        if (projectId == null || store.isReadOnly()) return
        val copy = pending ?: PaletteBoard(board.size, board.tubes).also { pending = it }
        board.surface.conc.copyInto(copy.surface.conc)
        board.surface.amount.copyInto(copy.surface.amount)
        for (i in board.brushes.indices) {
            board.brushes[i].amount.copyInto(copy.brushes[i].amount)
            board.brushes[i].conc.copyInto(copy.brushes[i].conc)
        }
        copy.hand = board.hand
        pendingProject = projectId
        val mine = generation
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            // still the same project (a closing one is flushed by close()), and still writable
            if (mine != generation || store.isReadOnly()) return@launch
            takePending()?.let { (owned, id) -> write(owned, id) }
        }
    }

    /** The pending copy and its project id, taken (the next change starts a fresh copy). */
    private fun takePending(): Pair<PaletteBoard, String>? {
        val owned = pending
        val id = pendingProject
        pending = null
        pendingProject = null
        return if (owned != null && id != null) owned to id else null
    }

    /**
     * Writes [owned] under [id]: encode and write on [io], and never half-way: the write is not cancellable, so a
     * newer change or a closing project cannot stop it between the encode and the file. The board and both brushes
     * are ONE file, so a crash leaves the old pair or the new pair, never a mix.
     */
    private suspend fun write(owned: PaletteBoard, id: String) {
        withContext(NonCancellable + io) {
            runCatching {
                val e = encoder(owned)
                store.write(id, PaletteFile.COMBINED_FILE, PaletteFile.pack(e.board, e.brushes))
            }.onFailure { if (it is CancellationException) throw it else store.onError(it) }
        }
    }

    /**
     * Writes any pending change now, under the project it belongs to, and waits for it: for the end of the
     * ViewModel's life. The change is written to its own project even if another has been opened since.
     */
    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        takePending()?.let { (owned, id) -> write(owned, id) }
        // flushes already handed off (the app went to the background, a project closed) must finish too
        inFlight.toList().joinAll()
    }

    /** [flush] without waiting (the app going to the background): the write is started and runs to completion. */
    fun flushAsync() {
        saveJob?.cancel()
        saveJob = null
        takePending()?.let { (owned, id) ->
            val job = detached.launch { write(owned, id) }
            inFlight += job
            job.invokeOnCompletion { inFlight -= job }
        }
    }

    /**
     * The open project is going away: its last unsaved palette edit is written under its OWN id (it is never
     * dropped, and never written under the next project), then a pending restore must not land.
     */
    fun close() {
        flushAsync()
        generation++
    }

    /** What was read from the project: the board and brushes bytes, and the file they came from. */
    private class Saved(val board: ByteArray?, val brushes: ByteArray?, val file: String, val raw: ByteArray)

    /** Project [projectId] opened: start from an empty board and load its saved one unless edited meanwhile. */
    fun open(projectId: String) {
        close()
        board.clear()
        val mine = generation
        val startRevision = revision
        scope.launch {
            val verdict = withContext(io) {
                readSaved(projectId)?.let { it to PaletteBoardPersistence.usable(board, it.board, it.brushes) }
            } ?: return@launch
            val (saved, snap) = verdict
            if (snap == null) {
                // The palette exists but cannot be used (damaged, or for a different board or tube set): keep the file
                // as a backup before anything can overwrite it, and tell the user once. This does not depend on an
                // edit having been made meanwhile, or on another project having been opened: the bytes are preserved.
                withContext(NonCancellable + io) { keepBackup(projectId, saved) }
                store.onUnreadable(projectId)
            }
            // Applying the saved state is separate: an edit made meanwhile (or another project) wins over the file.
            if (snap != null && mine == generation && startRevision == revision) {
                PaletteBoardPersistence.applySaved(board, snap, saved.brushes)
            }
        }
    }

    /** The saved palette: the combined file, else the older two files; null if the project has none. */
    private suspend fun readSaved(projectId: String): Saved? {
        val bytes = store.read(projectId, PaletteFile.COMBINED_FILE)
        if (bytes != null) {
            val parts = PaletteFile.unpack(bytes)
            return Saved(parts?.first, parts?.second, PaletteFile.COMBINED_FILE, bytes)
        }
        return store.read(projectId, PaletteBoardPersistence.BOARD_FILE)?.let { legacy ->
            val brushes = store.read(projectId, PaletteBoardPersistence.BRUSH_FILE)
            Saved(legacy, brushes, PaletteBoardPersistence.BOARD_FILE, legacy)
        }
    }

    private suspend fun keepBackup(projectId: String, saved: Saved) {
        runCatching { store.write(projectId, "${saved.file}.bak", saved.raw) }
            .onFailure { if (it is CancellationException) throw it else store.onError(it) }
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L

        fun encodeBoard(b: PaletteBoard) = Encoded(
            MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(b)),
            PaletteBoardPersistence.encodeBrushes(b),
        )
    }
}
