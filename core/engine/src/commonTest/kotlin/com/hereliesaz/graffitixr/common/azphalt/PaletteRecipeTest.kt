package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved mixes (spec/painterly.md 6.4): codec, fail-closed decode, deterministic recall, data-version safety, migration. */
class PaletteRecipeTest {
    private val pigments = PlaceholderPigments.set.pigments.take(PigmentConcentration.BOARD_TUBES)

    private fun board(tubes: List<Pigment> = pigments) = PaletteBoard(48, tubes)

    private fun mixed(b: PaletteBoard = board()): PaletteBoard = b.also {
        it.squeeze(1, 12f, 12f, 8f, 1f)
        it.squeeze(2, 20f, 12f, 8f, 1f)
        repeat(4) { _ -> it.dip(12f, 12f) }
        it.drag(floatArrayOf(12f, 12f, 20f, 12f, 12f, 12f))
    }

    private fun recipeOf(b: PaletteBoard) = assertNotNull(PaletteRecipes.fromBrush(b, "r1", "Sap green"))

    @Test
    fun aRecipeRoundTripsThroughJsonUnchanged() {
        val r = recipeOf(mixed())
        assertEquals(255, r.conc.sum())
        assertEquals(16, r.conc.size)
        val book = RecipeBook(recipes = listOf(r, r.copy(id = "r2", name = "Second", load = 0.5f)))
        assertEquals(book, assertNotNull(RecipeBook.fromJson(book.toJson())))
    }

    @Test
    fun recallIsDeterministicAndGivesTheSavedColourOnAnyBoard() {
        val source = mixed()
        val colour = source.brushColour()
        val r = recipeOf(source)
        val a = board().also { assertTrue(PaletteRecipes.recall(it, r) is PaletteRecipes.Recall.Ok) }
        val b = board().also { assertTrue(PaletteRecipes.recall(it, r) is PaletteRecipes.Recall.Ok) }
        assertEquals(a.brushColour(), b.brushColour(), "two devices, the same colour")
        // the recipe is stored as 255ths, so the colour is the saved one to within that rounding (a few levels per channel)
        for (shift in listOf(16, 8, 0)) {
            val saved = (colour ushr shift) and 0xFF
            val got = (a.brushColour() ushr shift) and 0xFF
            assertTrue(kotlin.math.abs(saved - got) <= 4, "channel at $shift: $saved vs $got")
        }
        assertTrue(a.brush.conc.contentEquals(b.brush.conc))
    }

    @Test
    fun theLoadIsRecalledAndWetnessIsNotPartOfARecipe() {
        val src = mixed()
        src.brush.amount[0] = 0.4f
        val r = recipeOf(src)
        assertEquals(0.4f, r.load, 1e-6f)
        val target = board()
        PaletteRecipes.recall(target, r)
        assertEquals(0.4f, target.brush.amount[0], 1e-6f)
    }

    @Test
    fun aDataVersionOrTubeMismatchIsRefusedNotApproximated() {
        val r = recipeOf(mixed())
        val newerData = pigments.mapIndexed { i, p -> if (i == 3) p.copy(dataVersion = p.dataVersion + 1) else p }
        val target = board(newerData)
        val before = target.brush.conc.copyOf()
        val result = PaletteRecipes.recall(target, r)
        assertTrue(result is PaletteRecipes.Recall.Refused)
        assertTrue(target.brush.conc.contentEquals(before), "the brush is left alone")
        assertEquals(0f, target.brush.amount[0], 0f)
        val reordered = board(pigments.reversed())
        assertTrue(PaletteRecipes.recall(reordered, r) is PaletteRecipes.Recall.Refused)
    }

    @Test
    fun anEmptyBrushHasNothingToSave() {
        assertNull(PaletteRecipes.fromBrush(board(), "x", "Nothing"))
    }

