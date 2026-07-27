package com.hereliesaz.graffitixr.common.util

/**
 * The classical colour-harmony relationships, as hue rotations off a base hue — Procreate's
 * Harmony tab. Pure degrees-on-the-wheel maths with no Android or Compose dependency, so the
 * relationships can be tested directly rather than inferred from rendered pixels.
 */
enum class HarmonyMode(val label: String) {
    /** The hue directly opposite: maximum contrast from a single partner. */
    COMPLEMENTARY("Complementary"),
    /** The two neighbours 30° either side: low contrast, naturally cohesive. */
    ANALOGOUS("Analogous"),
    /** Three hues evenly spaced around the wheel. */
    TRIADIC("Triadic"),
    /** The two hues flanking the complement: complementary contrast, softer clash. */
    SPLIT_COMPLEMENTARY("Split"),
    /** Two complementary pairs — a rectangle on the wheel. */
    TETRADIC("Tetradic"),
}

object ColorHarmony {

    /**
     * Hue rotations, in degrees, that [mode] adds to a base hue. The base itself (0°) is never
     * included — these are its *partners*, which is what the picker draws alongside it.
     */
    fun offsets(mode: HarmonyMode): List<Float> = when (mode) {
        HarmonyMode.COMPLEMENTARY -> listOf(180f)
        HarmonyMode.ANALOGOUS -> listOf(-30f, 30f)
        HarmonyMode.TRIADIC -> listOf(120f, 240f)
        HarmonyMode.SPLIT_COMPLEMENTARY -> listOf(150f, 210f)
        HarmonyMode.TETRADIC -> listOf(60f, 180f, 240f)
    }

    /**
     * The partner hues for [baseHue] under [mode], each normalised to [0, 360). Feeding in a hue
     * outside that range (a wheel drag that wrapped, a value straight out of `atan2`) is fine —
     * the result is normalised regardless.
     */
    fun partners(baseHue: Float, mode: HarmonyMode): List<Float> =
        offsets(mode).map { normalizeHue(baseHue + it) }

    /** Wraps any hue into [0, 360), including negatives. */
    fun normalizeHue(hue: Float): Float {
        val wrapped = hue % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }
}
