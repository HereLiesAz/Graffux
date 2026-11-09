package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable

/**
 * A saved mix (spec/painterly.md 6.4): the paint on a palette brush, recorded so it can be recalled onto a brush and give
 * the same colour on every device. It is the brush's recipe as the **16 quantised concentrations** (u8, summing to 255:
 * the 12 tubes then the 4 canvas-origin basis channels), the **slot table** (each tube's id and `dataVersion`) so a recipe
 * made against one set of pigment data can never silently change meaning under another, and the **load** (how much paint
 * was on the brush, 0..1). Wetness is not stored: it is board state that evolves, not part of a mix.
 *
 * A recipe migrated from an old saved swatch also keeps its [originalArgb] and says whether the match is
 * [approximate] (the nearest tube mix was not close to the colour).
 */
@Serializable
data class PaletteRecipe(
    val id: String,
    val name: String,
    /** 16 values 0..255 summing to 255, in channel order. */
    val conc: List<Int>,
    /** The 12 tubes this was made against, in channel order. */
    val slots: List<RecipeSlot>,
    /** 0..1 of the brush's capacity. */
    val load: Float = 1f,
    val approximate: Boolean = false,
    val originalArgb: Int? = null,
) {
    /** Null when well formed, else why not: every value is checked, none is trusted. */
    fun problem(): String? = when {
        id.isBlank() || id.length > MAX_ID -> "bad id"
        name.isBlank() || name.length > MAX_NAME -> "bad name"
        conc.size != PigmentConcentration.BOARD_CHANNELS -> "need ${PigmentConcentration.BOARD_CHANNELS} concentrations"
        conc.any { it !in 0..PigmentConcentration.SUM } -> "a concentration is out of range"
        conc.sum() != PigmentConcentration.SUM -> "concentrations do not sum to ${PigmentConcentration.SUM}"
        slots.size != PigmentConcentration.BOARD_TUBES -> "need ${PigmentConcentration.BOARD_TUBES} slots"
        slots.any { it.id.isBlank() || it.id.length > MAX_ID || it.dataVersion < 0 } -> "a slot is malformed"
        !load.isFinite() || load !in 0f..1f -> "load out of range"
        else -> null
    }

    companion object {
        const val MAX_NAME = 64
        const val MAX_ID = 128
    }
}

@Serializable
data class RecipeSlot(val id: String, val dataVersion: Int)

/**
 * The recipes of one scope (a project, or the app): `azphalt-recipes/1`, fail-closed. A file with a wrong format, too many
 * recipes, a malformed recipe, or a duplicate id is refused whole by [fromJson]; [fromJsonRepairing] (for the user's own
 * file) keeps the good recipes and says how many it dropped.
 */
@Serializable
data class RecipeBook(
    val format: String = FORMAT,
    val recipes: List<PaletteRecipe> = emptyList(),
) {
    fun toJson(): String = AzphaltJson.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = "azphalt-recipes/1"
        const val MAX_RECIPES = 200

        /** Strict: null on any problem. */
        fun fromJson(text: String): RecipeBook? {
            val book = runCatching { AzphaltJson.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            val ok = book.format == FORMAT && book.recipes.size <= MAX_RECIPES &&
                book.recipes.all { it.problem() == null } && book.recipes.map { it.id }.toSet().size == book.recipes.size
            return book.takeIf { ok }
        }

        /** The good recipes of [text] and how many were dropped; null if the file is not a recipe book at all. */
        fun fromJsonRepairing(text: String): Pair<RecipeBook, Int>? {
            val root = runCatching { AzphaltJson.parseToJsonElement(text) }.getOrNull()
                as? kotlinx.serialization.json.JsonObject ?: return null
            // a field that is present must be well formed: a present-but-wrong "format" or "recipes" is a damaged book,
            // never an empty healthy one (which would skip the backup and be overwritten)
            val format = root["format"]
            val formatOk = format == null ||
                (format is kotlinx.serialization.json.JsonPrimitive && format.isString && format.content == FORMAT)
            val rawItems = root["recipes"]
            if (!formatOk || (rawItems != null && rawItems !is kotlinx.serialization.json.JsonArray)) return null
            val items = rawItems as? kotlinx.serialization.json.JsonArray ?: return RecipeBook() to 0
            val good = ArrayList<PaletteRecipe>()
            val seen = HashSet<String>()
            items.forEach { el ->
                val r = runCatching { AzphaltJson.decodeFromJsonElement(PaletteRecipe.serializer(), el) }.getOrNull()
                if (r != null && r.problem() == null && seen.add(r.id) && good.size < MAX_RECIPES) good += r
            }
            return RecipeBook(recipes = good) to (items.size - good.size)
        }
    }
}
