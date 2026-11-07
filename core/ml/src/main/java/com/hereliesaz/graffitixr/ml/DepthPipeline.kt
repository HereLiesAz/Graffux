// Pixel kernels take explicit coordinates and scales on purpose: no per-pixel allocation in the hot loop.
@file:Suppress("LongParameterList", "MagicNumber")

package com.hereliesaz.graffitixr.ml

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a depth model expects. Defaults describe the MiDaS v2.1 small ONNX: a fixed square 256 px
 * `input_image` in NCHW RGB, channel values in [0,1], and relative inverse depth out.
 *
 * [mean]/[std] are applied after scaling to [0,1] (`(v - mean) / std`); the identity default matches
 * the [0,1] contract. A listing whose `io` descriptor asks for ImageNet statistics changes them here.
 */
data class DepthModelSpec(
    val inputSize: Int = MIDAS_SMALL_INPUT,
    val mean: FloatArray = floatArrayOf(0f, 0f, 0f),
    val std: FloatArray = floatArrayOf(1f, 1f, 1f),
    /** Grey level (0..1) transparent and padded pixels are composited onto before inference. */
    val fill: Float = 0.5f,
) {
    init {
        require(inputSize > 0) { "inputSize must be positive" }
        require(mean.size == CHANNELS && std.size == CHANNELS) { "mean and std need one entry per RGB channel" }
        require(std.all { it != 0f }) { "std must be non-zero" }
    }

    /** Samples in one input tensor (3 x size x size). */
    val tensorSize: Int get() = CHANNELS * inputSize * inputSize

    companion object {
        const val MIDAS_SMALL_INPUT = 256
        const val CHANNELS = 3
    }
}

/** Runs the model: [input] is the CHW tensor from [DepthPipeline.preprocess]; returns the raw output samples. */
fun interface DepthModelRunner {
    fun run(input: FloatArray, spec: DepthModelSpec): FloatArray
}

/**
 * Where an image lands inside the square model input: scaled by one factor (aspect preserved) and
 * centred, the rest padding. [contentWidth] x [contentHeight] is the image area at the model's
 * resolution, starting at ([padX], [padY]).
 */
data class Letterbox(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val size: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val padX: Int,
    val padY: Int,
) {
    companion object {
        fun compute(sourceWidth: Int, sourceHeight: Int, size: Int): Letterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source must have a positive size" }
            val scale = size.toDouble() / max(sourceWidth, sourceHeight)
            val cw = (sourceWidth * scale).roundToInt().coerceIn(1, size)
            val ch = (sourceHeight * scale).roundToInt().coerceIn(1, size)
            return Letterbox(sourceWidth, sourceHeight, size, cw, ch, (size - cw) / 2, (size - ch) / 2)
        }
    }
}

/**
 * The model-agnostic half of depth estimation, free of `android.*` so the geometry and numerics are
 * unit-tested on the JVM. [com.hereliesaz.graffitixr.ml.OnnxDepthEstimator] feeds it bitmap pixels
 * and an ONNX [DepthModelRunner].
 */
object DepthPipeline {
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF
    private const val MAX_CHANNEL = 255f

    /**
     * ARGB (non-premultiplied, `Bitmap.getPixels` order) -> CHW float tensor, RGB, scaled to [0,1]
     * then mean/std normalised. The image is letterboxed (never stretched). Down-scaling averages the
     * source footprint of each output pixel (alpha-weighted); up-scaling is bilinear. Transparency is
     * composited over [DepthModelSpec.fill], and so is the padding, so alpha never reaches the model.
     */
    fun preprocess(pixels: IntArray, width: Int, height: Int, spec: DepthModelSpec): FloatArray {
        require(pixels.size >= width * height) { "pixels shorter than ${width}x$height" }
        val box = Letterbox.compute(width, height, spec.inputSize)
        val size = spec.inputSize
        val plane = size * size
        val out = FloatArray(spec.tensorSize)
        // Padding and fully transparent pixels are the fill colour, normalised like any other.
        for (c in 0 until DepthModelSpec.CHANNELS) {
            out.fill((spec.fill - spec.mean[c]) / spec.std[c], c * plane, (c + 1) * plane)
        }
        val scaleX = box.contentWidth.toDouble() / width
        val scaleY = box.contentHeight.toDouble() / height
        val rgba = FloatArray(RGBA)
        for (dy in 0 until box.contentHeight) {
            for (dx in 0 until box.contentWidth) {
                sample(pixels, width, height, dx, dy, scaleX, scaleY, rgba)
                val alpha = rgba[ALPHA_INDEX]
                val idx = (dy + box.padY) * size + dx + box.padX
                for (c in 0 until DepthModelSpec.CHANNELS) {
                    val v = rgba[c] * alpha + spec.fill * (1f - alpha)
                    out[c * plane + idx] = (v - spec.mean[c]) / spec.std[c]
                }
            }
        }
        return out
    }

