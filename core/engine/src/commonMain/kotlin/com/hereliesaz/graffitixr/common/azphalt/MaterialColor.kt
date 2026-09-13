package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Public color-mixing contract for the material-aware paint path.
 *
 * The enum describes visible behavior, not a permanently fixed algorithm. [PIGMENT_RYB] is the
 * first bounded subtractive approximation; a future LUT or spectral backend can sit behind a new
 * model without changing brush input/replay semantics.
 */
enum class MaterialMixingModel {
    /** Historical Graffux channel interpolation. Existing brushes/presets stay on this by default. */
    LEGACY_RGB,

    /** Artist-oriented red/yellow/blue latent mixing; yellow + blue bends toward green. */
    PIGMENT_RYB,
}

/** Renderer-independent straight-alpha material color, normalized to 0..1 per channel. */
data class MaterialColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float = 1f,
) {
    fun clamped(): MaterialColor = MaterialColor(
        red.coerceIn(0f, 1f),
        green.coerceIn(0f, 1f),
        blue.coerceIn(0f, 1f),
        alpha.coerceIn(0f, 1f),
    )

    fun toArgb(): Int {
        val c = clamped()
        return ArgbColor.argb(
            (c.alpha * 255f).roundToInt().coerceIn(0, 255),
            (c.red * 255f).roundToInt().coerceIn(0, 255),
            (c.green * 255f).roundToInt().coerceIn(0, 255),
            (c.blue * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    companion object {
        fun fromArgb(argb: Int): MaterialColor = MaterialColor(
            ArgbColor.red(argb) / 255f,
            ArgbColor.green(argb) / 255f,
            ArgbColor.blue(argb) / 255f,
            ArgbColor.alpha(argb) / 255f,
        )
    }
}

/**
 * Deterministic CPU reference for material color mixing.
 *
 * PIGMENT_RYB uses the classic continuous RGB<->RYB artist-space transform and interpolates in
 * that latent space. It is intentionally an approximation, not a Kubelka-Munk/spectral claim.
 * It is cheap enough for a reference path, has an exact GLSL translation, preserves ratio
 * endpoints, and fixes the most obvious additive artifact this phase targets: yellow + blue no
 * longer trends through gray.
 */
object MaterialColorMixer {
    fun mix(
        a: MaterialColor,
        b: MaterialColor,
        ratio: Float,
        model: MaterialMixingModel,
        includeAlpha: Boolean = true,
    ): MaterialColor {
        val t = ratio.coerceIn(0f, 1f)
        if (t <= 0f) return a.clamped()
        if (t >= 1f) return if (includeAlpha) b.clamped() else b.clamped().copy(alpha = a.alpha.coerceIn(0f, 1f))

        val ac = a.clamped()
        val bc = b.clamped()
        val rgb = when (model) {
            MaterialMixingModel.LEGACY_RGB -> floatArrayOf(
                lerp(ac.red, bc.red, t),
                lerp(ac.green, bc.green, t),
                lerp(ac.blue, bc.blue, t),
            )
            MaterialMixingModel.PIGMENT_RYB -> {
                val aryb = rgbToRyb(ac.red, ac.green, ac.blue)
                val bryb = rgbToRyb(bc.red, bc.green, bc.blue)
                rybToRgb(
                    lerp(aryb[0], bryb[0], t),
                    lerp(aryb[1], bryb[1], t),
                    lerp(aryb[2], bryb[2], t),
                )
            }
        }
        return MaterialColor(
            rgb[0].coerceIn(0f, 1f),
            rgb[1].coerceIn(0f, 1f),
            rgb[2].coerceIn(0f, 1f),
            if (includeAlpha) lerp(ac.alpha, bc.alpha, t) else ac.alpha,
        )
    }

    fun mixArgb(
        a: Int,
        b: Int,
        ratio: Float,
        model: MaterialMixingModel,
        includeAlpha: Boolean = true,
    ): Int = mix(MaterialColor.fromArgb(a), MaterialColor.fromArgb(b), ratio, model, includeAlpha).toArgb()

    private fun rgbToRyb(red: Float, green: Float, blue: Float): FloatArray {
        var r = red
        var g = green
        var b = blue
        val white = min(r, min(g, b))
        r -= white
        g -= white
        b -= white

        val maxGreen = max(r, max(g, b))
        var yellow = min(r, g)
        r -= yellow
        g -= yellow

        if (b > 0f && g > 0f) {
            b *= 0.5f
            g *= 0.5f
        }

        yellow += g
        b += g
        val maxYellow = max(r, max(yellow, b))
        if (maxYellow > 0f) {
            val normal = maxGreen / maxYellow
            r *= normal
            yellow *= normal
            b *= normal
        }

        return floatArrayOf(r + white, yellow + white, b + white)
    }

    private fun rybToRgb(red: Float, yellow: Float, blue: Float): FloatArray {
        var r = red
        var y = yellow
        var b = blue
        val white = min(r, min(y, b))
        r -= white
        y -= white
        b -= white

        val maxYellow = max(r, max(y, b))
        var green = min(y, b)
        y -= green
        b -= green

        if (b > 0f && green > 0f) {
            b *= 2f
            green *= 2f
        }

        r += y
        green += y
        val maxGreen = max(r, max(green, b))
        if (maxGreen > 0f) {
            val normal = maxYellow / maxGreen
            r *= normal
            green *= normal
            b *= normal
        }

        return floatArrayOf(r + white, green + white, b + white)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
