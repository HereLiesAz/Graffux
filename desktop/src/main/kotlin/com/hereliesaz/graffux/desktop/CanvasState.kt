package com.hereliesaz.graffux.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Hoisted canvas state, shared between [DesktopStampCanvas] (which paints into it) and the rail
 * (which offers Undo) -- a real, if minimal, history stack, not the single always-live bitmap this
 * app started with. Each entry is a full canvas snapshot; simple and correct for a first pass, at
 * the cost of memory for a long undo chain (no tile-diff storage the way Android's `EditHistory`
 * eventually might use -- see DESKTOP.md).
 */
class CanvasState {
    var committed by mutableStateOf<BufferedImage?>(null)
        private set

    private val undoStack = mutableStateListOf<BufferedImage>()
    private val redoStack = mutableStateListOf<BufferedImage>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Called once per finished stroke (see [DesktopStampCanvas]'s `onEnd`). A newly committed
     *  stroke invalidates any redo history -- same convention every undo/redo stack uses: redo
     *  only replays what undo itself just took back, not an alternate future after a fresh edit. */
    fun commitStroke(frame: BufferedImage) {
        committed?.let { undoStack.add(it) }
        committed = frame
        redoStack.clear()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        committed?.let { redoStack.add(it) }
        committed = previous
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        committed?.let { undoStack.add(it) }
        committed = next
    }

    /** Called only when the canvas is resized (no prior content to preserve past its own bounds) --
     *  never as part of undo/redo, so it deliberately does NOT touch [undoStack]. */
    fun replaceWithoutHistory(frame: BufferedImage) {
        committed = frame
    }

    /** Wipes the canvas back to blank, same size as the current one -- pushes the current content
     *  onto the undo stack first, so "Clear" is itself undoable like any other edit. */
    fun clear() {
        val current = committed ?: return
        undoStack.add(current)
        redoStack.clear()
        committed = BufferedImage(current.width, current.height, BufferedImage.TYPE_INT_ARGB)
    }

    /** Exports the current canvas as a timestamped PNG under [directory] (created if missing) and
     *  returns the file written, or `null` if there's nothing to export yet. A first pass -- no
     *  native save/save-as file-chooser dialog (Compose Desktop has none built in, and a blocking
     *  AWT `FileDialog` is a real thing to wire up on its own -- see DESKTOP.md), just a genuine
     *  file landing on disk, not a placeholder. */
    fun exportPng(directory: File = File(System.getProperty("user.home"), "Graffux")): File? {
        val frame = committed ?: return null
        directory.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(directory, "graffux-$timestamp.png")
        ImageIO.write(frame, "png", file)
        return file
    }
}
