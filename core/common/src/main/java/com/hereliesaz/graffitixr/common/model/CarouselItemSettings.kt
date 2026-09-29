package com.hereliesaz.graffitixr.common.model

/**
 * The paint settings one bottom-carousel item owns: a stamp brush, an Ink utensil or an effect tool
 * each keep their own Size, Flow, Opacity, Softness and (Smudge only) Strength, so changing one
 * item's Size never touches another's. Keyed by the carousel entry's stable key (`builtin.<name>`,
 * `custom.<id>`, `ext.<id>`, `ink.<id>`, `tool.<name>`), the same keys favourites use.
 *
 * Defaults match `EditorUiState`'s own and `ColorSmudgeEngine.Settings.smudgeRate`'s.
 */
data class CarouselItemSettings(
    val size: Float = DEFAULT_SIZE,
    val flow: Float = 1f,
    val opacity: Float = 1f,
    val softness: Float = 0f,
    val strength: Float = DEFAULT_STRENGTH,
) {
    /** Every field clamped to what the editor's reducer would accept anyway. */
    fun sanitized(): CarouselItemSettings = CarouselItemSettings(
        size = size.finiteOr(DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE),
        flow = flow.finiteOr(1f).coerceIn(0f, 1f),
        opacity = opacity.finiteOr(1f).coerceIn(0f, 1f),
        softness = softness.finiteOr(0f).coerceIn(0f, 1f),
        strength = strength.finiteOr(DEFAULT_STRENGTH).coerceIn(0f, 1f),
    )

    companion object {
        const val DEFAULT_SIZE = 50f
        const val DEFAULT_STRENGTH = 0.65f
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 200f

        // Records are newline-separated, a record's key and values tab-separated. Keys embed brush
        // names, which may hold commas or spaces but never a tab or a newline (they are single-line
        // text fields), so neither separator can appear inside a key.
        private const val RECORD = "\n"
        private const val FIELD = "\t"
        private const val FIELD_COUNT = 6

        /** Serialises [map] for the settings store. Round-trips through [decode]. */
        fun encode(map: Map<String, CarouselItemSettings>): String = map.entries
            .filter { (key, _) -> key.isNotBlank() && RECORD !in key && FIELD !in key }
            .joinToString(RECORD) { (key, s) ->
                listOf(key, s.size, s.flow, s.opacity, s.softness, s.strength).joinToString(FIELD)
            }

        /** Parses [encode]'s output. Malformed records are dropped, never thrown on. */
        fun decode(raw: String?): Map<String, CarouselItemSettings> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(RECORD).mapNotNull { record ->
                val parts = record.split(FIELD)
                if (parts.size != FIELD_COUNT || parts[0].isBlank()) return@mapNotNull null
                val values = parts.drop(1).map { it.toFloatOrNull() ?: return@mapNotNull null }
                val it = values.iterator()
                parts[0] to CarouselItemSettings(it.next(), it.next(), it.next(), it.next(), it.next()).sanitized()
            }.toMap()
        }

        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
    }
}
