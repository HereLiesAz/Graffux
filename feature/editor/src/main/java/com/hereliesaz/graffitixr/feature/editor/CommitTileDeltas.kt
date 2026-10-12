package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.TileDelta
import com.hereliesaz.graffitixr.common.azphalt.TileGrid

/**
 * The undo tiles for a commit whose extent is known ([painted], the GPU stroke's rectangle): only
 * the [UNDO_TILE_SIZE] tiles [painted] touches are read from [base] and [committed], instead of
 * reading and diffing both whole layers. Same snapshots TileDelta.capture gives for those tiles.
 */
internal fun regionTileDeltas(base: Bitmap, committed: Bitmap, painted: DirtyRegion): List<TileDelta.TileSnapshot> {
    val grid = TileGrid(base.width, base.height, UNDO_TILE_SIZE)
    val tiles = grid.tilesTouching(painted)
    if (tiles.isEmpty) return emptyList()
    val first = grid.tileBounds(tiles.txMin, tiles.tyMin)
    val last = grid.tileBounds(tiles.txMax, tiles.tyMax)
    val region = DirtyRegion(first.left, first.top, last.right, last.bottom)
    val before = IntArray(region.width * region.height)
    val after = IntArray(region.width * region.height)
    base.getPixels(before, 0, region.width, region.left, region.top, region.width, region.height)
    committed.getPixels(after, 0, region.width, region.left, region.top, region.width, region.height)
    return TileDelta.captureRegion(before, after, region, grid)
}
