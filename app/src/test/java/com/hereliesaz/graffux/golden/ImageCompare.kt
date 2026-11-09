@file:Suppress("MagicNumber")

package com.hereliesaz.graffux.golden

import kotlin.math.abs
import kotlin.math.max

/** How two same-sized or differently sized ARGB images differ. */
data class ImageDiff(
    val expectedWidth: Int,
    val expectedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    /** Pixels whose largest per-channel difference exceeds the tolerance (0 when the sizes differ). */
    val differingPixels: Int,
    /** Largest single-channel difference seen anywhere (0 when the sizes differ). */
    val maxChannelDelta: Int,
) {
    val sizeMismatch: Boolean get() = expectedWidth != actualWidth || expectedHeight != actualHeight
    val totalPixels: Int get() = expectedWidth * expectedHeight
    val differingRatio: Double get() = if (totalPixels == 0) 0.0 else differingPixels.toDouble() / totalPixels

    /** True when the images are the same size and at most [maxRatio] of their pixels differ. */
    fun matches(maxRatio: Double): Boolean = !sizeMismatch && differingRatio <= maxRatio

    fun describe(): String =
        if (sizeMismatch) {
            "size ${actualWidth}x$actualHeight, golden is ${expectedWidth}x$expectedHeight"
        } else {
            "$differingPixels of $totalPixels pixels differ (%.4f%%), largest channel delta $maxChannelDelta"
                .format(differingRatio * 100)
        }
}

/** An ARGB image as a flat pixel array, row by row. */
class Raster(val pixels: IntArray, val width: Int, val height: Int) {
    init {
        require(pixels.size == width * height) { "pixel array does not match ${width}x$height" }
    }
}

/**
 * Pixel comparison on plain ARGB int arrays, so it runs (and is tested) on the JVM without Android.
 * A pixel differs when any of its four channels differs by more than [channelTolerance]; a small
 * tolerance absorbs anti-aliasing noise between runs without hiding a real change.
 */
object ImageCompare {
    fun compare(expected: Raster, actual: Raster, channelTolerance: Int = 2): ImageDiff {
        if (expected.width != actual.width || expected.height != actual.height) {
            return ImageDiff(expected.width, expected.height, actual.width, actual.height, 0, 0)
        }
        var differing = 0
        var maxDelta = 0
        for (i in expected.pixels.indices) {
            val delta = pixelDelta(expected.pixels[i], actual.pixels[i])
            maxDelta = max(maxDelta, delta)
            if (delta > channelTolerance) differing++
        }
        return ImageDiff(expected.width, expected.height, actual.width, actual.height, differing, maxDelta)
    }

    /** Largest difference between any one channel of two ARGB pixels. */
    fun pixelDelta(a: Int, b: Int): Int {
        var delta = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            delta = max(delta, abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)))
        }
        return delta
    }

    /** The golden dimmed, with every differing pixel solid red, for a human to look at. */
    fun diffImage(expected: IntArray, actual: IntArray, channelTolerance: Int = 2): IntArray =
        IntArray(expected.size) { i ->
            if (pixelDelta(expected[i], actual[i]) > channelTolerance) {
                0xFFFF0000.toInt()
            } else {
                val p = expected[i]
                val r = ((p shr 16) and 0xFF) / 3
                val g = ((p shr 8) and 0xFF) / 3
                val b = (p and 0xFF) / 3
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
}
