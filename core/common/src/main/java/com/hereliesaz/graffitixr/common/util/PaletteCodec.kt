package com.hereliesaz.graffitixr.common.util

/**
 * Serialises a saved colour palette to a single string for preference storage and back.
 *
 * A palette is an *ordered* list — the artist arranges swatches — so it can't ride on a string-set
 * preference the way an unordered flag set does; it goes in one comma-separated string instead.
 *
 * [decode] is deliberately forgiving: a preference blob can survive a downgrade, a partial write or
 * hand-editing, and a palette that fails to parse should cost you the unreadable swatches, not
 * crash the picker. Unparseable entries are skipped rather than substituted, so a corrupt entry
 * never silently becomes black.
 */
object PaletteCodec {

    private const val SEPARATOR = ","

    /** Palettes are capped so a runaway writer can't grow the preference blob without bound. */
    const val MAX_SWATCHES = 120

    fun encode(colors: List<Int>): String =
        colors.take(MAX_SWATCHES).joinToString(SEPARATOR) { Integer.toHexString(it) }

    fun decode(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEPARATOR)
            .mapNotNull { entry ->
                // Parsed as Long then narrowed: an ARGB value with the alpha bit set overflows a
                // signed Int, so toIntOrNull() would reject exactly the opaque colours we store.
                entry.trim().takeIf { it.isNotEmpty() }?.toLongOrNull(16)?.toInt()
            }
            .take(MAX_SWATCHES)
    }

    /**
     * Appends [color] unless it is already present — re-saving the colour you are painting with is
     * the most likely double-tap in the picker, and a palette full of one repeated swatch is worse
     * than useless. Drops the oldest swatch when full.
     */
    fun add(palette: List<Int>, color: Int): List<Int> {
        if (palette.contains(color)) return palette
        val appended = palette + color
        return if (appended.size <= MAX_SWATCHES) appended
        else appended.takeLast(MAX_SWATCHES)
    }
}
