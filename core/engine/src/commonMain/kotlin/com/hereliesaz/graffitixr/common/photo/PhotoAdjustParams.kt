package com.hereliesaz.graffitixr.common.photo

import kotlin.math.ln
import kotlin.math.pow

/**
 * Packs a [PhotoAdjustment] into the GPU's parameter block (`Params` in
 * core/wgpu-engine/src/shaders/adjust.wgsl, [FLOATS] floats) and its curves table. The CPU
 * reference ([PhotoAdjustMath]) reads the same block, so the two can never disagree on what a
 * slider means.
 *
 * Layout (index: meaning):
 * 0 exposure EV · 1 contrast · 2 highlights · 3 shadows · 4 whites · 5 blacks · 6 vibrance ·
 * 7 saturation (each −1..1) · 8–10 white-balance multipliers · 11 mask on · 12 levels black (0..1) ·
 * 13 levels gamma · 14 levels white (0..1) · 15 curves on · 16–17 width, height (the GPU fills them) ·
 * 18 dehaze · 19 grain seed · 20 vignette amount · 21 midpoint · 22 feather · 23 grain amount ·
 * 24 grain size (px) · 25 clarity · 26 texture · 27 sharpen · 28 noise reduction · 29 detail on ·
 * 30 mask inverted · 31 unused · 32–55 HSL, three per range (hue, saturation, luminance, −1..1).
 */
object PhotoAdjustParams {
    const val FLOATS = 56
    const val CURVE_FLOATS = 1024
    const val DETAIL_ON = 29

    fun pack(adjustment: PhotoAdjustment, grainSeed: Int = 0, maskOn: Boolean = adjustment.mask.enabled): FloatArray {
        val a = adjustment
        val p = FloatArray(FLOATS)
        fun v(s: PhotoSlider) = a.value(s)
        p[0] = v(PhotoSliders.EXPOSURE)
        p[1] = v(PhotoSliders.CONTRAST) / 100f
        p[2] = v(PhotoSliders.HIGHLIGHTS) / 100f
        p[3] = v(PhotoSliders.SHADOWS) / 100f
        p[4] = v(PhotoSliders.WHITES) / 100f
        p[5] = v(PhotoSliders.BLACKS) / 100f
        p[6] = v(PhotoSliders.VIBRANCE) / 100f
        p[7] = v(PhotoSliders.SATURATION) / 100f
        val wb = whiteBalance(v(PhotoSliders.TEMPERATURE), v(PhotoSliders.TINT))
        p[8] = wb[0]
        p[9] = wb[1]
        p[10] = wb[2]
        p[11] = if (maskOn) 1f else 0f
        p[12] = v(PhotoSliders.LEVELS_BLACK) / 255f
        p[13] = v(PhotoSliders.LEVELS_MID)
        p[14] = v(PhotoSliders.LEVELS_WHITE) / 255f
        p[15] = if (hasCurves(a)) 1f else 0f
        p[18] = v(PhotoSliders.DEHAZE) / 100f
        p[19] = (grainSeed and 0xFFFFFF).toFloat()
        p[20] = v(PhotoSliders.VIGNETTE) / 100f
        p[21] = v(PhotoSliders.VIGNETTE_MIDPOINT) / 100f
        p[22] = v(PhotoSliders.VIGNETTE_FEATHER) / 100f
        p[23] = v(PhotoSliders.GRAIN) / 100f
        p[24] = 1f + v(PhotoSliders.GRAIN_SIZE) / 100f * GRAIN_MAX_EXTRA_PX
        p[25] = v(PhotoSliders.CLARITY) / 100f
        p[26] = v(PhotoSliders.TEXTURE) / 100f
        p[27] = v(PhotoSliders.SHARPEN) / 100f
        p[28] = v(PhotoSliders.NOISE_REDUCTION) / 100f
        p[DETAIL_ON] = if (p[25] != 0f || p[26] != 0f || p[27] != 0f || p[28] != 0f) 1f else 0f
        p[30] = if (a.mask.inverted) 1f else 0f
        HueRange.entries.forEachIndexed { r, range ->
            for (c in 0..2) p[32 + r * 3 + c] = v(PhotoSliders.hsl(range, c)) / 100f
        }
        return p
    }

    private fun hasCurves(a: PhotoAdjustment): Boolean =
        a.curves.values.any { it.isNotEmpty() && it != PhotoAdjustment.IDENTITY_CURVE }

    /** The curves table: master, red, green, blue rows of 256 outputs (0..1) each. */
    fun curvesTable(adjustment: PhotoAdjustment): FloatArray {
        val out = FloatArray(CURVE_FLOATS)
        CurveChannel.entries.forEachIndexed { row, channel ->
            val lut = CurveLut.build(adjustment.curves[channel] ?: PhotoAdjustment.IDENTITY_CURVE)
            lut.copyInto(out, row * 256)
        }
        return out
    }

    /**
     * Per-channel multipliers (linear light) that move a photo lit at 6500 K to look as if lit at
     * [kelvin] corrected back to 6500 K — raising it warms the photo — then [tint] (−100 green to
     * +100 magenta) on green. Normalised to keep luminance, so the slider never also brightens.
     */
    fun whiteBalance(kelvin: Float, tint: Float): FloatArray {
        val neutral = kelvinToRgb(PhotoSliders.NEUTRAL_KELVIN)
        // Light at [kelvin] corrected to 6500 K: a bluer source (higher K) gets red boosted.
        val target = kelvinToRgb(kelvin)
        val m = FloatArray(3) { neutral[it] / target[it].coerceAtLeast(1e-3f) }
        m[1] *= 1f - tint / 100f * TINT_STRENGTH
        val y = 0.2126f * m[0] + 0.7152f * m[1] + 0.0722f * m[2]
        return FloatArray(3) { m[it] / y }
    }

    /**
     * A black body's colour at [kelvin] (sRGB-encoded, 0..1, then linearised), by Tanner Helland's
     * fit (1000–40000 K, within a percent or two of the CIE locus in this range).
     */
    fun kelvinToRgb(kelvin: Float): FloatArray {
        val t = kelvin.coerceIn(1000f, 40000f) / 100f
        val r = if (t <= 66f) 255f else (329.69873f * (t - 60f).toDouble().pow(-0.13320476).toFloat())
        val g = if (t <= 66f) {
            (99.4708f * ln(t.toDouble()).toFloat() - 161.11957f)
        } else {
            (288.12216f * (t - 60f).toDouble().pow(-0.07551485).toFloat())
        }
        val b = when {
            t >= 66f -> 255f
            t <= 19f -> 0f
            else -> (138.51773f * ln((t - 10f).toDouble()).toFloat() - 305.0448f)
        }
        return floatArrayOf(r, g, b).let { c ->
            FloatArray(3) { PhotoAdjustMath.toLinear((c[it] / 255f).coerceIn(0f, 1f)) }
        }
    }

    private const val TINT_STRENGTH = 0.2f
    private const val GRAIN_MAX_EXTRA_PX = 7f
}