    /**
     * Crops the model output (a size x size grid, optionally with a leading 1/channel dim already
     * flattened) to the image area and returns it as a [DepthMap] of [Letterbox.contentWidth] x
     * [Letterbox.contentHeight]: padding is removed, so the map has the source's aspect ratio.
     *
     * @throws DepthEstimationException.UnexpectedOutput if [output] is not exactly one size x size plane.
     */
    fun postprocess(output: FloatArray, box: Letterbox): DepthMap {
        val size = box.size
        if (output.size != size * size) {
            throw DepthEstimationException.UnexpectedOutput(
                "Expected ${size * size} depth samples (${size}x$size), the model produced ${output.size}",
            )
        }
        val out = FloatArray(box.contentWidth * box.contentHeight)
        for (y in 0 until box.contentHeight) {
            System.arraycopy(output, (y + box.padY) * size + box.padX, out, y * box.contentWidth, box.contentWidth)
        }
        return DepthMap(box.contentWidth, box.contentHeight, out)
    }

    /** The whole path for already-extracted pixels: preprocess, run, postprocess. */
    fun estimate(pixels: IntArray, width: Int, height: Int, spec: DepthModelSpec, runner: DepthModelRunner): DepthMap {
        val box = Letterbox.compute(width, height, spec.inputSize)
        val input = preprocess(pixels, width, height, spec)
        return postprocess(runner.run(input, spec), box)
    }

    /** One output pixel's alpha-weighted colour (r,g,b in 0..1 *not* premultiplied) and mean alpha into [out]. */
    private fun sample(
        px: IntArray, w: Int, h: Int, dx: Int, dy: Int, scaleX: Double, scaleY: Double, out: FloatArray,
    ) {
        if (scaleX >= 1.0 || scaleY >= 1.0) {
            bilinear(px, w, h, dx, dy, scaleX, scaleY, out)
        } else {
            box(px, w, h, dx, dy, scaleX, scaleY, out)
        }
    }

    private fun box(px: IntArray, w: Int, h: Int, dx: Int, dy: Int, scaleX: Double, scaleY: Double, out: FloatArray) {
        val x0 = floor(dx / scaleX).toInt().coerceIn(0, w - 1)
        val x1 = max(x0 + 1, ceil((dx + 1) / scaleX).toInt()).coerceAtMost(w)
        val y0 = floor(dy / scaleY).toInt().coerceIn(0, h - 1)
        val y1 = max(y0 + 1, ceil((dy + 1) / scaleY).toInt()).coerceAtMost(h)
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = px[y * w + x]
                val pa = ((p ushr ALPHA_SHIFT) and BYTE_MASK) / MAX_CHANNEL
                r += ((p shr RED_SHIFT) and BYTE_MASK) * pa
                g += ((p shr GREEN_SHIFT) and BYTE_MASK) * pa
                b += (p and BYTE_MASK) * pa
                a += pa
            }
        }
        val n = (x1 - x0) * (y1 - y0)
        finish(r, g, b, a, n, out)
    }

    private fun bilinear(
        px: IntArray, w: Int, h: Int, dx: Int, dy: Int, scaleX: Double, scaleY: Double, out: FloatArray,
    ) {
        val fx = ((dx + 0.5) / scaleX - 0.5).coerceIn(0.0, (w - 1).toDouble())
        val fy = ((dy + 0.5) / scaleY - 0.5).coerceIn(0.0, (h - 1).toDouble())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
        for (corner in 0 until CORNERS) {
            val cx = if (corner and 1 == 0) x0 else x1
            val cy = if (corner and 2 == 0) y0 else y1
            val wgt = (if (corner and 1 == 0) 1 - tx else tx) * (if (corner and 2 == 0) 1 - ty else ty)
            val p = px[cy * w + cx]
            val pa = ((p ushr ALPHA_SHIFT) and BYTE_MASK) / MAX_CHANNEL
            r += ((p shr RED_SHIFT) and BYTE_MASK) * pa * wgt
            g += ((p shr GREEN_SHIFT) and BYTE_MASK) * pa * wgt
            b += (p and BYTE_MASK) * pa * wgt
            a += pa * wgt
        }
        finish(r, g, b, a, 1, out)
    }

    /** Premultiplied sums over [n] samples -> straight colour 0..1 and mean alpha. */
    private fun finish(r: Float, g: Float, b: Float, a: Float, n: Int, out: FloatArray) {
        if (a <= 0f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            out[ALPHA_INDEX] = 0f
            return
        }
        out[0] = r / a / MAX_CHANNEL
        out[1] = g / a / MAX_CHANNEL
        out[2] = b / a / MAX_CHANNEL
        out[ALPHA_INDEX] = (a / n).coerceIn(0f, 1f)
    }

    private const val RGBA = 4
    private const val ALPHA_INDEX = 3
    private const val CORNERS = 4
}
