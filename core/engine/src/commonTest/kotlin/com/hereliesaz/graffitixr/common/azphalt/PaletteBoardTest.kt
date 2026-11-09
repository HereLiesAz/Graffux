package com.hereliesaz.graffitixr.common.azphalt

import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.CH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec/painterly.md 6.3/6.5: the physical palette board and the recipe solver. */
class PaletteBoardTest {
    private val set = PlaceholderPigments.set
    private val tubes = listOf("titanium-white", "cadmium-yellow", "phthalo-blue", "cadmium-red", "ultramarine").map { id -> set.pigments.first { it.id == id } }

    private fun board(): PaletteBoard = PaletteBoard(64, tubes).also {
        it.squeeze(0, 14f, 20f, 8f, 1f) // white
        it.squeeze(1, 32f, 20f, 8f, 1f) // yellow
        it.squeeze(2, 50f, 20f, 8f, 1f) // phthalo blue
    }

    @Test
    fun aSqueezedBlobIsThatTubeAndFinite() {
        val b = board()
        val r = b.recipeAt(14, 20)
        assertEquals("titanium-white", r.tubes.first().first.id)
        assertEquals(1f, r.tubes.first().second, 1e-3f)
        assertTrue(r.amount > 0.5f)
        val before = b.surface.amount.sum()
        repeat(200) { b.dip(14f, 20f, lift = 0.5f) }
        // the brush is a finite capacity vessel: it fills and stops, the blob keeps the rest
        assertTrue(b.brush.amount[0] <= PaletteBoard.BRUSH_CAPACITY + 1e-4f)
        assertEquals(before, b.surface.amount.sum() + b.brush.amount[0], 1e-2f)
    }

    @Test
    fun dippingThenMixingYellowAndBlueMakesGreenOnTheBoard() {
        val b = board()
        repeat(4) { b.dip(32f, 20f) }
        assertEquals("cadmium-yellow", b.brushRecipe().tubes.first().first.id)
        // lay yellow into the blue blob and work it
        repeat(3) { b.dab(50f, 20f, mix = 0f) }
        val path = floatArrayOf(44f, 20f, 48f, 20f, 52f, 20f, 56f, 20f)
        repeat(4) { b.drag(path) }
        val rgb = b.colourAt(50, 20)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        assertTrue(g > r, "the mixed blob leans green, not grey: ${rgb.toString(16)}")
        val recipe = b.recipeAt(50, 20)
        val ids = recipe.tubes.map { it.first.id }
        assertTrue("phthalo-blue" in ids && "cadmium-yellow" in ids, "the recipe still names both tubes: $ids")
    }

    @Test
    fun cleaningTheBrushWipesPartlyOrRinsesFully() {
        val b = board()
        repeat(4) { b.dip(14f, 20f) }
        val full = b.brush.amount[0]
        b.clean(PaletteBoard.Clean.WIPE)
        assertTrue(b.brush.amount[0] < full && b.brush.amount[0] > 0f)
        b.clean(PaletteBoard.Clean.RINSE)
        assertEquals(0f, b.brush.amount[0])
        assertTrue(b.brushRecipe().tubes.isEmpty())
    }

    @Test
    fun twoRecipesWithTheSameColourStayDistinguishable() {
        // ultramarine + white vs cadmium-red-free pure ultramarine tint: different recipes, kept apart
        val b = PaletteBoard(32, tubes)
        b.squeeze(4, 8f, 8f, 5f, 1f)
        b.squeeze(0, 8f, 8f, 5f, 0.5f)
        b.squeeze(2, 24f, 8f, 5f, 1f)
        b.squeeze(0, 24f, 8f, 5f, 0.5f)
        val a = b.recipeAt(8, 8).tubes.map { it.first.id }.toSet()
        val c = b.recipeAt(24, 8).tubes.map { it.first.id }.toSet()
        assertTrue(a != c, "recipes differ even if the colours were close: $a vs $c")
    }

    @Test
    fun theRecipeSolverFindsPureTubesAndHonestApproximations() {
        for (t in tubes) {
            val rgb = PigmentMixer.mixSrgb8(FloatArray(tubes.size).also { it[tubes.indexOf(t)] = 1f }, tubes)
            val r = RecipeSolver.solve(rgb, tubes)
            val top = r.concentration.indices.maxByOrNull { r.concentration[it] }!!
            assertEquals(t.id, tubes[top].id)
            assertTrue(r.error < 0.02f, "${t.id} error ${r.error}")
        }
        // a colour the tubes cannot reach: reported with its error, never silently exact
        val r = RecipeSolver.solve(intArrayOf(255, 0, 255), tubes)
        assertTrue(r.error > 0.02f)
        assertEquals(1f, r.concentration.sum(), 1e-3f)
    }

    @Test
    fun renderIsDeterministicAndTransparentWhereThereIsNoPaint() {
        val a = board().render()
        val c = board().render()
        assertTrue(a.contentEquals(c))
        assertEquals(0, a[0])
        assertTrue(a.any { it != 0 })
        assertEquals(CH, 16)
    }

    @Test
    fun swappingExchangesThePaintOnTheTwoBrushesAndKeepsTheHand() {
        val b = board()
        repeat(3) { b.dip(14f, 20f) }   // white on the foreground brush
        b.hand = 1
        repeat(3) { b.dip(32f, 20f) }   // yellow on the background brush
        b.hand = 0
        val white = b.brushes[0].conc.copyOf()
        val yellow = b.brushes[1].conc.copyOf()
        val wa = b.brushes[0].amount[0]
        val ya = b.brushes[1].amount[0]
        b.swapBrushes()
        assertTrue(b.brushes[0].conc.contentEquals(yellow) && b.brushes[1].conc.contentEquals(white))
        assertEquals(ya, b.brushes[0].amount[0], 0f)
        assertEquals(wa, b.brushes[1].amount[0], 0f)
        assertEquals(0, b.hand)
        assertEquals("cadmium-yellow", b.brushRecipe().tubes.first().first.id, "the brush in hand now holds the swapped paint")
        b.swapBrushes()
        assertTrue(b.brushes[0].conc.contentEquals(white))
    }

    @Test
    fun aFastDragPaintsEveryPointAlongTheSegmentNotJustItsEnds() {
        val b = board()
        repeat(4) { b.dip(14f, 20f) } // white on the brush
        b.drag(floatArrayOf(20f, 30f, 60f, 30f)) // 40 texels in one step, far more than two brush radii
        for (x in listOf(20, 30, 40, 50, 60)) {
            assertTrue(b.surface.amount[30 * 64 + x] > 0f, "paint reaches x=$x, including the midpoint")
        }
    }

    @Test
    fun aDragWithOnePointStillDabsAndAnEmptyPathDoesNothing() {
        val b = board()
        repeat(4) { b.dip(14f, 20f) }
        b.drag(floatArrayOf(20f, 40f))
        assertTrue(b.surface.amount[40 * 64 + 20] > 0f)
        val before = b.surface.amount.sum()
        b.drag(floatArrayOf())
        assertEquals(before, b.surface.amount.sum(), 0f)
    }
}
