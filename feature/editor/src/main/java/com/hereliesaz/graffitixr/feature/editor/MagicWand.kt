package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Procreate's Automatic selection: the contiguous run of similarly-coloured pixels around a tap.
 *
 * Deliberately the same spread as [ImageProcessor.floodFill], the paint bucket — same per-channel
 * tolerance, same 4-connected scanline walk, and the same visited set. The two answer the same
 * question ("what counts as this colour, from here?") and a user who has learned where the bucket
 * stops has learned where the wand stops. They differ only in what they do with the answer: the
 * bucket writes pixels, this returns the region so it can be traced into a selection.
 */
internal object MagicWand {

    /**
     * The region around ([x], [y]) whose colour is within [tolerance] of the seed, per channel.
     *
     * Returns a mask the size of [bitmap], or null when the seed is off the bitmap. Alpha counts as
     * a channel, so tapping transparent space selects the transparent space rather than everything
     * that happens to share its RGB.
     */
    fun mask(bitmap: Bitmap, x: Int, y: Int, tolerance: Int = 32): BooleanArray? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        if (x !in 0 until w || y !in 0 until h) return null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val seed = pixels[y * w + x]

        fun matches(c: Int): Boolean {
            val da = abs(((c ushr 24) and 0xFF) - ((seed ushr 24) and 0xFF))
            val dr = abs(((c ushr 16) and 0xFF) - ((seed ushr 16) and 0xFF))
            val dg = abs(((c ushr 8) and 0xFF) - ((seed ushr 8) and 0xFF))
            val db = abs((c and 0xFF) - (seed and 0xFF))
            return da <= tolerance && dr <= tolerance && dg <= tolerance && db <= tolerance
        }

        val out = BooleanArray(w * h)
        // Scanline flood, mirroring the bucket's. `out` doubles as the visited set, which is why a
        // pixel is tested against it as well as against the colour — without that the run-walk
        // re-enters spans it has already claimed and the stack grows without bound.
        fun open(px: Int, py: Int): Boolean = !out[py * w + px] && matches(pixels[py * w + px])

        val stack = ArrayDeque<Int>()
        stack.addLast(y * w + x)
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            val py = idx / w
            var left = idx % w
            if (!open(left, py)) continue
            while (left > 0 && open(left - 1, py)) left--
            var spanUp = false
            var spanDown = false
            var i = left
            while (i < w && open(i, py)) {
                out[py * w + i] = true
                if (py > 0) {
                    val up = open(i, py - 1)
                    if (up && !spanUp) { stack.addLast((py - 1) * w + i); spanUp = true }
                    else if (!up) spanUp = false
                }
                if (py < h - 1) {
                    val down = open(i, py + 1)
                    if (down && !spanDown) { stack.addLast((py + 1) * w + i); spanDown = true }
                    else if (!down) spanDown = false
                }
                i++
            }
        }
        return out
    }
}
