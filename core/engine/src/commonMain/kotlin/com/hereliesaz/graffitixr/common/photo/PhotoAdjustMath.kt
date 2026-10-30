package com.hereliesaz.graffitixr.common.photo

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The adjustment formulas, on the CPU: the reference the GPU shader
 * (core/wgpu-engine/src/shaders/adjust.wgsl) follows step for step, and the fallback where there is
 * no GPU (export on a device without wgpu, unit tests, a desktop without an adapter). Values are
 * sRGB-encoded 0..1 unless a name says linear. [p] is a [PhotoAdjustParams] block.
 *
 * Pipeline, in order: white balance and exposure in linear light; blacks/whites; shadows and
 * highlights; contrast; Detail (local contrast against blurred luma); dehaze; curves; levels; HSL;
 * vibrance and saturation; the effects (sketch, then outline); vignette; grain; then the mask.
 */
object PhotoAdjustMath {

    fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun toSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * max(c, 0f).pow(1f / 2.4f) - 0.055f

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Exposure [ev] stops in linear light, back to sRGB. */
    fun exposure(c: Float, ev: Float, wb: Float = 1f): Float =
        toSrgb((toLinear(c) * wb * 2f.pow(ev)).coerceIn(0f, 1f))

    /**
     * The tone curve for one channel: blacks and whites move the end points by up to 15%, shadows
     * and highlights lift or pull the lower and upper thirds (x(1−x)² and x²(1−x), peaking at ⅓
     * and ⅔), contrast blends toward a smoothstep S (or away from it, negative).
     */
    fun tone(x0: Float, contrast: Float, highlights: Float, shadows: Float, whites: Float, blacks: Float): Float {
        val bk = -blacks * 0.15f
        val wh = 1f - whites * 0.15f
        var x = ((x0 - bk) / (wh - bk)).coerceIn(0f, 1f)
        val inv = 1f - x
        x = (x + 0.9f * shadows * x * inv * inv + 0.9f * highlights * x * x * inv).coerceIn(0f, 1f)
        val s = x * x * (3f - 2f * x)
        return (x + contrast * (s - x)).coerceIn(0f, 1f)
    }

    /** Levels: [black]..[white] (0..1) stretched to the full range, then the midpoint [gamma]. */
    fun levels(c: Float, black: Float, gamma: Float, white: Float): Float =
        ((c - black) / max(white - black, 1e-4f)).coerceIn(0f, 1f).pow(1f / max(gamma, 0.01f))

