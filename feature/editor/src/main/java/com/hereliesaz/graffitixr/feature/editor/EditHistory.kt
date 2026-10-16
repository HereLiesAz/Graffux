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
internal class EditHistory(
    private val maxStackSize: Int = 20,
    /** Called with every GPU undo snapshot an entry leaving both stacks held, to free it. */
    private val onGpuUndoDiscarded: (com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots.Ref) -> Unit = {},
) {
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
        clearRedo()
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
        clearRedo()
    }

    /**
     * Attaches a tile-delta fast path (roadmap item 16) to the [EditCommand.Draw] entry matching
     * [command] by object identity — never by [StrokeCommand] equality, since two independently
     * drawn strokes could otherwise compare equal. Searches both stacks: the entry may have moved
     * to the redo stack (a fast undo before this attachment runs) or moved back again by the time
     * a slow attachment finally happens, and either still benefits from the fast path. A no-op if
     * the entry isn't found on either stack — e.g. it was trimmed by [trim] or is a
     * [EditCommand.PropertyChange] — which correctly leaves that stroke on the slow (full replay)
     * path rather than attaching stale/misdirected data.
     */
    fun attachTileDeltas(
        command: StrokeCommand,
        tileDeltas: List<com.hereliesaz.graffitixr.common.azphalt.TileDelta.TileSnapshot>,
        canvasWidth: Int,
        canvasHeight: Int,
        gpuUndo: com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots.Ref? = null,
    ) {
        fun attachIn(stack: ArrayDeque<EditCommand>): Boolean {
            val idx = stack.indexOfLast { it is EditCommand.Draw && it.command === command }
            if (idx < 0) return false
            val entry = stack[idx] as EditCommand.Draw
            // The entry owns exactly one snapshot: one it already held is freed when replaced
            // (a second, slower attach), never just dropped.
            entry.gpuUndo?.takeIf { it != gpuUndo }?.let(onGpuUndoDiscarded)
            stack[idx] = entry.copy(
                tileDeltas = tileDeltas,
                tileDeltaCanvasWidth = canvasWidth,
                tileDeltaCanvasHeight = canvasHeight,
                gpuUndo = gpuUndo,
            )
            return true
        }
        if (!attachIn(undoStack) && !attachIn(redoStack)) gpuUndo?.let(onGpuUndoDiscarded)
    }

    /**
     * Detaches [ref] from whichever entry holds it and frees it: its GPU copy fell out of step
     * with the layer (a swap was refused, or the layer was rebuilt another way), so it must never
     * be swapped again. Frees it only if an entry still held it: one already trimmed or cleared was
     * freed then, and freeing it twice would release a native snapshot handle twice.
     */
    fun forgetGpuUndo(ref: com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots.Ref) {
        var held = false
        for (stack in listOf(undoStack, redoStack)) {
            val idx = stack.indexOfFirst { it is EditCommand.Draw && it.gpuUndo == ref }
            if (idx >= 0) {
                stack[idx] = (stack[idx] as EditCommand.Draw).copy(gpuUndo = null)
                held = true
            }
        }
        if (held) onGpuUndoDiscarded(ref)
    }

    /**
     * Removes the [EditCommand.Draw] entry for [command] (by identity) from whichever stack holds
     * it, freeing its GPU snapshot: the stroke never landed on the layer (its commit failed), so
     * neither undo nor redo may offer it. Returns whether an entry was removed.
     */
    fun dropDraw(command: StrokeCommand): Boolean {
        for (stack in listOf(undoStack, redoStack)) {
            val idx = stack.indexOfLast { it is EditCommand.Draw && it.command === command }
            if (idx >= 0) {
                stack.removeAt(idx).gpuUndo()?.let(onGpuUndoDiscarded)
                return true
            }
        }
        return false
    }

    /**
     * The ViewModel reattaches current bitmaps to property-history snapshots during undo/redo.
     * Any stored origin therefore needs to follow the bitmap when its storage is grown.
     * Legacy zero frame dimensions must become explicit before the larger bitmap is attached.
     */
    fun migrateLayerGrowth(layerId: String, frameWidth: Int, frameHeight: Int, originX: Int, originY: Int) {
        fun reframe(command: EditCommand): EditCommand {
            if (command !is EditCommand.PropertyChange) return command
            return command.copy(oldLayers = command.oldLayers.map { layer ->
                if (layer.id != layerId) layer else layer.copy(
                    frameWidth = layer.frameWidth.takeIf { it > 0 } ?: frameWidth,
                    frameHeight = layer.frameHeight.takeIf { it > 0 } ?: frameHeight,
                    originX = originX,
                    originY = originY,
                )
            })
        }
        for (stack in listOf(undoStack, redoStack)) {
            for (index in stack.indices) stack[index] = reframe(stack[index])
        }
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
    fun dropTopRedo(): EditCommand? = redoStack.removeLastOrNull()?.also { it.gpuUndo()?.let(onGpuUndoDiscarded) }

    /**
     * Symmetric to [dropTopRedo]: discards the counter-entry [popRedo] just speculatively pushed onto
     * the undo stack, for a redo that turns out to be inapplicable (its layer's cache was evicted).
     */
    fun dropTopUndo(): EditCommand? = undoStack.removeLastOrNull()?.also { it.gpuUndo()?.let(onGpuUndoDiscarded) }

    /** The most recent undoable entry, or null if the undo stack is empty. Test-only visibility
     *  into whether item 16's tile-delta fast path actually got attached to a given stroke. */
    internal fun undoStackTopForTest(): EditCommand? = undoStack.lastOrNull()

    fun clear() {
        undoStack.discardAll(onGpuUndoDiscarded)
        clearRedo()
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
        if (undoStack.size > maxStackSize) undoStack.removeFirst().gpuUndo()?.let(onGpuUndoDiscarded)
    }

    private fun clearRedo() = redoStack.discardAll(onGpuUndoDiscarded)
}

private fun EditCommand.gpuUndo(): com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots.Ref? =
    (this as? EditCommand.Draw)?.gpuUndo

/** Empties the stack, handing every GPU undo snapshot it held to [discard]. */
private fun ArrayDeque<EditCommand>.discardAll(
    discard: (com.hereliesaz.graffitixr.nativebridge.GpuUndoSnapshots.Ref) -> Unit,
) {
    forEach { it.gpuUndo()?.let(discard) }
    clear()
}
