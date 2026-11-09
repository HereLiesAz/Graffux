package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaletteMixerHexTest {
    private fun rgbOf(board: com.hereliesaz.graffitixr.common.azphalt.PaletteBoard): IntArray {
        val argb = board.brushColour()
        return intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    private fun distance(a: IntArray, b: IntArray) = (0..2).sumOf { abs(a[it] - b[it]) }

    @Test
    fun anOrangeHexIsMatchedToANearbyColourNotBlack() {
        val board = newMixerBoard()
        val message = matchHexToBrush(board, "#FF8800")
        val got = rgbOf(board)
        assertTrue("not black: ${got.toList()}", got.sum() > 200)
        assertTrue("orange-ish, r > g > b: ${got.toList()}", got[0] > got[1] && got[1] > got[2])
        assertTrue("near the target: ${got.toList()} ($message)", distance(got, intArrayOf(255, 136, 0)) < 150)
        assertEquals(1f, board.brush.amount[0], 0f)
    }

    @Test
    fun otherHexValuesLandNearThemToo() {
        for ((hex, target) in listOf(
            "#3366CC" to intArrayOf(0x33, 0x66, 0xCC),
            "ffffff" to intArrayOf(255, 255, 255),
            "#CC2222" to intArrayOf(0xCC, 0x22, 0x22),
        )) {
            val board = newMixerBoard()
            matchHexToBrush(board, hex)
            val got = rgbOf(board)
            assertTrue("$hex -> ${got.toList()}", distance(got, target) < 170)
        }
        // blue stays blue-ish and is not the black the unfixed code produced for every input
        val blue = newMixerBoard().also { matchHexToBrush(it, "#3366CC") }
        assertTrue(rgbOf(blue)[2] > rgbOf(blue)[0])
    }

    @Test
    fun aNonColourLeavesTheBrushAlone() {
        val board = newMixerBoard()
        assertEquals(NOT_A_COLOUR, matchHexToBrush(board, "hello"))
        assertEquals(0f, board.brush.amount[0], 0f)
    }

    @Test
    fun squeezingATubeNeverOverwritesTheEditorColourButDippingAndMixingDo() {
        val board = newMixerBoard()
        val applied = ArrayList<androidx.compose.ui.graphics.Color>()
        var redraws = 0
        val ops = MixerOps(board, applyColour = { applied += it }, redraw = { redraws++ })
        // the brush holds paint from earlier; the editor colour has since been set by the eyedropper
        board.squeeze(2, 20f, 20f, 8f, 0.9f)
        ops.squeeze(2, 40f, 40f, 7f, 0.9f)
        ops.squeeze(4, 60f, 20f, 7f, 0.9f)
        assertTrue("squeezing redraws the board", redraws == 2)
        assertTrue("and never applies the brush's colour", applied.isEmpty())
        repeat(4) { ops.dip(20f, 20f) }
        assertEquals("each dip applies the brush's colour", 4, applied.size)
        assertTrue(board.brush.amount[0] > 0.2f)
        ops.drag(floatArrayOf(20f, 20f, 24f, 20f))
        // mixing applies the brush's colour exactly while the brush still holds paint (a long drag can spend it all)
        assertEquals(4 + if (board.brush.amount[0] > 0f) 1 else 0, applied.size)
        // a brush that holds nothing applies nothing
        val before = applied.size
        val empty = MixerOps(newMixerBoard(), applyColour = { applied += it }, redraw = {})
        empty.applyBrush()
        assertEquals(before, applied.size)
    }

    @Test
    fun mixOnceAndMixMoreBlendWithoutAnyDrag() {
        fun spread(passes: Int): Int {
            val board = newMixerBoard()
            board.squeeze(4, 40f, 48f, 7f, 0.9f)
            board.squeeze(8, 56f, 48f, 7f, 0.9f)
            val ops = MixerOps(board, applyColour = {}, redraw = {})
            ops.dip(40f, 48f)
            ops.mixStep(48f, 48f, passes)
            return (35..60).count { x -> board.recipeAt(x, 48).tubes.size >= 2 }
        }
        val once = spread(1)
        val more = spread(MIX_MORE_PASSES)
        assertTrue("one tap mixes paint together on the board ($once)", once > 0)
        assertTrue("mix more blends at least as widely ($more >= $once)", more >= once)
    }

    @Test
    fun mixStepIsDeterministicAndAppliesTheBrushColour() {
        val applied = ArrayList<androidx.compose.ui.graphics.Color>()
        fun run(): FloatArray {
            val b = newMixerBoard()
            b.squeeze(4, 48f, 48f, 8f, 0.9f)
            MixerOps(b, applyColour = { applied += it }, redraw = {}).also { it.dip(48f, 48f); it.mixStep(48f, 48f, 2) }
            return b.brush.conc.copyOf()
        }
        assertTrue(run().contentEquals(run()))
        assertTrue(applied.isNotEmpty())
    }

    @Test
    fun aSavedSwatchChipLoadsTheNearestMixAndKeepsItsOriginalHex() {
        val orange = androidx.compose.ui.graphics.Color(0xFFFF8800)
        assertEquals("#FF8800", swatchHex(orange))
        val board = newMixerBoard()
        matchHexToBrush(board, swatchHex(orange))
        val got = rgbOf(board)
        assertTrue(got[0] > got[1] && got[1] > got[2] && got.sum() > 200)
        assertEquals(1f, board.brush.amount[0], 0f)
    }
}
