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
 *  - **Save** snapshots the board when the change happens (so the bytes written are that moment's board, never a
 *    later one), debounces the write, and carries the project id with the snapshot. Opening or closing a project
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

    /** The board or a brush changed in project [projectId]. */
    fun changed(projectId: String?) {
        revision++
        if (projectId == null || store.isReadOnly()) return
        val boardBytes = MaterialStateCodec.encode(PaletteBoardPersistence.snapshot(board))
        val brushBytes = PaletteBoardPersistence.encodeBrushes(board)
        val mine = generation
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(debounceMs)
            if (mine != generation || store.isReadOnly()) return@launch
            withContext(io) {
                runCatching {
                    store.write(projectId, PaletteBoardPersistence.BOARD_FILE, boardBytes)
                    store.write(projectId, PaletteBoardPersistence.BRUSH_FILE, brushBytes)
                }.onFailure(onError)
            }
        }
    }

    /** The open project is going away: a pending save and a pending restore must not land. */
    fun close() {
        generation++
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
    }
}
