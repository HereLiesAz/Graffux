// Pixel kernels take explicit coordinates and scales on purpose: no per-pixel allocation in the hot loop.
@file:Suppress("LongParameterList", "MagicNumber", "NestedBlockDepth")

package com.hereliesaz.graffitixr.ml

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The model's raw depth plane: [values] is [width] x [height], row-major, in the model's own polarity. */
class RawDepth(val values: FloatArray, val width: Int, val height: Int)

/** Runs the model on [input] (from [DepthPipeline.preprocess], laid out for [box]); returns the depth output. */
fun interface DepthModelRunner {
    fun run(input: FloatArray, box: Letterbox, spec: DepthModelSpec): RawDepth
}

/**
 * Where an image lands inside the model input ([inputWidth] x [inputHeight]): scaled by one factor
 * (aspect preserved) and centred for a fixed input, the rest padding; or filling a dynamic input
 * exactly (no padding). [contentWidth] x [contentHeight] is the image area at the model's
 * resolution, starting at ([padX], [padY]).
 */
data class Letterbox(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val padX: Int,
    val padY: Int,
) {
    companion object {
        fun compute(sourceWidth: Int, sourceHeight: Int, size: Int): Letterbox =
            compute(sourceWidth, sourceHeight, SizePolicy.Fixed(size, size))

        fun compute(sourceWidth: Int, sourceHeight: Int, policy: SizePolicy): Letterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source must have a positive size" }
            return when (policy) {
                is SizePolicy.Fixed -> fixed(sourceWidth, sourceHeight, policy)
                is SizePolicy.Dynamic -> dynamic(sourceWidth, sourceHeight, policy)
            }
        }

        private fun fixed(sw: Int, sh: Int, p: SizePolicy.Fixed): Letterbox {
            val scale = min(p.width.toDouble() / sw, p.height.toDouble() / sh)
            val cw = (sw * scale).roundToInt().coerceIn(1, p.width)
            val ch = (sh * scale).roundToInt().coerceIn(1, p.height)
            return Letterbox(sw, sh, p.width, p.height, cw, ch, (p.width - cw) / 2, (p.height - ch) / 2)
        }

        private fun dynamic(sw: Int, sh: Int, p: SizePolicy.Dynamic): Letterbox {
            val m = p.multipleOf
            val hi = (p.maxLongSide / m) * m
            val lo = min(ceil(p.minSide.toDouble() / m).toInt() * m, hi)
            val long = max(sw, sh)
            val scale = min(1.0, p.maxLongSide.toDouble() / long) // never upscale past the source
            val snap = { v: Double -> (((v / m).roundToInt()) * m).coerceIn(lo, hi) }
            val w = snap(sw * scale)
            val h = snap(sh * scale)
            return Letterbox(sw, sh, w, h, w, h, 0, 0)
        }
    }
}

/**
 * The model-agnostic half of depth estimation, free of `android.*` so the geometry and numerics are
 * unit-tested on the JVM. [com.hereliesaz.graffitixr.ml.OnnxDepthEstimator] feeds it bitmap pixels
 * and an ONNX [DepthModelRunner]. Everything model-specific comes from the [DepthModelSpec].
 */
object DepthPipeline {
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF
    private const val MAX_CHANNEL = 255f
    private const val NEAR_FLOOR_FRACTION = 1e-3f
    private const val NEAR_FLOOR_ABS = 1e-6f
    private const val ASPECT_TOLERANCE = 0.1

    /**
     * ARGB (non-premultiplied, `Bitmap.getPixels` order) -> the model's input tensor: colour order,
     * layout and normalisation per [spec], sized per [box]. Down-scaling averages the source footprint
     * of each output pixel (alpha-weighted); up-scaling is bilinear. Transparency is composited over
     * [DepthModelSpec.fill], and so is any padding, so alpha never reaches the model.
     */
    fun preprocess(pixels: IntArray, width: Int, height: Int, spec: DepthModelSpec, box: Letterbox): FloatArray {
        require(pixels.size >= width * height) { "pixels shorter than ${width}x$height" }
        val iw = box.inputWidth
        val plane = iw * box.inputHeight
        val channels = DepthModelSpec.CHANNELS
        val last = spec.layout == TensorLayout.NHWC
        val out = FloatArray(channels * plane)
        // Padding and fully transparent pixels are the fill colour, normalised like any other.
        for (c in 0 until channels) {
            val v = spec.normalization.apply(c, spec.fill)
            if (last) for (i in 0 until plane) out[i * channels + c] = v else out.fill(v, c * plane, (c + 1) * plane)
        }
        val scaleX = box.contentWidth.toDouble() / width
        val scaleY = box.contentHeight.toDouble() / height
        val rgba = FloatArray(RGBA)
        for (dy in 0 until box.contentHeight) {
            for (dx in 0 until box.contentWidth) {
                sample(pixels, width, height, dx, dy, scaleX, scaleY, rgba)
                val alpha = rgba[ALPHA_INDEX]
                val idx = (dy + box.padY) * iw + dx + box.padX
                for (c in 0 until channels) {
                    val src = if (spec.colorOrder == ColorOrder.RGB) c else channels - 1 - c
                    val v = rgba[src] * alpha + spec.fill * (1f - alpha)
                    out[if (last) idx * channels + c else c * plane + idx] = spec.normalization.apply(c, v)
                }
            }
        }
        return out
    }

