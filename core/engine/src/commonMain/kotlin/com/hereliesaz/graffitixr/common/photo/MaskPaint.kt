package com.hereliesaz.graffitixr.common.photo

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Local tab's brush on an adjustment layer's mask: one byte per pixel, 255 = the layer's full
 * effect. A dab is a soft disc ([RetouchMath.falloff]); [flow] is how much of the way to full (or,
 * erasing, to empty) one dab goes, so passes build up as Lightroom's Flow does.
 */
object MaskPaint {

    /** One dab at ([x], [y]) of [radius] pixels. Returns the touched rectangle (l, t, r, b), or null. */
    @Suppress("LongParameterList")
    fun dab(mask: ByteArray, w: Int, h: Int, x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean): IntArray? {
        val r = radius.coerceAtLeast(0.5f)
        val l = max(0, (x - r).toInt())
        val t = max(0, (y - r).toInt())
        val rr = min(w - 1, (x + r).toInt() + 1)
        val b = min(h - 1, (y + r).toInt() + 1)
        if (l > rr || t > b) return null
        val k = flow.coerceIn(0f, 1f)
        for (py in t..b) for (px in l..rr) {
            val d = sqrt((px + 0.5f - x).let { it * it } + (py + 0.5f - y).let { it * it }) / r
            val f = RetouchMath.falloff(d, feather) * k
            if (f <= 0f) continue
            val i = py * w + px
            val m = (mask[i].toInt() and 0xFF) / 255f
            val next = if (erase) m * (1f - f) else m + (1f - m) * f
            mask[i] = (next * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        return intArrayOf(l, t, rr, b)
    }

    /** Dabs along the segment from ([x0], [y0]) to ([x1], [y1]), a quarter radius apart, the first end excluded. */
    @Suppress("LongParameterList")
    fun segment(mask: ByteArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, feather: Float, flow: Float, erase: Boolean) {
        val len = hypot(x1 - x0, y1 - y0)
        val step = max(1f, radius / 4f)
        val n = max(1, (len / step).toInt())
        for (i in 1..n) {
            val t = i / n.toFloat()
            dab(mask, w, h, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius, feather, flow, erase)
        }
    }
}
