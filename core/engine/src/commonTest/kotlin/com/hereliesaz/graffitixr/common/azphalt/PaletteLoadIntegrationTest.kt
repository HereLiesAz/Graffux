package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec P6b: the board's paint becomes a painterly brush's starting load and what is left returns to the board. */
class PaletteLoadIntegrationTest {
    private val set = PlaceholderPigments.set
    private val tubes = listOf("titanium-white", "cadmium-yellow", "phthalo-blue").map { id -> set.pigments.first { it.id == id } }

    private val brush = AzphaltBrush.fromParams(
        "p",
        Json.parseToJsonElement(
            """{"painterly":1,"load":{"lanes":8},"program":{"step":"hide = 1; emit { x: px, y: py, size: 4, lane: 0, draw: 0.01 };"}}""",
        ) as JsonObject,
    )

    private fun samples() = BrushSampleBuilder().let { b -> (0..120 step 2).mapIndexed { i, x -> b.add(x.toFloat(), 40f, i * 8L, 0.8f) } }

    private fun loaded(): PaletteBoard = PaletteBoard(48, tubes).also {
        it.squeeze(1, 12f, 12f, 8f, 1f)
        repeat(5) { _ -> it.dip(12f, 12f) }
    }

    @Test
    fun anEmptyBrushGivesNoLoadAndAFullOneGivesItsRecipe() {
        assertNull(PaletteBoard(48, tubes).brushLoad())
        val b = loaded()
        val load = assertNotNull(b.brushLoad())
        assertEquals("cadmium-yellow", PigmentRecipe.parse(load.fill.single().pigment).first().first)
        assertTrue(load.fill.single().amount > 0.5f, "the brush is mostly full: ${load.fill.single().amount}")
    }

    @Test
    fun canvasOriginPaintOnTheBrushKeepsItsBasisShareInTheLoad() {
        val b = loaded()
        // dirty the brush with canvas-origin paint: move some of its recipe into the basis channels
        b.brush.conc[PigmentConcentration.BOARD_TUBES] = 0.5f
        b.brush.conc[1] = 0.5f
        val ids = PigmentRecipe.parse(assertNotNull(b.brushLoad()).fill.single().pigment).map { it.first }
        assertTrue(ids.any { it in PigmentLookup.BASIS_IDS }, "the basis share is carried: $ids")
    }

    @Test
    fun aPainterlyStrokeStartsFromTheBoardLoadAndDepletesIt() {
        val b = loaded()
        val start = b.brush.amount[0]
        val strokeBrush = brush.copy(load = b.brushLoad())
        val gen = IncrementalDynamicDabGenerator(16f, strokeBrush, 5L)
        val before = gen.loadRemaining!!
        samples().forEach { gen.append(it) }
        val after = gen.loadRemaining!!
        assertTrue(after < before, "the stroke spent paint: $before -> $after")
        b.absorbStrokeLoad(after / before, start)
        assertTrue(b.brush.amount[0] < start, "and the brush on the board now holds less")
    }

    @Test
    fun aBrushWithNoLoadBlockReportsNone() {
        val gen = IncrementalDynamicDabGenerator(16f, AzphaltBrush(name = "plain"), 1L)
        assertNull(gen.loadRemaining)
    }

    @Test
    fun theSecondStrokeStartsWhereTheFirstLeftOff() {
        val b = loaded()
        fun stroke(): Float {
            val start = b.brush.amount[0]
            val sb = brush.copy(load = b.brushLoad())
            val gen = IncrementalDynamicDabGenerator(16f, sb, 5L)
            val before = gen.loadRemaining!!
            samples().forEach { gen.append(it) }
            b.absorbStrokeLoad(gen.loadRemaining!! / before, start)
            return b.brush.amount[0]
        }
        val first = stroke()
        val second = stroke()
        assertTrue(second < first, "paint keeps running out across strokes: $first then $second")
    }
}