    /**
     * Turns a raw output tensor of [shape] into a [RawDepth]. Accepts `[H,W]`, `[1,H,W]` (MiDaS,
     * Depth Anything V2) and `[1,1,H,W]` (Depth Anything V3's `[batch, views, H, W]`): every dimension
     * before the last two must be 1, since the host sends one image.
     *
     * @throws DepthEstimationException.UnexpectedOutput for any other shape or a length that disagrees with it.
     */
    fun rawFromShape(values: FloatArray, shape: LongArray): RawDepth {
        val ok = shape.size >= 2 && shape.dropLast(2).all { it == 1L } && shape.all { it > 0 }
        if (!ok) {
            throw DepthEstimationException.UnexpectedOutput(
                "Unsupported depth output shape ${shape.joinToString("x")}; expected [H,W] with only 1s before it",
            )
        }
        val h = shape[shape.size - 2].toInt()
        val w = shape[shape.size - 1].toInt()
        if (values.size != w * h) {
            throw DepthEstimationException.UnexpectedOutput("Depth output has ${values.size} samples for ${w}x$h")
        }
        return RawDepth(values, w, h)
    }

    /**
     * Crops [raw] to the image area of [box] (padding removed) and returns it as a [DepthMap] in the
     * nearer-is-larger convention, finite everywhere. If the model answered at a different resolution
     * than it was given (Depth Anything returns `14*floor(H/14)`), the crop is scaled proportionally.
     *
     * @throws DepthEstimationException.UnexpectedOutput if the output's aspect disagrees with the input's or holds
     * no finite sample.
     */
    fun postprocess(raw: RawDepth, box: Letterbox, spec: DepthModelSpec): DepthMap {
        val fx = raw.width.toDouble() / box.inputWidth
        val fy = raw.height.toDouble() / box.inputHeight
        if (abs(fx - fy) > ASPECT_TOLERANCE) {
            throw DepthEstimationException.UnexpectedOutput(
                "Expected about ${box.inputWidth}x${box.inputHeight} depth samples, " +
                    "the model produced ${raw.width}x${raw.height}",
            )
        }
        val x0 = (box.padX * fx).roundToInt().coerceIn(0, raw.width - 1)
        val y0 = (box.padY * fy).roundToInt().coerceIn(0, raw.height - 1)
        val cw = (box.contentWidth * fx).roundToInt().coerceIn(1, raw.width - x0)
        val ch = (box.contentHeight * fy).roundToInt().coerceIn(1, raw.height - y0)
        val out = FloatArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(raw.values, (y + y0) * raw.width + x0, out, y * cw, cw)
        return DepthMap(cw, ch, toNearerLarger(out, spec.higherIsNearer, spec.inversion))
    }

    /**
     * Brings model output into [DepthMap]'s convention (relative inverse depth, larger = nearer) with
     * every sample finite. A [higherIsNearer] output is kept; otherwise it is depth and is inverted:
     *  - [Inversion.RECIPROCAL]: `1 / max(d, floor)`, floor = `max(1e-3 * maxDepth, 1e-6)`, so zero,
     *    negative and tiny depths read as "very near" instead of dividing by zero.
     *  - [Inversion.REFLECT]: `min + max - d`, safe for values at or below zero.
     * NaN and +-Inf samples (and anything else non-finite) become the far plane.
     *
     * @throws DepthEstimationException.UnexpectedOutput if no sample is finite (e.g. a degenerate 14x14 V3 run).
     */
    fun toNearerLarger(values: FloatArray, higherIsNearer: Boolean, inversion: Inversion): FloatArray {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in values) if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v) }
        if (lo > hi) throw DepthEstimationException.UnexpectedOutput("The depth model produced no finite samples")
        if (higherIsNearer) return FloatArray(values.size) { if (values[it].isFinite()) values[it] else lo }
        return if (inversion == Inversion.REFLECT) {
            FloatArray(values.size) { if (values[it].isFinite()) lo + hi - values[it] else lo }
        } else {
            val floor = max(hi * NEAR_FLOOR_FRACTION, NEAR_FLOOR_ABS)
            val far = 1f / max(hi, floor)
            FloatArray(values.size) { if (values[it].isFinite()) 1f / max(values[it], floor) else far }
        }
    }

    /** The whole path for already-extracted pixels: place, preprocess, run, postprocess. */
    fun estimate(pixels: IntArray, width: Int, height: Int, spec: DepthModelSpec, runner: DepthModelRunner): DepthMap {
        val box = Letterbox.compute(width, height, spec.size)
        val input = preprocess(pixels, width, height, spec, box)
        return postprocess(runner.run(input, box, spec), box, spec)
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
