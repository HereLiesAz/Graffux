package com.hereliesaz.graffitixr.common.azphalt

/** Making, recalling and migrating [PaletteRecipe]s. Pure and deterministic: the same inputs give the same colour anywhere. */
object PaletteRecipes {
    /** Why a recipe cannot be put on a brush. */
    sealed interface Recall {
        object Ok : Recall
        class Refused(val reason: String) : Recall
    }

    private fun slotsOf(board: PaletteBoard): List<RecipeSlot> =
        board.tubes.take(PigmentConcentration.BOARD_TUBES).map { RecipeSlot(it.id, it.dataVersion) }

    /** The mix on [board]'s brush in hand as a recipe called [name]; null when the brush holds no paint to save. */
    fun fromBrush(board: PaletteBoard, id: String, name: String): PaletteRecipe? {
        val b = board.brush
        if (!(b.amount[0] > 0f)) return null
        val raw = FloatArray(PigmentConcentration.BOARD_CHANNELS) { c -> b.conc[c].let { if (it.isFinite()) it.coerceAtLeast(0f) else 0f } }
        if (raw.sum() <= 0f) return null
        return PaletteRecipe(
            id = id, name = name.trim(),
            conc = PigmentConcentration.quantize(raw).toList(),
            slots = slotsOf(board),
            load = (b.amount[0] / PaletteBoard.BRUSH_CAPACITY).coerceIn(0f, 1f),
        ).takeIf { it.problem() == null }
    }

    /**
     * Puts [recipe] on [board]'s brush in hand: the 16 concentrations as 255ths and the load. Refused, and the brush left
     * alone, when the recipe is malformed or was made against different pigment data (a tube id or `dataVersion` differs):
     * a mismatch is never approximated.
     */
    fun recall(board: PaletteBoard, recipe: PaletteRecipe): Recall {
        recipe.problem()?.let { return Recall.Refused(it) }
        val want = slotsOf(board)
        if (recipe.slots != want) return Recall.Refused("made with different pigment data")
        val b = board.brush
        for (c in 0 until PigmentConcentration.BOARD_CHANNELS) b.conc[c] = recipe.conc[c] / PigmentConcentration.SUM.toFloat()
        b.amount[0] = (recipe.load * PaletteBoard.BRUSH_CAPACITY).coerceAtMost(PaletteBoard.BRUSH_CAPACITY)
        return Recall.Ok
    }

    /** How close the nearest tube mix must be (per RecipeSolver's error) for a migrated swatch not to be "approximate". */
    const val CLOSE_MATCH_ERROR = 0.02f

    /**
     * An old saved swatch as a recipe (spec 6.4): the nearest tube mix to [argb] by [RecipeSolver], the original ARGB kept,
     * and marked approximate when the mix is not close. Ids are derived from the colour (`swatch-RRGGBB`), so migrating
     * twice gives the same recipe and a swatch is never duplicated.
     */
    fun fromSwatch(argb: Int, tubes: List<Pigment>, slotSource: PaletteBoard): PaletteRecipe {
        val rgb = intArrayOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
        val solved = RecipeSolver.solve(rgb, tubes)
        val raw = FloatArray(PigmentConcentration.BOARD_CHANNELS) { c -> if (c < solved.concentration.size) solved.concentration[c] else 0f }
        val hex = "%06X".format(argb and 0xFFFFFF)
        return PaletteRecipe(
            id = "swatch-$hex", name = "#$hex",
            conc = PigmentConcentration.quantize(raw).toList(),
            slots = slotsOf(slotSource),
            load = 1f,
            approximate = solved.error >= CLOSE_MATCH_ERROR,
            originalArgb = argb,
        )
    }

    /** Every swatch as a recipe, in order, without duplicates; the original list is untouched (it can be undone). */
    fun migrateSwatches(swatches: List<Int>, tubes: List<Pigment>, slotSource: PaletteBoard): List<PaletteRecipe> =
        swatches.map { fromSwatch(it, tubes, slotSource) }.distinctBy { it.id }
}
