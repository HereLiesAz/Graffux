@file:Suppress("MagicNumber") // Hex digits and channel masks are literal.

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** How many colours the Colour sheet's recent-colours grid keeps; the oldest drops off past this. */
const val RECENT_COLORS_MAX = 24

/**
 * [recent] with [color] at the front: a colour already in the list moves to the front instead of
 * appearing twice (compared as ARGB), and the list never grows past [max].
 */
fun pushRecentColor(recent: List<Color>, color: Color, max: Int = RECENT_COLORS_MAX): List<Color> {
    val argb = color.toArgb()
    return (listOf(color) + recent.filter { it.toArgb() != argb }).take(max)
}

/** [recent] without [color] (compared as ARGB); the same list when it was not there. */
fun removeRecentColor(recent: List<Color>, color: Color): List<Color> {
    val argb = color.toArgb()
    return recent.filter { it.toArgb() != argb }
}

/**
 * The list [recent] (this run's, newest first) over [stored] (what an earlier run saved): the
 * stored colours follow, minus any already in [recent], capped at [max].
 */
fun mergeRecentColors(recent: List<Color>, stored: List<Color>, max: Int = RECENT_COLORS_MAX): List<Color> {
    val seen = recent.mapTo(HashSet()) { it.toArgb() }
    return (recent + stored.filter { seen.add(it.toArgb()) }).take(max)
}

/** [colors] as the text the settings store keeps: eight hex digits each, comma-separated, in order. */
fun encodeRecentColors(colors: List<Color>): String = colors.joinToString(",") { "%08X".format(it.toArgb()) }

/** [encodeRecentColors]'s inverse. An entry that is not eight hex digits is skipped, never an error. */
fun decodeRecentColors(text: String?, max: Int = RECENT_COLORS_MAX): List<Color> {
    if (text.isNullOrBlank()) return emptyList()
    val colors = text.split(',').mapNotNull { part ->
        val hex = part.trim()
        hex.takeIf { it.length == 8 }?.toLongOrNull(16)?.let { Color(it.toInt()) }
    }
    return colors.distinctBy { it.toArgb() }.take(max)
}

/**
 * `#RGB`, `#RRGGBB` or `#AARRGGBB` (the `#` optional, either case) as a colour; null if it isn't one.
 * A three-digit value doubles each digit, as in CSS.
 */
fun parseHexColor(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    val isHex = hex.isNotEmpty() && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return when {
        !isHex -> null
        hex.length == 3 -> {
            val value = hex.toInt(16)
            val r = (value shr 8) and 0xF
            val g = (value shr 4) and 0xF
            val b = value and 0xF
            Color(0xFF000000.toInt() or (r * 0x11 shl 16) or (g * 0x11 shl 8) or (b * 0x11))
        }
        hex.length == 6 -> Color((0xFF000000L or hex.toLong(16)).toInt())
        hex.length == 8 -> Color(hex.toLong(16).toInt())
        else -> null
    }
}

/**
 * What a hex field's [text] means for a brush now [current]: the colour it names, or null while it
 * names none (the field is then shown as invalid and the colour is left alone). Digits that carry
 * no alpha (three or six) keep [current]'s alpha, the brush's working opacity; eight say their own.
 */
fun hexFieldColor(text: String, current: Color): Color? {
    val parsed = parseHexColor(text) ?: return null
    val digits = text.trim().removePrefix("#").length
    return if (digits == 8) parsed else parsed.copy(alpha = current.alpha)
}

/** [color] as `#RRGGBB`, or `#AARRGGBB` when it isn't opaque. */
fun hexOf(color: Color): String {
    val argb = color.toArgb()
    return if ((argb ushr 24) == 0xFF) {
        "#%06X".format(argb and 0xFFFFFF)
    } else {
        "#%08X".format(argb)
    }
}
