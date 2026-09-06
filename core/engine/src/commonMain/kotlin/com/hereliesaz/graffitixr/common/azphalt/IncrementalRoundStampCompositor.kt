package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stateful max-combine compositor for a single round-tip, non-build-up stroke.
 *
 * Unlike [RoundStampCompositor], this keeps the strongest coverage/color already seen in sparse
 * fixed-size tiles. Appending a new dab therefore touches only the pixels covered by that dab and
 * returns only tiles whose stored maximum changed. The work of a live stroke is proportional to
 * newly-arrived dabs, not the total number of dabs since pointer-down.
 */
class IncrementalRoundStampCompositor(
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val tileSize: Int = 64,
) {
    init {
        require(canvasWidth > 0 && canvasHeight > 0)
        require(tileSize > 0)
    }

    private data class TileState(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val alpha: FloatArray,
        val color: IntArray,
    )

    private val tiles = mutableMapOf<Long, TileState>()

    fun append(
        dabs: List<Dab>,
        colorArgb: Int,
        secondaryColorArgb: Int,
        colorSource: BrushColorSource,
        flow: Float,
    ): List<CompositedTile> {
        if (dabs.isEmpty()) return emptyList()
        val baseFlow = flow.coerceIn(0f, 1f)
        val changed = linkedSetOf<Long>()

        for (dab in dabs) {
            val dabColor = ArgbColor.resolveDabColor(colorArgb, secondaryColorArgb, colorSource, dab)
            val rgb = dabColor and 0x00FFFFFF
            val radius = max(dab.radius, 0.5f)
            val strength = (
                ArgbColor.alpha(dabColor) / 255f * dab.alpha * baseFlow * dab.flowMultiplier.coerceAtLeast(0f)
                ).coerceIn(0f, 1f)
            if (strength <= 0f) continue

            val left = floor(dab.x - radius).toInt().coerceIn(0, canvasWidth - 1)
            val top = floor(dab.y - radius).toInt().coerceIn(0, canvasHeight - 1)
            val right = ceil(dab.x + radius).toInt().coerceIn(0, canvasWidth - 1)
            val bottom = ceil(dab.y + radius).toInt().coerceIn(0, canvasHeight - 1)
            if (right < left || bottom < top) continue

            var py = top
            while (py <= bottom) {
                val dy = py + 0.5f - dab.y
                var px = left
                while (px <= right) {
                    val dx = px + 0.5f - dab.x
                    val rNorm = hypot(dx, dy) / radius
                    if (rNorm < 1f) {
                        val localAlpha = BrushStamps.stampCoverage(rNorm, dab.hardness) * strength
                        if (localAlpha > 0f) {
                            val tx = px / tileSize
                            val ty = py / tileSize
                            val key = key(tx, ty)
                            val tile = tiles.getOrPut(key) { createTile(tx, ty) }
                            val localX = px - tile.left
                            val localY = py - tile.top
                            val index = localY * tile.width + localX
                            if (localAlpha > tile.alpha[index]) {
                                tile.alpha[index] = localAlpha
                                tile.color[index] = rgb
                                changed.add(key)
                            }
                        }
                    }
                    px++
                }
                py++
            }
        }

        if (changed.isEmpty()) return emptyList()
        return changed.mapNotNull { key -> tiles[key]?.toCompositedTile() }
    }

    fun clear() = tiles.clear()

    private fun createTile(tx: Int, ty: Int): TileState {
        val left = tx * tileSize
        val top = ty * tileSize
        val width = minOf(tileSize, canvasWidth - left)
        val height = minOf(tileSize, canvasHeight - top)
        return TileState(
            left = left,
            top = top,
            width = width,
            height = height,
            alpha = FloatArray(width * height),
            color = IntArray(width * height),
        )
    }

    private fun TileState.toCompositedTile(): CompositedTile? {
        val out = IntArray(width * height)
        var any = false
        for (i in out.indices) {
            val a = alpha[i]
            if (a > 0f) {
                any = true
                out[i] = (a.coerceIn(0f, 1f) * 255f).roundToInt().shl(24) or color[i]
            }
        }
        if (!any) return null
        return CompositedTile(left, top, width, height, out)
    }

    private fun key(tx: Int, ty: Int): Long =
        (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
}
