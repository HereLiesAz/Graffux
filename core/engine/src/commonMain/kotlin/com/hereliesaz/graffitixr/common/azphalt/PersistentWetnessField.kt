package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.exp

/**
 * Persistent normalized wetness channel for Phase 4.
 *
 * The backing channel is canvas-sized because wetness is persistent material state, but simulation
 * work is strictly limited to [activeTileCount] tiles. No idle full-canvas scan is performed: an
 * empty active set makes [advance] an O(1) no-op. Callers activate tiles by depositing wetness or
 * explicitly touching a [DirtyRegion]. Transport never leaks into an inactive neighbouring tile;
 * a stroke/action must explicitly activate that tile first. That keeps rapid boundary-crossing
 * work deterministic and bounded by the action's touched tiles rather than the whole layer.
 *
 * [tileSize] defaults to 64 to match Graffux's existing tile-based dirty/undo machinery. The
 * internal full-size [deltaScratch] is temporary numerical workspace, not a second material
 * channel; only pixels in active tiles are cleared/read/written on each step.
 */
class PersistentWetnessField(
    val width: Int,
    val height: Int,
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    init {
        require(width > 0) { "PersistentWetnessField.width must be positive" }
        require(height > 0) { "PersistentWetnessField.height must be positive" }
        require(tileSize > 0) { "PersistentWetnessField.tileSize must be positive" }
    }

    private val grid = TileGrid(width, height, tileSize)
    private val wetness = FloatArray(width * height)
    private val deltaScratch = FloatArray(width * height)
    private val activeTiles = BooleanArray(grid.columns * grid.rows)
    private var activeCount = 0

    val activeTileCount: Int get() = activeCount
    val isIdle: Boolean get() = activeCount == 0

    /** One deterministic simulation-step accounting record, useful for profiling and tests. */
    data class StepStats(
        val activeTilesProcessed: Int,
        val pixelsProcessed: Int,
        val remainingActiveTiles: Int,
        val wetnessBeforeDrying: Float,
        val wetnessAfter: Float,
    )

    /** Returns normalized wetness at one pixel, or 0 outside the canvas. */
    fun wetnessAt(x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        return wetness[y * width + x]
    }

    /**
     * Adds normalized wetness to one pixel and activates its tile. Out-of-bounds coordinates and
     * non-positive additions are safe no-ops. Returns the resulting pixel wetness.
     */
    fun addWetness(x: Int, y: Int, amount: Float): Float {
        if (x !in 0 until width || y !in 0 until height || amount <= 0f) return wetnessAt(x, y)
        activateTile(x / tileSize, y / tileSize)
        val index = y * width + x
        wetness[index] = (wetness[index] + amount).coerceIn(0f, 1f)
        return wetness[index]
    }

    /**
     * Uniformly deposits [amount] into every pixel of [region], clipped to the canvas. This is a
     * core reference primitive; renderers may call [addWetness] with their own per-pixel coverage
     * when they need shaped deposition.
     */
    fun deposit(region: DirtyRegion, amount: Float) {
        if (amount <= 0f) return
        val clamped = region.clampTo(width, height)
        if (clamped.isEmpty) return
        activate(clamped)
        for (y in clamped.top until clamped.bottom) {
            var index = y * width + clamped.left
            for (x in clamped.left until clamped.right) {
                wetness[index] = (wetness[index] + amount).coerceIn(0f, 1f)
                index++
            }
        }
    }

    /** Marks every tile intersecting [region] active without changing its current wetness. */
    fun activate(region: DirtyRegion) {
        val touched = grid.tilesTouching(region)
        if (touched.isEmpty) return
        for ((tx, ty) in touched.indices()) activateTile(tx, ty)
    }

    /** Active tile coordinates in deterministic row-major order. */
    fun activeTileCoordinates(): List<Pair<Int, Int>> {
        if (activeCount == 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>(activeCount)
        for (id in activeTiles.indices) {
            if (activeTiles[id]) out += (id % grid.columns) to (id / grid.columns)
        }
        return out
    }

    /** Defensive snapshot for persistence/tests; not intended for the render hot path. */
    fun snapshot(): FloatArray = wetness.copyOf()

    /**
     * Advances drying and bounded four-neighbour transport by an explicit [deltaSeconds].
     *
     * Transport is pairwise and symmetric, so with `dryingRate = 0` it conserves total wetness
     * (modulo Float rounding). Only edges whose two endpoint tiles are already active participate;
     * inactive adjacent tiles are never initialized or modified implicitly. Drying is exponential,
     * making the result stable for different frame subdivisions of the same elapsed time.
     *
     * [transportRate] and [dryingRate] are normalized rates per second. Extremely large time steps
     * are clamped for transport stability, while drying remains time-correct through `exp()`.
     */
    fun advance(
        deltaSeconds: Float,
        dryingRate: Float,
        transportRate: Float,
        dryEpsilon: Float = DEFAULT_DRY_EPSILON,
        /** Optional per-pixel drying rate for spatially owned media. */
        dryingRateAt: ((x: Int, y: Int) -> Float)? = null,
    ): StepStats {
        val dt = deltaSeconds.coerceAtLeast(0f)
        val startingTiles = activeCount
        if (startingTiles == 0 || dt <= 0f) {
            return StepStats(startingTiles, 0, activeCount, totalActiveWetness(), totalActiveWetness())
        }

        val epsilon = dryEpsilon.coerceAtLeast(0f)
        val transportStep = (transportRate.coerceIn(0f, 1f) * dt).coerceIn(0f, 1f) * 0.25f
        var pixelsProcessed = 0
        var before = 0f

        // Clear only the active numerical workspace and collect the pre-step mass.
        forEachActivePixel { index, _, _ ->
            deltaScratch[index] = 0f
            before += wetness[index]
            pixelsProcessed++
        }

        // Visit each undirected edge exactly once (right + down). Pairwise flux is equal/opposite.
        if (transportStep > 0f) {
            forEachActivePixel { index, x, y ->
                if (x + 1 < width && isPixelTileActive(x + 1, y)) {
                    exchange(index, index + 1, transportStep)
                }
                if (y + 1 < height && isPixelTileActive(x, y + 1)) {
                    exchange(index, index + width, transportStep)
                }
            }
        }

        val drying = dryingRate.coerceIn(0f, 1f)
        val uniformDryMultiplier = exp((-drying * dt).toDouble()).toFloat()
        var after = 0f
        val stillWetTiles = BooleanArray(activeTiles.size)

        forEachActivePixel { index, x, y ->
            val dryMultiplier = if (dryingRateAt == null) {
                uniformDryMultiplier
            } else {
                val localRate = dryingRateAt(x, y).coerceIn(0f, 1f)
                exp((-localRate * dt).toDouble()).toFloat()
            }
            val next = ((wetness[index] + deltaScratch[index]).coerceIn(0f, 1f) * dryMultiplier)
                .coerceIn(0f, 1f)
            wetness[index] = if (next <= epsilon) 0f else next
            if (wetness[index] > epsilon) {
                val tileId = tileId(x / tileSize, y / tileSize)
                stillWetTiles[tileId] = true
                after += wetness[index]
            }
        }

        // A dry tile falls out of the simulation immediately; no full-layer scan is required.
        var remaining = 0
        for (id in activeTiles.indices) {
            if (!activeTiles[id]) continue
            activeTiles[id] = stillWetTiles[id]
            if (activeTiles[id]) remaining++
        }
        activeCount = remaining

        return StepStats(
            activeTilesProcessed = startingTiles,
            pixelsProcessed = pixelsProcessed,
            remainingActiveTiles = remaining,
            wetnessBeforeDrying = before,
            wetnessAfter = after,
        )
    }

    /** Clears only active tiles and returns the field to the idle state. */
    fun clearActiveWetness() {
        if (activeCount == 0) return
        forEachActivePixel { index, _, _ ->
            wetness[index] = 0f
            deltaScratch[index] = 0f
        }
        activeTiles.fill(false)
        activeCount = 0
    }

    private fun exchange(a: Int, b: Int, step: Float) {
        val fluxIntoA = (wetness[b] - wetness[a]) * step
        deltaScratch[a] += fluxIntoA
        deltaScratch[b] -= fluxIntoA
    }

    private fun activateTile(tx: Int, ty: Int) {
        if (tx !in 0 until grid.columns || ty !in 0 until grid.rows) return
        val id = tileId(tx, ty)
        if (!activeTiles[id]) {
            activeTiles[id] = true
            activeCount++
        }
    }

    private fun tileId(tx: Int, ty: Int): Int = ty * grid.columns + tx

    private fun isPixelTileActive(x: Int, y: Int): Boolean =
        activeTiles[tileId(x / tileSize, y / tileSize)]

    private inline fun forEachActivePixel(block: (index: Int, x: Int, y: Int) -> Unit) {
        for (tileId in activeTiles.indices) {
            if (!activeTiles[tileId]) continue
            val tx = tileId % grid.columns
            val ty = tileId / grid.columns
            val bounds = grid.tileBounds(tx, ty)
            for (y in bounds.top until bounds.bottom) {
                var index = y * width + bounds.left
                for (x in bounds.left until bounds.right) {
                    block(index, x, y)
                    index++
                }
            }
        }
    }

    private fun totalActiveWetness(): Float {
        var total = 0f
        forEachActivePixel { index, _, _ -> total += wetness[index] }
        return total
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 64
        const val DEFAULT_DRY_EPSILON = 1e-4f
    }
}
