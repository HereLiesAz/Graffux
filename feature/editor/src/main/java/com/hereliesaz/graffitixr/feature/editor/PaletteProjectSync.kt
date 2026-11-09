package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val onError: (Throwable) -> Unit = {},
    private val encoder: (PaletteBoard) -> Encoded = ::encodeBoard,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    /** Where the project's files live and whether it may be written. */
    interface Store {
        fun isReadOnly(): Boolean
        suspend fun write(projectId: String, file: String, bytes: ByteArray)
        suspend fun read(projectId: String, file: String): ByteArray?
    }

    private var saveJob: Job? = null
    private var generation = 0L
    private var revision = 0L

    /** The two files' bytes. */
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
            if (mine != generation || store.isReadOnly()) return@launch
            // hand the copy to the IO work; the next change starts a fresh one
            val owned = pending ?: return@launch
            val id = pendingProject ?: return@launch
            pending = null
            pendingProject = null
            withContext(io) {
                runCatching {
                    val bytes = encoder(owned)
                    store.write(id, PaletteBoardPersistence.BOARD_FILE, bytes.board)
                    store.write(id, PaletteBoardPersistence.BRUSH_FILE, bytes.brushes)
                }.onFailure(onError)
            }
        }
    }

    /** The open project is going away: a pending save and a pending restore must not land. */
    fun close() {
        generation++
        pending = null
        pendingProject = null
        saveJob?.cancel()
        saveJob = null
    }

    /** Project [projectId] opened: start from an empty board and load its saved one unless edited meanwhile. */
    fun open(projectId: String) {
        close()
        board.clear()
        val mine = generation
        val startRevision = revision
        scope.launch {
            val saved = withContext(io) {
                val b = store.read(projectId, PaletteBoardPersistence.BOARD_FILE)
                    ?.let { runCatching { MaterialStateCodec.decode(it) }.getOrNull() }
                b to store.read(projectId, PaletteBoardPersistence.BRUSH_FILE)
            }
            if (mine != generation || startRevision != revision) return@launch
            val (snap, brushes) = saved
            if (snap != null && !PaletteBoardPersistence.restore(board, snap)) board.clear()
            if (brushes != null) PaletteBoardPersistence.decodeBrushes(board, brushes)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L

        fun encodeBoard(b: PaletteBoard) = Encoded(
            MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(b)),
            PaletteBoardPersistence.encodeBrushes(b),
        )
    }
}
