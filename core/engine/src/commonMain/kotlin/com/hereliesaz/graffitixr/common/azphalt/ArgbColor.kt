package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.roundToInt

/** Packed-ARGB-int color math with zero `android.graphics.Color` dependency, so dab color
 *  resolution ([resolveDabColor]) runs identically on Android and desktop from the same [Dab]
 *  list. Channel order and packing match `android.graphics.Color` exactly (ARGB, 0xAARRGGBB). */
object ArgbColor {

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    fun resolveDabColor(
        baseArgb: Int,
        secondaryArgb: Int,
        colorSource: BrushColorSource,
        dab: Dab,
    ): Int {
        val sourced = when (colorSource) {
            BrushColorSource.PLAIN -> baseArgb
            BrushColorSource.GRADIENT -> lerp(baseArgb, secondaryArgb, dab.colorMix)
            BrushColorSource.UNIFORM_RANDOM -> lerp(baseArgb, secondaryArgb, dab.sourceRandom)
        }
        if (dab.hueShiftDeg == 0f && dab.saturationMultiplier == 1f && dab.valueMultiplier == 1f) {
            return sourced
        }
        val hsv = rgbToHsv(red(sourced), green(sourced), blue(sourced))
        val h = ((hsv[0] + dab.hueShiftDeg) % 360f + 360f) % 360f
        val s = (hsv[1] * dab.saturationMultiplier).coerceIn(0f, 1f)
        val v = (hsv[2] * dab.valueMultiplier).coerceIn(0f, 1f)
        val (r, g, b) = hsvToRgb(h, s, v)
        return argb(alpha(sourced), r, g, b)
    }

    private fun lerp(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(ca: Int, cb: Int): Int = (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        return argb(
            channel(alpha(a), alpha(b)),
            channel(red(a), red(b)),
            channel(green(a), green(b)),
            channel(blue(a), blue(b)),
        )
    }

    /** Returns [hue (0..360), saturation (0..1), value (0..1)]. */
    fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / delta) % 6f)
            max == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val saturation = if (max == 0f) 0f else delta / max
        return floatArrayOf(hue, saturation, max)
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun toByte(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return Triple(toByte(r1), toByte(g1), toByte(b1))
    }
}
