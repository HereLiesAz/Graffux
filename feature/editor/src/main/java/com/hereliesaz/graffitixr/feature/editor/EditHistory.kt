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
    /** Most strokes one undo chunk holds (see [pushDraw]). */
    private val chunkMaxStrokes: Int = DEFAULT_CHUNK_MAX_STROKES,
    /** Longest pause (ms) between two strokes of one undo chunk. */
    private val chunkPauseMs: Long = DEFAULT_CHUNK_PAUSE_MS,
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
    fun pushProperty(
        layersWithoutBitmaps: List<Layer>,
        crop: com.hereliesaz.graffitixr.common.photo.DocumentCrop? = null,
    ): Boolean {
        clearRedo()
        val last = undoStack.lastOrNull()
        val same = last is EditCommand.PropertyChange && last.oldLayers == layersWithoutBitmaps && last.crop == crop
        if (same) return false
        undoStack.addLast(EditCommand.PropertyChange(layersWithoutBitmaps, crop))
        trim(maxStackSize)
        return true
    }

    /**
     * Records a completed brush stroke. Pushing clears the redo stack.
     *
     * A [chunkable] stroke joins the newest entry's undo chunk instead of starting its own when that
     * entry is a chunkable stroke on the same [layerId] with the same tool, holds fewer than
     * [chunkMaxStrokes] strokes, and was pushed no more than [chunkPauseMs] before [nowMs]. Undo and
     * redo then act on the whole chunk. Joining frees the entry's GPU undo snapshot (two cannot be
     * merged); its tile deltas are merged as each stroke's arrive ([attachTileDeltas]).
     *
     * Returns whether the stroke joined an existing chunk, so its commit can skip capturing a GPU
     * undo snapshot the chunk would only free again.
     */
    @Suppress("LongParameterList")
    fun pushDraw(
        layerId: String,
        command: StrokeCommand,
        chunkable: Boolean = false,
        nowMs: Long = System.nanoTime() / NANOS_PER_MS,
        /** A view plane the layer adopted for this stroke; it never joins an earlier chunk. */
        plane: PlaneSwap? = null,
        /**
         * A later segment of the same physical gesture (a stroke the plane broke at a gap): it
         * always joins the newest entry when that is a stroke on [layerId], so one gesture is one
         * undo step whatever the chunk rules say.
         */
        continuesGesture: Boolean = false,
        /**
         * A later segment of the same gesture on a different layer than the newest entry's (a
         * stroke crossing between view images): it becomes its own entry, chained to the newest so
         * that undo and redo act on both as one step. Ignored when the newest entry is not a stroke.
         */
        chainedToPrevious: Boolean = false,
        /**
         * A stroke that may be taken straight back (a brush-tap dot that turns out to be the first
         * tap of a view-reset double-tap). It never joins a chunk; the stack may hold one entry
         * more than [maxStackSize] for it (so removing it loses nothing), and the redo stack is set
         * aside rather than discarded. [cancelTentative] removes it and brings the redo stack back;
         * any other edit, undo or redo makes it final and discards the set-aside redo.
         */
        tentative: Boolean = false,
    ): Boolean {
        val top = undoStack.lastOrNull()
        if (chainedToPrevious && top is EditCommand.Draw && top.layerId != layerId) {
            undoStack[undoStack.lastIndex] = top.copy(chainedNext = true)
            undoStack.addLast(
                EditCommand.Draw(layerId, command, chunkable = false, lastPushMs = nowMs, chainedToPrevious = true),
            )
            trim(maxStackSize)
            clearRedo()
            return false
        }
        // A tentative stroke is never chunk-able: it neither joins an earlier chunk nor is joined.
        val chunk = chunkable && !tentative
        val joined = top is EditCommand.Draw && if (continuesGesture) {
            top.layerId == layerId
        } else {
            joinsChunk(top, layerId, command, nowMs, plane == null && chunk)
        }
        if (joined) {
            top.gpuUndo?.let(onGpuUndoDiscarded)
            undoStack[undoStack.lastIndex] = top.copy(
                earlier = top.strokes, command = command, gpuUndo = null, lastPushMs = nowMs,
            )
        } else {
            undoStack.addLast(
                EditCommand.Draw(
                    layerId, command, chunkable = chunk, lastPushMs = nowMs, plane = plane,
                ),
            )
            trim(if (tentative) maxStackSize + 1 else maxStackSize)
        }
        if (tentative && !joined) setAsideRedo() else clearRedo()
        return joined
    }

    private fun joinsChunk(
        top: EditCommand.Draw,
        layerId: String,
        command: StrokeCommand,
        nowMs: Long,
        allowed: Boolean,
    ) = allowed && top.chunkable && top.layerId == layerId && top.command.tool == command.tool &&
            top.strokes.size < chunkMaxStrokes && nowMs - top.lastPushMs <= chunkPauseMs

    /** Whether the newest undo entry is a stroke. */
    fun undoTopIsDraw(): Boolean = undoStack.lastOrNull() is EditCommand.Draw

    /** Whether the next entry redo would apply is a stroke. */
    fun redoTopIsDraw(): Boolean = redoStack.lastOrNull() is EditCommand.Draw

    /**
     * Cancels a tentative push of [command]: when it is still the newest entry (by identity, alone
     * in its entry), runs [undo] (which must take that entry back off the undo stack, as the editor's
     * undo does, repainting the layer) and then restores the redo stack the push set aside, leaving
     * no redo of the dot. False, with nothing run, when the entry is not the newest.
     */
    fun cancelTentative(command: StrokeCommand, undo: () -> Unit): Boolean {
        val top = undoStack.lastOrNull() as? EditCommand.Draw
        val holds = top != null && top.strokes.size == 1 && top.strokes[0] === command
        if (holds) {
            val aside = setAsideRedo
            setAsideRedo = null
            undo()
            redoStack.discardAll(onGpuUndoDiscarded)
            aside?.let { redoStack.addAll(it) }
        }
        return holds
    }

    /**
     * The plane adoption to take back when [command] (by identity) never landed: the one riding on its entry, and
     * only when that entry is live (on the undo stack, not already undone, which put the plane back) and [command]
     * is all it holds. A stroke that joined a chunk, or an entry with other strokes in it, keeps its layer: only the
     * stroke is dropped ([dropDraw]).
     */
    fun planeSwapToRevert(command: StrokeCommand): PlaneSwap? {
        val entry = undoStack.lastOrNull { it is EditCommand.Draw && it.strokes.any { s -> s === command } }
        return (entry as? EditCommand.Draw)?.takeIf { it.strokes.size == 1 }?.plane
    }

    /** Whether the newest undo entry is a stroke entry holding [command] (by identity). */
    fun topDrawHolds(command: StrokeCommand): Boolean =
        (undoStack.lastOrNull() as? EditCommand.Draw)?.strokes?.any { it === command } == true

    /** How many strokes on [layerId] undo can still take back: what a bake must leave unbaked. */
    fun undoableStrokeCount(layerId: String): Int =
        undoStack.sumOf { if (it is EditCommand.Draw && it.layerId == layerId) it.strokes.size else 0 }

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
            val idx = stack.indexOfLast { it is EditCommand.Draw && it.strokes.any { stroke -> stroke === command } }
            if (idx < 0) return false
            val entry = stack[idx] as EditCommand.Draw
            stack[idx] = if (entry.earlier.isEmpty()) {
                // The entry owns exactly one snapshot: one it already held is freed when replaced
                // (a second, slower attach), never just dropped.
                entry.gpuUndo?.takeIf { it != gpuUndo }?.let(onGpuUndoDiscarded)
                entry.copy(
                    tileDeltas = tileDeltas,
                    tileDeltaCanvasWidth = canvasWidth,
                    tileDeltaCanvasHeight = canvasHeight,
                    gpuUndo = gpuUndo,
                    attached = listOf(command),
                )
            } else {
                // A chunk keeps no GPU snapshot: its strokes' snapshots cannot be merged.
                gpuUndo?.let(onGpuUndoDiscarded)
                mergeIntoChunk(entry, command, tileDeltas, canvasWidth, canvasHeight)
            }
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
            val idx = stack.indexOfLast { it is EditCommand.Draw && it.strokes.any { stroke -> stroke === command } }
            if (idx < 0) continue
            val entry = stack[idx] as EditCommand.Draw
            if (entry.earlier.isEmpty()) {
                stack.removeAt(idx).gpuUndo()?.let(onGpuUndoDiscarded)
                // A tentative entry that never landed can no longer be cancelled: release its set-aside redo.
                if (stack === undoStack) dropSetAside()
            } else {
                // One stroke of a chunk never landed: the chunk keeps the rest, but its merged tile
                // deltas still hold that stroke's pixels, so undo falls back to a full rebuild.
                val rest = entry.strokes.filterNot { it === command }
                entry.gpuUndo?.let(onGpuUndoDiscarded)
                stack[idx] = entry.copy(
                    earlier = rest.dropLast(1), command = rest.last(),
                    tileDeltas = null, gpuUndo = null, attached = emptyList(),
                )
            }
            return true
        }
        return false
    }

    /**
     * [entry] (a chunk) with [command]'s [deltas] merged in. Deltas merge only in stroke order (a
     * tile's before must come from the earliest stroke that touched it); anything else, a size
     * change or tiles from different grids drops the merged deltas, and the chunk then undoes by
     * a full rebuild.
     */
    private fun mergeIntoChunk(
        entry: EditCommand.Draw,
        command: StrokeCommand,
        deltas: List<com.hereliesaz.graffitixr.common.azphalt.TileDelta.TileSnapshot>,
        canvasWidth: Int,
        canvasHeight: Int,
    ): EditCommand.Draw {
        val strokes = entry.strokes
        val position = strokes.indexOfFirst { it === command }
        val newest = entry.attached.maxOfOrNull { attached -> strokes.indexOfFirst { it === attached } } ?: -1
        val existing = entry.tileDeltas
        val sameCanvas = entry.tileDeltaCanvasWidth == canvasWidth && entry.tileDeltaCanvasHeight == canvasHeight
        val merged = when {
            position <= newest -> null
            existing == null -> deltas
            !sameCanvas -> null
            else -> com.hereliesaz.graffitixr.common.azphalt.TileDelta.merge(existing, deltas)
        }
        return if (merged == null) {
            entry.copy(tileDeltas = null, attached = emptyList())
        } else {
            entry.copy(
                tileDeltas = merged,
                tileDeltaCanvasWidth = canvasWidth,
                tileDeltaCanvasHeight = canvasHeight,
                attached = entry.attached + command,
            )
        }
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
        dropSetAside()
        val command = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(counterEntry(command))
        return command
    }

    /** Symmetric to [popUndo]: pops the most recent redoable command onto the undo stack. */
    fun popRedo(counterEntry: (EditCommand) -> EditCommand): EditCommand? {
        dropSetAside()
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

    private fun trim(limit: Int) {
        while (undoStack.size > limit) undoStack.removeFirst().gpuUndo()?.let(onGpuUndoDiscarded)
    }

    /** The redo stack a tentative push set aside, to be restored if it is cancelled. */
    private var setAsideRedo: List<EditCommand>? = null

    private fun setAsideRedo() {
        dropSetAside()
        setAsideRedo = redoStack.toList()
        redoStack.clear()
    }

    private fun dropSetAside() {
        setAsideRedo?.forEach { it.gpuUndo()?.let(onGpuUndoDiscarded) }
        setAsideRedo = null
    }

    private fun clearRedo() {
        redoStack.discardAll(onGpuUndoDiscarded)
        dropSetAside()
    }

    companion object {
        /** Default for [chunkMaxStrokes]. */
        const val DEFAULT_CHUNK_MAX_STROKES = 5

        /** Default for [chunkPauseMs]. */
        const val DEFAULT_CHUNK_PAUSE_MS = 1_500L
        private const val NANOS_PER_MS = 1_000_000L
    }
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

/**
 * A layer's plane before and after view-plane adoption, undone and redone with its stroke. [created] is set when
 * the plane was a draw-plane depth's and the stroke went onto a new layer made for it: undo removes that layer
 * with the stroke, redo puts it back, so creating the layer and drawing on it are one step.
 */
data class PlaneSwap(
    val before: com.hereliesaz.graffitixr.common.scene.PlanePose,
    val after: com.hereliesaz.graffitixr.common.scene.PlanePose,
    val created: CreatedPlaneLayer? = null,
)

/** The layer a draw-plane depth made for a stroke: snapshot (no bitmap), place in the stack, what was active. */
data class CreatedPlaneLayer(
    val layer: Layer,
    val index: Int,
    val previousActiveId: String?,
)
