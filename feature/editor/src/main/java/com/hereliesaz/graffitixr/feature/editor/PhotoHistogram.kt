@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import kotlin.math.sqrt

/** Bins in the Curves graph's histogram. */
internal const val HISTOGRAM_BINS = 64

/** Every this many pixels on each axis: the histogram's shape, at a sixteenth of the reads. */
private const val HISTOGRAM_STRIDE = 4

/**
 * The luma histogram of [bitmap] (Rec. 709 on its sRGB values, transparent pixels skipped):
 * [HISTOGRAM_BINS] bins, square-rooted and scaled so the fullest is 1. The root keeps a sky full of
 * one tone from flattening every other bin to nothing.
 */
internal fun lumaHistogram(bitmap: Bitmap): FloatArray {
    val counts = FloatArray(HISTOGRAM_BINS)
    val w = bitmap.width
    val row = IntArray(w)
    for (y in 0 until bitmap.height step HISTOGRAM_STRIDE) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w step HISTOGRAM_STRIDE) {
            val c = row[x]
            if ((c ushr 24) == 0) continue
            val l = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
            counts[(l * (HISTOGRAM_BINS - 1)).toInt().coerceIn(0, HISTOGRAM_BINS - 1)] += 1f
        }
    }
    val max = counts.maxOrNull()?.takeIf { it > 0f } ?: return counts
    return FloatArray(HISTOGRAM_BINS) { sqrt(counts[it] / max) }
}
