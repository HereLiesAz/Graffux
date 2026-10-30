@file:Suppress("MagicNumber") // Hex digits and channel masks are literal.

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** How many colours the picker's Recent row keeps. */
const val RECENT_COLORS_MAX = 12

/**
 * [recent] with [color] at the front: a colour already in the row moves to the front instead of
 * appearing twice (compared as ARGB), and the row never grows past [max].
 */
fun pushRecentColor(recent: List<Color>, color: Color, max: Int = RECENT_COLORS_MAX): List<Color> {
    val argb = color.toArgb()
    return (listOf(color) + recent.filter { it.toArgb() != argb }).take(max)
}

/** `#RRGGBB` or `#AARRGGBB` (the `#` optional, either case) as a colour; null if it isn't one. */
fun parseHexColor(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    val value = hex.takeIf { it.length == 6 || it.length == 8 }?.toLongOrNull(16) ?: return null
    val argb = if (hex.length == 6) 0xFF000000L or value else value
    return Color(argb.toInt())
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