    /** RGB (0..1) to hue degrees, saturation, lightness. */
    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) * 0.5f
        val d = mx - mn
        if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
        val s = d / max(1f - abs(2f * l - 1f), 1e-6f)
        val h = when (mx) {
            r -> ((g - b) / d).let { if (it < 0f) it + 6f else it }
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return floatArrayOf(h * 60f, s.coerceIn(0f, 1f), l)
    }

    fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        val a = s * min(l, 1f - l)
        fun f(n: Float): Float {
            val k = (n + h / 30f) % 12f
            return l - a * min(k - 3f, 9f - k).coerceIn(-1f, 1f)
        }
        return floatArrayOf(f(0f), f(8f), f(4f))
    }

    /** How much hue [h] belongs to [range]: 1 at its centre, falling linearly to 0 at its neighbours'. */
    fun rangeWeight(h: Float, range: Int): Float {
        val centres = HueRange.entries
        val c = centres[range].centerDegrees
        val prev = centres[(range + 7) % 8].centerDegrees
        val next = centres[(range + 1) % 8].centerDegrees
        var d = h - c
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        var span = if (d >= 0f) next - c else c - prev
        if (span <= 0f) span += 360f
        return max(0f, 1f - abs(d) / span)
    }

    /** One pixel, unpremultiplied RGB in [c] (changed in place), at ([x], [y]) of a [w]×[h] image. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
    fun adjustPixel(
        c: FloatArray,
        p: FloatArray,
        curves: FloatArray?,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        srcLuma: Float = 0f,
        blurSmall: Float = 0f,
        blurLarge: Float = 0f,
        line: Float = 0f,
    ) {
        for (k in 0..2) c[k] = tone(exposure(c[k], p[0], p[8 + k]), p[1], p[2], p[3], p[4], p[5])
        if (p[PhotoAdjustParams.DETAIL_ON] > 0.5f) {
            var dy = 0f
            dy += p[25] * 0.6f * (srcLuma - blurLarge)
            dy += p[26] * 0.8f * (srcLuma - blurSmall)
            dy += p[27] * 1.5f * (srcLuma - blurSmall)
            dy += p[28] * (blurSmall - srcLuma)
            for (k in 0..2) c[k] = (c[k] + dy).coerceIn(0f, 1f)
        }
        if (p[18] != 0f) {
            val k0 = p[18] * 0.12f
            for (k in 0..2) c[k] = ((c[k] - k0) / (1f - k0)).coerceIn(0f, 1f)
        }
        if (p[15] > 0.5f && curves != null) {
            for (k in 0..2) {
                val m = curves[(c[k].coerceIn(0f, 1f) * 255f).roundToInt()]
                c[k] = curves[256 * (k + 1) + (m.coerceIn(0f, 1f) * 255f).roundToInt()]
            }
        }
        for (k in 0..2) c[k] = levels(c[k], p[12], p[13], p[14])
        val hsl = rgbToHsl(c[0], c[1], c[2])
        if (hsl[1] >= 1e-4f) {
            var dh = 0f
            var ds = 0f
            var dl = 0f
            for (r in 0 until 8) {
                val wgt = rangeWeight(hsl[0], r)
                if (wgt > 0f) {
                    dh += wgt * p[32 + 3 * r]
                    ds += wgt * p[33 + 3 * r]
                    dl += wgt * p[34 + 3 * r]
                }
            }
            if (dh != 0f || ds != 0f || dl != 0f) {
                val sat = hsl[1]
                val out = hslToRgb(
                    (hsl[0] + dh * 30f + 360f) % 360f,
                    (hsl[1] * (1f + ds)).coerceIn(0f, 1f),
                    (hsl[2] + dl * 0.25f * sat).coerceIn(0f, 1f),
                )
                out.copyInto(c)
            }
        }
        val yy = luma(c[0], c[1], c[2])
        val sat = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))
        val f = (1f + p[6] * (1f - sat)) * (1f + p[7]) * (1f + max(p[18], 0f) * 0.2f)
        for (k in 0..2) c[k] = (yy + (c[k] - yy) * f).coerceIn(0f, 1f)
        if (p[PhotoAdjustParams.EFFECTS_ON] > 0.5f) {
            if (p[59] > 0f) {
                val s = sketch(srcLuma, blurSmall, blurLarge, p[60], p[61])
                for (k in 0..2) c[k] = c[k] + (s - c[k]) * p[59]
            }
            if (p[56] > 0f) {
                for (k in 0..2) {
                    c[k] = c[k] + (1f - c[k]) * (p[58] * p[56])
                    c[k] = c[k] * (1f - p[56] * line)
                }
            }
        }
        if (p[20] != 0f) {
            val u = ((x + 0.5f) / w) * 2f - 1f
            val v = ((y + 0.5f) / h) * 2f - 1f
            val r = sqrt(u * u + v * v) / 1.41421356f
            val mid = p[21]
            val t = smoothstep(mid, vignetteEdge(mid, p[22]), r)
            for (k in 0..2) c[k] = (c[k] * (1f + p[20] * t)).coerceIn(0f, 1f)
        }
        if (p[23] > 0f) {
            val size = max(p[24].toInt(), 1)
            val seed = p[19].toInt()
            val n = (hash((x / size) * 73856093 xor (y / size) * 19349663 xor seed) and 0xFFFF) / 65535f - 0.5f
            for (k in 0..2) c[k] = (c[k] + n * p[23] * 0.25f).coerceIn(0f, 1f)
        }
    }

    /**
     * Where the vignette's falloff ends, always strictly past [mid]. At the midpoint slider's
     * maximum (mid = 1) the plain formula gives edge == mid, and `smoothstep(1, 1, r)` is undefined
     * in WGSL (and a division by zero here). adjust.wgsl applies the same floor.
     */
    fun vignetteEdge(mid: Float, feather: Float): Float =
        max(mid + max(feather, 0.01f) * (1f - mid), mid + VIGNETTE_MIN_SPAN)

    /** The smallest vignette falloff width; keep in step with adjust.wgsl. */
    const val VIGNETTE_MIN_SPAN = 1e-4f

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The shader's PCG-style hash, on 32-bit unsigned arithmetic. */
    fun hash(x: Int): Int {
        val v = x * 747796405 + -1403630843 // 2891336453 as a signed Int
        val w = ((v ushr ((v ushr 28) + 4)) xor v) * 277803737
        return (w ushr 22) xor w
    }

    /**
     * The whole layer: [src] premultiplied RGBA8 ([w]×[h]) to [out], times [mask] (one byte per
     * pixel) when the block's mask is on. The CPU path: used only where the GPU is missing.
     */
    @Suppress("LongParameterList")
    fun render(src: ByteArray, w: Int, h: Int, p: FloatArray, curves: FloatArray?, mask: ByteArray?, out: ByteArray) {
        val n = w * h
        val detail = p[PhotoAdjustParams.DETAIL_ON] > 0.5f || p[PhotoAdjustParams.EFFECTS_ON] > 0.5f
        val lum = if (detail) FloatArray(n) { i -> pixelLuma(src, i) } else null
        val smallRadius = smallBlurRadius(w, h)
        val (small, large) = if (lum != null) {
            boxBlur(lum, w, h, smallRadius) to boxBlur(lum, w, h, (max(w, h) / 40).coerceIn(4, 64))
        } else {
            null to null
        }
        val lines = if (small != null && p[PhotoAdjustParams.EFFECTS_ON] > 0.5f && p[56] > 0f) {
            cannyOutline(edgePlane(small, w, h, smallRadius), w, h, p[57])
        } else {
            null
        }
        val c = FloatArray(3)
        for (i in 0 until n) {
            val a0 = (src[i * 4 + 3].toInt() and 0xFF) / 255f
            if (a0 <= 0f) {
                for (k in 0..3) out[i * 4 + k] = 0
                continue
            }
            for (k in 0..2) c[k] = (src[i * 4 + k].toInt() and 0xFF) / 255f / a0
            adjustPixel(
                c, p, curves, i % w, i / w, w, h,
                lum?.get(i) ?: 0f, small?.get(i) ?: 0f, large?.get(i) ?: 0f, lines?.get(i) ?: 0f,
            )
            var m = 1f
            if (p[11] > 0.5f && mask != null) {
                m = (mask[i].toInt() and 0xFF) / 255f
                if (p[30] > 0.5f) m = 1f - m
            }
            val a = a0 * m
            for (k in 0..2) out[i * 4 + k] = ((c[k] * a).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            out[i * 4 + 3] = (a.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
    }

    /** The Detail blur's small radius at [w]×[h]: ~1/400 of the long edge (the shader's rule). */
    fun smallBlurRadius(w: Int, h: Int): Int = (max(w, h) / 400).coerceIn(1, 8)

    /**
     * Sketch: luma [y] colour-dodged by its blur (a box blur blended from the small to the large
     * radius by [softness]), so flat areas go white and edges leave pencil strokes; [darkness]
     * (0..1) raises it to a power up to 5, deepening the strokes.
     */
    fun sketch(y: Float, blurSmall: Float, blurLarge: Float, softness: Float, darkness: Float): Float {
        val b = blurSmall + (blurLarge - blurSmall) * softness
        return (y / max(b, 1e-3f)).coerceIn(0f, 1f).pow(1f + 4f * darkness)
    }

    /**
     * The Sobel gradient of [small] (a box blur of radius [r]) at ([x], [y]), edges clamped:
     * direction quantised to 0 horizontal, 1 down-right, 2 vertical, 3 down-left, plus a magnitude
     * in the fraction, scaled so a full black-to-white step reads 1 and capped at 0.999.
     */
    @Suppress("LongParameterList")
    fun edgeCode(small: FloatArray, w: Int, h: Int, r: Int, x: Int, y: Int): Float {
        fun at(xx: Int, yy: Int) = small[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
        val gx = (at(x + 1, y - 1) + 2f * at(x + 1, y) + at(x + 1, y + 1)) -
            (at(x - 1, y - 1) + 2f * at(x - 1, y) + at(x - 1, y + 1))
        val gy = (at(x - 1, y + 1) + 2f * at(x, y + 1) + at(x + 1, y + 1)) -
            (at(x - 1, y - 1) + 2f * at(x, y - 1) + at(x + 1, y - 1))
        val mag = min(sqrt(gx * gx + gy * gy) * (2 * r + 1).toFloat() / 8f, 0.999f)
        val ax = abs(gx)
        val ay = abs(gy)
        val dir = when {
            ay <= ax * TAN_22_5 -> 0f
            ax <= ay * TAN_22_5 -> 2f
            (gx > 0f) == (gy > 0f) -> 1f
            else -> 3f
        }
        return dir + mag
    }

    /** [edgeCode] for every pixel: the plane the GPU precomputes once per source upload. */
    fun edgePlane(small: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        FloatArray(w * h) { i -> edgeCode(small, w, h, r, i % w, i / w) }

    /**
     * Canny lines from an [edgePlane]: non-maximum suppression along each gradient's direction,
     * a double threshold ([high], and half of it), and one step of hysteresis (a weak edge
     * survives beside a strong one). 1 on a line, 0 elsewhere.
     */
    fun cannyOutline(edges: FloatArray, w: Int, h: Int, high: Float): FloatArray {
        fun mag(x: Int, y: Int): Float {
            val e = edges[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            return e - floor(e)
        }
        val low = high * 0.5f
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val e = edges[i]
            val m = e - floor(e)
            val (ox, oy) = when (floor(e).toInt()) {
                1 -> 1 to 1
                2 -> 0 to 1
                3 -> -1 to 1
                else -> 1 to 0
            }
            when {
                m < low || m <= 0f -> 0f
                m < mag(x + ox, y + oy) || m < mag(x - ox, y - oy) -> 0f
                m >= high -> 1f
                (-1..1).any { dy -> (-1..1).any { dx -> (dx != 0 || dy != 0) && mag(x + dx, y + dy) >= high } } -> 1f
                else -> 0f
            }
        }
    }

    private const val TAN_22_5 = 0.41421356f

    private fun pixelLuma(src: ByteArray, i: Int): Float {
        val a = (src[i * 4 + 3].toInt() and 0xFF) / 255f
        if (a <= 0f) return 0f
        return luma(
            (src[i * 4].toInt() and 0xFF) / 255f / a,
            (src[i * 4 + 1].toInt() and 0xFF) / 255f / a,
            (src[i * 4 + 2].toInt() and 0xFF) / 255f / a,
        )
    }

    /** Separable box blur of radius [r], edges averaged over what is inside (the shader's rule). */
    fun boxBlur(plane: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(plane.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (k in -r..r) {
                val xx = x + k
                if (xx in 0 until w) { s += plane[y * w + xx]; n++ }
            }
            tmp[y * w + x] = s / n
        }
        val out = FloatArray(plane.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (k in -r..r) {
                val yy = y + k
                if (yy in 0 until h) { s += tmp[yy * w + x]; n++ }
            }
            out[y * w + x] = s / n
        }
        return out
    }
}
