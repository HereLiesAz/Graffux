package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Owns the undo/redo stacks for the editor. Pure logic — no Android, Compose, or OpenCV
 * dependencies — so it is fully unit-testable in isolation (unlike most of EditorViewModel,
 * whose tests need native OpenCV). Extracted from EditorViewModel to shrink that god-class
 * and give the history mechanics a single, testable home.
 *
 * The *application* of a command (rebuilding bitmaps, restoring layer props) stays in the
 * ViewModel; this class only manages the stacks. When popping, the caller supplies the
 * counterpart entry to record on the opposite stack via [popUndo]/[popRedo], because that
 * entry depends on the ViewModel's current state.
 */
internal class EditHistory(private val maxStackSize: Int = 20) {
    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /**
     * Records a layer-property snapshot. Deduplicated: a snapshot identical to the most recent
     * one is ignored, and no new entry is added (returns false) — but the redo stack is still
     * cleared, because the caller (pushHistory) is about to apply a new edit either way. Without
     * this, a redo queued between two calls that happen to dedup survives past an edit that never
     * cleared it — undo, edit something whose "before" snapshot happens to match, then Redo re-
     * applies the stale entry on top of the newer edit instead of doing nothing.
     */
    fun pushProperty(layersWithoutBitmaps: List<Layer>): Boolean {
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (last is EditCommand.PropertyChange && last.oldLayers == layersWithoutBitmaps) return false
        undoStack.addLast(EditCommand.PropertyChange(layersWithoutBitmaps))
        trim()
        return true
    }

    /** Records a completed brush stroke. Pushing clears the redo stack. */
    fun pushDraw(layerId: String, command: StrokeCommand) {
        undoStack.addLast(EditCommand.Draw(layerId, command))
        trim()
        redoStack.clear()
    }

    /**
     * Pops the most recent undoable command, recording [counterEntry] of it on the redo stack.
     * Returns null (and records nothing) when there is nothing to undo.
     */
    fun popUndo(counterEntry: (EditCommand) -> EditCommand): EditCommand? {
        val command = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(counterEntry(command))
        return command
    }

    /** Symmetric to [popUndo]: pops the most recent redoable command onto the undo stack. */
    fun popRedo(counterEntry: (EditCommand) -> EditCommand): EditCommand? {
        val command = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(counterEntry(command))
        return command
    }

    /**
     * Discards the counter-entry [popUndo] just speculatively pushed onto the redo stack, for a
     * caller that finds — only after popping — that the command it undid can't actually be applied
     * (e.g. the layer it targets has no cached pixels to restore). Leaves the undo stack untouched:
     * the command is dropped outright rather than kept redoable, since there is nothing left to
     * redo it onto.
     */
    fun dropTopRedo(): EditCommand? = redoStack.removeLastOrNull()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun referencedLayerIds(): Set<String> {
        val set = mutableSetOf<String>()
        (undoStack + redoStack).forEach { cmd ->
            when (cmd) {
                is EditCommand.Draw -> set.add(cmd.layerId)
                is EditCommand.PropertyChange -> cmd.oldLayers.forEach { set.add(it.id) }
            }
        }
        return set
    }

    private fun trim() {
        if (undoStack.size > maxStackSize) undoStack.removeFirst()
    }
}
