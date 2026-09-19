package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.roundToInt

/**
 * Bounded CPU reference for Phase-4 pigment/display-colour transport.
 *
 * Work is proportional to [PersistentWetnessField.activeTileCount]. Only pixels in already-active
 * wet tiles participate and an edge crossing into an inactive tile is skipped, matching the
 * wetness solver's explicit tile-boundary contract. Each undirected neighbour edge is processed
 * once (right + down). Channel transfer is equal/opposite integer mass, so every exchange
 * conserves the summed ARGB channel values exactly before ordinary 0..255 bounds.
 *
 * This deliberately is not a fluid solver. It is a small deterministic diffusion proxy whose job
 * is to make wet paint locally mobile while keeping dry/idle documents cheap.
 */
object WetMaterialTransport {
    data class Stats(
        val activeTilesProcessed: Int,
        val pixelsVisited: Int,
        val edgesProcessed: Int,
    )

    fun advanceArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        wetness: PersistentWetnessField,
        deltaSeconds: Float,
        transportRate: Float,
        iterations: Int = DEFAULT_ITERATIONS,
    ): Stats {
        require(width == wetness.width && height == wetness.height) {
            "WetMaterialTransport dimensions must match the wetness field"
        }
        require(pixels.size >= width * height) {
            "WetMaterialTransport pixels must contain width*height entries"
        }

        val active = wetness.activeTileCoordinates()
        val dt = deltaSeconds.coerceAtLeast(0f)
        val count = iterations.coerceIn(0, MAX_ITERATIONS)
        if (active.isEmpty() || dt <= 0f || transportRate <= 0f || count == 0) {
            return Stats(active.size, 0, 0)
        }

        val tileSize = wetness.tileSize
        val columns = (width + tileSize - 1) / tileSize
        val activeIds = HashSet<Int>(active.size * 2)
        for ((tx, ty) in active) activeIds += ty * columns + tx

        var visited = 0
        var edges = 0
        val iterationRate = transportRate.coerceIn(0f, 1f) * dt / count

        repeat(count) {
            for ((tx, ty) in active) {
                val left = tx * tileSize
                val top = ty * tileSize
                val right = minOf(width, left + tileSize)
                val bottom = minOf(height, top + tileSize)

                for (y in top until bottom) {
                    var index = y * width + left
                    for (x in left until right) {
                        visited++
                        if (x + 1 < width && isActive(x + 1, y, tileSize, columns, activeIds)) {
                            val mobility = ((wetness.wetnessAt(x, y) + wetness.wetnessAt(x + 1, y)) * 0.5f)
                                .coerceIn(0f, 1f)
                            val step = (iterationRate * mobility).coerceIn(0f, MAX_EDGE_STEP)
                            if (step > 0f) {
                                exchangeArgb(pixels, index, index + 1, step)
                                edges++
                            }
                        }
                        if (y + 1 < height && isActive(x, y + 1, tileSize, columns, activeIds)) {
                            val mobility = ((wetness.wetnessAt(x, y) + wetness.wetnessAt(x, y + 1)) * 0.5f)
                                .coerceIn(0f, 1f)
                            val step = (iterationRate * mobility).coerceIn(0f, MAX_EDGE_STEP)
                            if (step > 0f) {
                                exchangeArgb(pixels, index, index + width, step)
                                edges++
                            }
                        }
                        index++
                    }
                }
            }
        }

        return Stats(
            activeTilesProcessed = active.size,
            pixelsVisited = visited,
            edgesProcessed = edges,
        )
    }

    private fun isActive(
        x: Int,
        y: Int,
        tileSize: Int,
        columns: Int,
        activeIds: Set<Int>,
    ): Boolean = ((y / tileSize) * columns + (x / tileSize)) in activeIds

    private fun exchangeArgb(pixels: IntArray, aIndex: Int, bIndex: Int, step: Float) {
        val a = pixels[aIndex]
        val b = pixels[bIndex]

        fun move(shift: Int): Int {
            val av = a ushr shift and 0xFF
            val bv = b ushr shift and 0xFF
            return ((bv - av) * step).roundToInt()
        }

        val da = move(24)
        val dr = move(16)
        val dg = move(8)
        val db = move(0)

        fun packed(base: Int, sign: Int): Int {
            val alpha = ((base ushr 24 and 0xFF) + sign * da).coerceIn(0, 255)
            val red = ((base ushr 16 and 0xFF) + sign * dr).coerceIn(0, 255)
            val green = ((base ushr 8 and 0xFF) + sign * dg).coerceIn(0, 255)
            val blue = ((base and 0xFF) + sign * db).coerceIn(0, 255)
            return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        pixels[aIndex] = packed(a, +1)
        pixels[bIndex] = packed(b, -1)
    }

    const val DEFAULT_ITERATIONS = 2
    const val MAX_ITERATIONS = 4
    private const val MAX_EDGE_STEP = 0.25f
}
