package com.hereliesaz.graffitixr.feature.editor.ink

/** Pure colour arithmetic for the Ink utensils, kept apart from Ink so it is unit-testable. */
internal object InkColor {
    /** [argb] with its alpha scaled by [opacity] (clamped to 0..1) — how a stock family takes opacity. */
    fun withOpacity(argb: Int, opacity: Float): Int {
        val alpha = ((argb ushr ALPHA_SHIFT and BYTE) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, BYTE)
        return (alpha shl ALPHA_SHIFT) or (argb and RGB_MASK)
    }

    private const val ALPHA_SHIFT = 24
    private const val BYTE = 0xFF
    private const val RGB_MASK = 0xFFFFFF
}
