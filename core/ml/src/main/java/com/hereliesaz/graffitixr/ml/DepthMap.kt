@file:Suppress("MagicNumber") // 0.5f pixel-centre offsets

package com.hereliesaz.graffitixr.ml

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A dense single-channel depth map, row-major, [width] x [height] samples in [values].
 *
 * Semantics: **relative inverse depth** (what MiDaS and Depth Anything V2 emit; models that emit depth, like V3, are
 * inverted on the way in). A larger value is *nearer* to
 * the camera. The scale and shift are arbitrary and differ from image to image, so a raw value means
 * nothing in metres and two maps are not comparable until each is passed through [normalized].
 */
class DepthMap(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0) { "A depth map needs a positive size, got ${width}x$height" }
        require(values.size == width * height) {
            "Expected ${width * height} samples for ${width}x$height, got ${values.size}"
        }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /**
     * Rescaled so the nearest sample is 1 and the farthest is 0 (min-max). A flat map (no range to
     * stretch) becomes all zeros rather than dividing by zero, and non-finite samples are treated as
     * the far plane.
     */
    fun normalized(): DepthMap {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isFinite()) {
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        val range = hi - lo
        val out = FloatArray(values.size)
        if (range.isFinite() && range > 0f) {
            for (i in values.indices) {
                val v = values[i]
                out[i] = if (v.isFinite()) ((v - lo) / range).coerceIn(0f, 1f) else 0f
            }
        }
        return DepthMap(width, height, out)
    }

    /**
     * Bilinear resample to [targetWidth] x [targetHeight], pixel-centre aligned (so a 2x2 map
     * upsampled keeps its corner values at the corner pixel centres and a same-size resample is the
     * identity). Use it after [normalized] to bring a model-resolution map up to a layer's pixels.
     */
    fun resampledTo(targetWidth: Int, targetHeight: Int): DepthMap {
        require(targetWidth > 0 && targetHeight > 0) { "Target must have a positive size" }
        if (targetWidth == width && targetHeight == height) return this
        val out = FloatArray(targetWidth * targetHeight)
        val sx = width.toFloat() / targetWidth
        val sy = height.toFloat() / targetHeight
        for (y in 0 until targetHeight) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, height - 1)
            val ty = fy - y0
            for (x in 0 until targetWidth) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = floor(fx).toInt()
                val x1 = min(x0 + 1, width - 1)
                val tx = fx - x0
                val top = values[y0 * width + x0] * (1 - tx) + values[y0 * width + x1] * tx
                val bottom = values[y1 * width + x0] * (1 - tx) + values[y1 * width + x1] * tx
                out[y * targetWidth + x] = top * (1 - ty) + bottom * ty
            }
        }
        return DepthMap(targetWidth, targetHeight, out)
    }

    /**
     * Opaque ARGB grey ramp of a *normalized* map (near = white), for previews and tests. The map is
     * clamped to 0..1 first, so passing a raw map gives a saturated image rather than garbage.
     */
    fun toGrayscaleArgb(): IntArray = IntArray(values.size) { i ->
        val g = (values[i].coerceIn(0f, 1f) * MAX_CHANNEL + 0.5f).toInt()
        (OPAQUE shl ALPHA_SHIFT) or (g shl RED_SHIFT) or (g shl GREEN_SHIFT) or g
    }

    private companion object {
        const val MAX_CHANNEL = 255f
        const val OPAQUE = 0xFF
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
    }
}
