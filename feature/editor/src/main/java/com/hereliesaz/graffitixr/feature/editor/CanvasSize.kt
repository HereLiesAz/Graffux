// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CanvasSize.kt
package com.hereliesaz.graffitixr.feature.editor

import java.util.Locale
import kotlin.math.roundToInt

/** Unit the canvas's physical size is shown and typed in. */
@Suppress("MagicNumber") // 2.54 cm per inch.
enum class LengthUnit(val label: String, val perInch: Double) {
    INCHES("in", 1.0),
    CENTIMETERS("cm", 2.54),
}

/** The resolutions offered for a canvas. */
@Suppress("MagicNumber") // The offered resolutions are the point of the list.
val DPI_OPTIONS: List<Int> = listOf(72, 96, 150, 300, 600)

/**
 * A canvas size: pixels are the truth, [dpi] and [unit] only say how to read them as paper.
 * Every field of the new-project dialog is a view of this one value, so editing any of them
 * recomputes the others:
 *
 * - width or height (in [unit]) sets that side's pixels at [dpi]; the ratio follows.
 * - ratio keeps the width and recomputes the height.
 * - dpi and unit never change the pixels, only how they read.
 */
data class CanvasSize(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int = DEFAULT_DPI,
    val unit: LengthUnit = LengthUnit.INCHES,
) {
    val widthLength: Double get() = length(widthPx)
    val heightLength: Double get() = length(heightPx)
    val ratio: Double get() = widthPx.toDouble() / heightPx

    fun withWidthLength(value: Double): CanvasSize = copy(widthPx = pixels(value))
    fun withHeightLength(value: Double): CanvasSize = copy(heightPx = pixels(value))

    /** Keeps the width and sets the height to match [ratio] (width ÷ height). */
    fun withRatio(ratio: Double): CanvasSize = copy(heightPx = clampPx((widthPx / ratio).roundToInt()))

    fun withDpi(dpi: Int): CanvasSize = copy(dpi = dpi)
    fun withUnit(unit: LengthUnit): CanvasSize = copy(unit = unit)

    fun widthText(): String = formatLength(widthLength)
    fun heightText(): String = formatLength(heightLength)
    fun ratioText(): String = formatRatio(widthPx, heightPx)

    private fun length(px: Int): Double = px.toDouble() / dpi * unit.perInch
    private fun pixels(value: Double): Int = clampPx((value / unit.perInch * dpi).roundToInt())

    companion object {
        const val DEFAULT_DPI = 96
        const val MIN_PX = 1
        const val MAX_PX = 8192
        private const val MAX_RATIO_TERM = 50
        private const val DECIMALS_LENGTH = 2
        private const val DECIMALS_RATIO = 3

        fun clampPx(px: Int): Int = px.coerceIn(MIN_PX, MAX_PX)

        /** A positive number typed into a length field, or null while it isn't one yet. */
        fun parseLength(text: String): Double? =
            text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

        /** "16:9", "16/9", "16x9" or a plain "1.78" (width ÷ height); null if it isn't a positive ratio. */
        fun parseRatio(text: String): Double? {
            val t = text.trim().replace(',', '.')
            val parts = t.split(':', '/', 'x', 'X', '×').map { it.trim() }
            val value = when (parts.size) {
                1 -> parts[0].toDoubleOrNull()
                2 -> {
                    val a = parts[0].toDoubleOrNull()
                    val b = parts[1].toDoubleOrNull()
                    if (a != null && b != null && b > 0) a / b else null
                }
                else -> null
            }
            return value?.takeIf { it > 0 && it.isFinite() }
        }

        fun formatLength(value: Double): String = trimZeros(String.format(Locale.US, "%.${DECIMALS_LENGTH}f", value))

        /** Reduced "a:b" when the terms stay small, else "n:1". */
        fun formatRatio(widthPx: Int, heightPx: Int): String {
            val g = gcd(widthPx, heightPx).coerceAtLeast(1)
            val a = widthPx / g
            val b = heightPx / g
            if (a <= MAX_RATIO_TERM && b <= MAX_RATIO_TERM) return "$a:$b"
            val n = trimZeros(String.format(Locale.US, "%.${DECIMALS_RATIO}f", widthPx.toDouble() / heightPx))
            return "$n:1"
        }

        private fun trimZeros(s: String): String = if ('.' in s) s.trimEnd('0').trimEnd('.') else s

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
