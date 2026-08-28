package com.hereliesaz.graffitixr.common.azphalt

/**
 * Partitions a canvas into fixed-size square tiles and maps a [DirtyRegion] onto the tile
 * indices it overlaps (roadmap item 16). This is the second foundational piece of dirty-region
 * tracking, alongside [DirtyRegion] itself -- it still does NOT touch undo storage:
 * `EditHistory`/`LayerStore` remain whole-bitmap-snapshot based. Tile-based *undo* (replacing
 * those whole-bitmap snapshots with per-tile deltas) is a materially larger, correctness-
 * sensitive rewrite of that storage layer, not attempted here; see the roadmap doc for why.
 *
 * [tileSize] must be positive. Tile `(tx, ty)` covers pixels
 * `[tx*tileSize, (tx+1)*tileSize) x [ty*tileSize, (ty+1)*tileSize)` in canvas space, clipped to
 * `[0, canvasWidth) x [0, canvasHeight)` at the grid's edges.
 */
data class TileGrid(val canvasWidth: Int, val canvasHeight: Int, val tileSize: Int) {
    init {
        require(tileSize > 0) { "tileSize must be positive, was $tileSize" }
    }

    val columns: Int get() = if (canvasWidth <= 0) 0 else (canvasWidth + tileSize - 1) / tileSize
    val rows: Int get() = if (canvasHeight <= 0) 0 else (canvasHeight + tileSize - 1) / tileSize

    /** Pixel bounds of tile `(tx, ty)`, clamped to the canvas. Empty if `(tx, ty)` is out of range. */
    fun tileBounds(tx: Int, ty: Int): DirtyRegion {
        if (tx < 0 || ty < 0 || tx >= columns || ty >= rows) return DirtyRegion(0, 0, 0, 0)
        return DirtyRegion(
            left = tx * tileSize,
            top = ty * tileSize,
            right = minOf((tx + 1) * tileSize, canvasWidth),
            bottom = minOf((ty + 1) * tileSize, canvasHeight),
        )
    }

    /**
     * The inclusive `(tx, ty)` tile-index range [region] overlaps, clamped to this grid's bounds.
     * Empty (zero rows/columns covered) when [region] is empty or entirely outside the canvas.
     */
    fun tilesTouching(region: DirtyRegion): TileRange {
        val clamped = region.clampTo(canvasWidth, canvasHeight)
        if (clamped.isEmpty) return TileRange(0, 0, -1, -1)
        val txMin = clamped.left / tileSize
        val tyMin = clamped.top / tileSize
        // right/bottom are exclusive; the last touched tile is the one containing (right-1, bottom-1).
        val txMax = (clamped.right - 1) / tileSize
        val tyMax = (clamped.bottom - 1) / tileSize
        return TileRange(txMin, tyMin, txMax, tyMax)
    }

    /** Inclusive tile-index rectangle. [isEmpty] when no tiles are covered. */
    data class TileRange(val txMin: Int, val tyMin: Int, val txMax: Int, val tyMax: Int) {
        val isEmpty: Boolean get() = txMax < txMin || tyMax < tyMin
        val tileCount: Int get() = if (isEmpty) 0 else (txMax - txMin + 1) * (tyMax - tyMin + 1)

        /** Every `(tx, ty)` pair in this range, row-major. Empty list if [isEmpty]. */
        fun indices(): List<Pair<Int, Int>> {
            if (isEmpty) return emptyList()
            val result = ArrayList<Pair<Int, Int>>(tileCount)
            for (ty in tyMin..tyMax) {
                for (tx in txMin..txMax) {
                    result.add(tx to ty)
                }
            }
            return result
        }
    }
}
