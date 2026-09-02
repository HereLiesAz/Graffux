package com.hereliesaz.graffux.desktop

import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.RoundStampCompositor
import com.hereliesaz.graffitixr.common.azphalt.TileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Tile size in pixels. Small enough to spread a stroke's dirty region over several cores on a
 *  Surface Pro's multi-core CPU (typically 4-16 cores depending on generation), large enough that
 *  per-tile overhead (bounds math, coroutine dispatch) doesn't dominate for a small stroke. */
private const val TILE_SIZE_PX = 192

/**
 * Rasterizes [dabs] onto a canvas of [canvasWidth] x [canvasHeight], splitting the stroke's dirty
 * region into [TileGrid] tiles and compositing them concurrently on [kotlinx.coroutines.Dispatchers.Default]
 * (backed by a thread per CPU core). This is the CPU-side "engine" optimization this desktop app
 * makes for Surface Pro hardware: there is no cross-platform GPU compute path available to a plain
 * JVM Compose Desktop app (the Android build's native Vulkan stamp engine is Android-NDK-only — see
 * DESKTOP.md), so the real lever here is keeping every core busy instead of rasterizing a big stroke
 * single-threaded on the UI thread.
 *
 * Each tile's dab-overlap filter is a cheap bounding-box test, not a re-run of the full compositor,
 * so passing the whole (unfiltered) [dabs] list per tile is fine even for a few hundred tiles.
 */
suspend fun compositeTileParallel(
    dabs: List<Dab>,
    canvasWidth: Int,
    canvasHeight: Int,
    colorArgb: Int,
    secondaryColorArgb: Int,
    colorSource: BrushColorSource,
    flow: Float,
): List<com.hereliesaz.graffitixr.common.azphalt.CompositedTile> = coroutineScope {
    val overall = DirtyRegion.fromDabs(dabs)?.clampTo(canvasWidth, canvasHeight) ?: return@coroutineScope emptyList()
    if (overall.isEmpty) return@coroutineScope emptyList()

    val grid = TileGrid(canvasWidth, canvasHeight, TILE_SIZE_PX)
    val range = grid.tilesTouching(overall)
    if (range.isEmpty) return@coroutineScope emptyList()

    range.indices().map { (tx, ty) ->
        // Explicitly on Dispatchers.Default, not the caller's own dispatcher: a coroutine started
        // with plain `async {}` inherits its parent scope's dispatcher, and the only caller here is
        // bound to Compose's single-threaded UI/composition dispatcher — without this, every tile
        // would still composite one after another on that one thread, defeating the whole point.
        async(Dispatchers.Default) {
            val bounds = grid.tileBounds(tx, ty)
            val relevant = dabs.filter { d ->
                val r = d.radius.coerceAtLeast(0.5f)
                d.x + r >= bounds.left && d.x - r <= bounds.right &&
                    d.y + r >= bounds.top && d.y - r <= bounds.bottom
            }
            if (relevant.isEmpty()) {
                null
            } else {
                RoundStampCompositor.compositeMaxCombinedForRegion(
                    relevant, bounds, colorArgb, secondaryColorArgb, colorSource, flow,
                )
            }
        }
    }.mapNotNull { it.await() }
}
