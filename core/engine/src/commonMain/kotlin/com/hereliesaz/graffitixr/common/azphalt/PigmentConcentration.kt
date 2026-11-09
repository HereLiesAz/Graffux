package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.floor

/**
 * Recipe-preserving concentration storage (spec/painterly.md 6.2 item 4). A palette-board texel holds
 * up to [BOARD_TUBES] tube concentrations as one byte each, always summing to exactly [SUM], rounded by
 * a deterministic largest-remainder rule (ties to the lower slot) that the WGSL port repeats.
 */
object PigmentConcentration {
    const val BOARD_TUBES = 12
    const val SUM = 255

    /** [concentration] (any non-negative scale) as bytes summing to [SUM]; all zero stays all zero. */
    fun quantize(concentration: FloatArray): IntArray {
        val n = concentration.size
        val out = IntArray(n)
        var total = 0f
        for (c in concentration) total += if (c.isFinite() && c > 0f) c else 0f
        if (!(total > 0f)) return out
        val remainder = FloatArray(n)
        var used = 0
        for (i in 0 until n) {
            val c = concentration[i]
            val scaled = (if (c.isFinite() && c > 0f) c else 0f) / total * SUM
            val whole = floor(scaled).toInt().coerceIn(0, SUM)
            out[i] = whole
            remainder[i] = scaled - whole
            used += whole
        }
        var left = SUM - used
        while (left > 0) {
            var best = 0
            for (i in 1 until n) if (remainder[i] > remainder[best]) best = i
            out[best]++
            remainder[best] = -1f
            left--
        }
        return out
    }

    fun dequantize(bytes: IntArray): FloatArray = FloatArray(bytes.size) { bytes[it] / SUM.toFloat() }
}
