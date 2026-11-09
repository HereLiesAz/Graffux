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
}
