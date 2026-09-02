package com.hereliesaz.graffux.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.image.BufferedImage

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

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Called once per finished stroke (see [DesktopStampCanvas]'s `onEnd`). */
    fun commitStroke(frame: BufferedImage) {
        committed?.let { undoStack.add(it) }
        committed = frame
    }

    fun undo() {
        committed = undoStack.removeLastOrNull() ?: return
    }

    /** Called only when the canvas is resized (no prior content to preserve past its own bounds) --
     *  never as part of undo/redo, so it deliberately does NOT touch [undoStack]. */
    fun replaceWithoutHistory(frame: BufferedImage) {
        committed = frame
    }
}
