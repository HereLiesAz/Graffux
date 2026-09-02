package com.hereliesaz.graffitixr.common.azphalt

/**
 * Captures and replays per-tile pixel deltas between two ARGB pixel buffers of the same canvas
 * (roadmap item 16's tile-based undo storage). This is the third and final foundational piece of
 * item 16's dirty-region tracking, after [DirtyRegion] and [TileGrid].
 *
 * Given a "before" and "after" pixel buffer and the tile range [DirtyRegion.fromDabs] +
 * [TileGrid.tilesTouching] narrow a stroke down to, [capture] records only those tiles'
 * before/after pixels (a small, bounded copy) instead of the whole canvas, and [applyBefore]/
 * [applyAfter] losslessly restore either side later. `EditorViewModel.applyTileDeltaFastPath`
 * is the actual consumer: `EditHistory.attachTileDeltas`'s own invariant (a `Draw` command still
 * reachable by undo/redo is always the layer's most recent stroke, or, for redo, the next one to
 * reapply) is what makes patching the live bitmap in place with these deltas safe, letting a
 * fast undo/redo skip a full-stroke replay onto a whole-bitmap snapshot.
 */
object TileDelta {

    /** One tile's captured before/after pixel content, in row-major order within [bounds]. */
    data class TileSnapshot(
        val tx: Int,
        val ty: Int,
        val bounds: DirtyRegion,
        val before: IntArray,
        val after: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TileSnapshot) return false
            return tx == other.tx && ty == other.ty && bounds == other.bounds &&
                before.contentEquals(other.before) && after.contentEquals(other.after)
        }

        override fun hashCode(): Int {
            var result = tx
            result = 31 * result + ty
            result = 31 * result + bounds.hashCode()
            result = 31 * result + before.contentHashCode()
            result = 31 * result + after.contentHashCode()
            return result
        }
    }

    /**
     * Captures every tile [grid] and [touched] name from [before]/[after] -- both full-canvas
     * ARGB pixel buffers, [grid].`canvasWidth`×[grid].`canvasHeight` long, row-major. Returns one
     * [TileSnapshot] per touched tile, in [TileGrid.TileRange.indices] order. Empty when [touched]
     * is empty, or when [before]/[after] don't match the canvas size (a defensive no-op rather
     * than an out-of-bounds read -- the same "mismatched size degrades safely" contract this
     * codebase uses elsewhere, e.g. `ColorSmudgeEngine.apply`'s `sampleSource`).
     */
    fun capture(
        before: IntArray,
        after: IntArray,
        grid: TileGrid,
        touched: TileGrid.TileRange,
    ): List<TileSnapshot> {
        val canvasPixels = grid.canvasWidth * grid.canvasHeight
        if (before.size < canvasPixels || after.size < canvasPixels) return emptyList()
        return touched.indices().map { (tx, ty) ->
            val bounds = grid.tileBounds(tx, ty)
            val before2 = extract(before, grid.canvasWidth, bounds)
            val after2 = extract(after, grid.canvasWidth, bounds)
            TileSnapshot(tx, ty, bounds, before2, after2)
        }
    }

    /** Writes each snapshot's [TileSnapshot.before] pixels back into [target] (an undo). */
    fun applyBefore(target: IntArray, canvasWidth: Int, snapshots: List<TileSnapshot>) =
        apply(target, canvasWidth, snapshots) { it.before }

    /** Writes each snapshot's [TileSnapshot.after] pixels back into [target] (a redo). */
    fun applyAfter(target: IntArray, canvasWidth: Int, snapshots: List<TileSnapshot>) =
        apply(target, canvasWidth, snapshots) { it.after }

    private inline fun apply(
        target: IntArray,
        canvasWidth: Int,
        snapshots: List<TileSnapshot>,
        side: (TileSnapshot) -> IntArray,
    ) {
        for (snapshot in snapshots) {
            val bounds = snapshot.bounds
            if (bounds.isEmpty) continue
            val pixels = side(snapshot)
            var srcIndex = 0
            for (y in bounds.top until bounds.bottom) {
                val rowStart = y * canvasWidth + bounds.left
                for (x in 0 until bounds.width) {
                    val dstIndex = rowStart + x
                    if (dstIndex in target.indices) target[dstIndex] = pixels[srcIndex]
                    srcIndex++
                }
            }
        }
    }

    private fun extract(pixels: IntArray, canvasWidth: Int, bounds: DirtyRegion): IntArray {
        if (bounds.isEmpty) return IntArray(0)
        val out = IntArray(bounds.width * bounds.height)
        var dstIndex = 0
        for (y in bounds.top until bounds.bottom) {
            val rowStart = y * canvasWidth + bounds.left
            for (x in 0 until bounds.width) {
                out[dstIndex] = pixels[rowStart + x]
                dstIndex++
            }
        }
        return out
    }
}