    @Test
    fun decodeFailsClosedOnEveryKindOfDamage() {
        val r = recipeOf(mixed())
        val good = RecipeBook(recipes = listOf(r)).toJson()
        assertNotNull(RecipeBook.fromJson(good))
        assertNull(RecipeBook.fromJson("not json"))
        assertNull(RecipeBook.fromJson(RecipeBook(format = "other/9", recipes = listOf(r)).toJson()))
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = listOf(r.copy(conc = r.conc.dropLast(1)))).toJson()))
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = listOf(r.copy(conc = r.conc.map { it + 1 }))).toJson()), "sum not 255")
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = listOf(r.copy(load = 2f))).toJson()))
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = listOf(r.copy(name = ""))).toJson()))
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = listOf(r, r)).toJson()), "duplicate id")
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = List(RecipeBook.MAX_RECIPES + 1) { r.copy(id = "r$it") }).toJson()))
        assertNull(RecipeBook.fromJson(RecipeBook(recipes = listOf(r.copy(slots = r.slots.dropLast(1)))).toJson()))
    }

    @Test
    fun theRepairingReaderKeepsGoodRecipesAndCountsTheDropped() {
        val r = recipeOf(mixed())
        val book = RecipeBook(recipes = listOf(r, r.copy(id = "bad", load = 5f), r.copy(id = "r3"))).toJson()
        val (kept, dropped) = assertNotNull(RecipeBook.fromJsonRepairing(book))
        assertEquals(listOf("r1", "r3"), kept.recipes.map { it.id })
        assertEquals(1, dropped)
        assertNull(RecipeBook.fromJsonRepairing("garbage"))
        assertEquals(0, RecipeBook.fromJsonRepairing(RecipeBook().toJson())!!.first.recipes.size)
    }

    @Test
    fun swatchesMigrateWithTheirOriginalColourAndAnApproximateFlag() {
        val swatches = listOf(0xFFCC2222.toInt(), 0xFF3366CC.toInt(), 0xFFCC2222.toInt(), 0xFF00FF00.toInt(), 0xFF101010.toInt())
        val board = board()
        val migrated = PaletteRecipes.migrateSwatches(swatches, pigments, board)
        assertEquals(4, migrated.size, "a repeated swatch is one recipe")
        assertEquals(listOf(0xFFCC2222.toInt(), 0xFF3366CC.toInt(), 0xFF00FF00.toInt(), 0xFF101010.toInt()), migrated.map { it.originalArgb })
        assertTrue(migrated.all { it.problem() == null })
        assertTrue(migrated.all { it.id.startsWith("swatch-") && it.load == 1f })
        // migrating twice gives the same recipes (ids are derived from the colour)
        assertEquals(migrated, PaletteRecipes.migrateSwatches(swatches, pigments, board))
        // every one can be recalled
        migrated.forEach { assertTrue(PaletteRecipes.recall(board(), it) is PaletteRecipes.Recall.Ok) }
    }

    @Test
    fun theOldSwatchListIsNotTouchedByMigration() {
        val swatches = listOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val copy = swatches.toList()
        PaletteRecipes.migrateSwatches(swatches, pigments, board())
        assertEquals(copy, swatches, "the migration reads the list; it can be undone because nothing was removed")
    }

    @Test
    fun aPoorMatchIsMarkedApproximateAndAGoodOneIsNot() {
        val board = board()
        val exact = recipeOf(mixed(board)) // a colour the tubes can make exactly
        val argb = board.brushColour()
        val good = PaletteRecipes.fromSwatch(argb, pigments, board)
        assertFalse(good.approximate, "a colour the tubes made is matched closely")
        // a colour far outside the placeholder gamut
        val poor = PaletteRecipes.fromSwatch(0xFF00FFFF.toInt(), pigments, board)
        assertEquals(0xFF00FFFF.toInt(), poor.originalArgb)
        assertEquals(exact.conc.size, poor.conc.size)
    }
}
