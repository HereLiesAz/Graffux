package com.hereliesaz.graffitixr.common.azphalt

import com.hereliesaz.graffitixr.common.azphalt.WatercolourField.Field
import com.hereliesaz.graffitixr.common.azphalt.WatercolourField.Params
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec/painterly.md 3.8: wicking, bleed, edge darkening, granulation and drying. */
class WatercolourFieldTest {
    private val n = 32

    private fun blob(wet: Float = 1f, pig: Float = 1f, absorbency: Float = 0.5f): Field {
        val f = Field(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            f.absorbency[i] = absorbency
            val dx = x - 16
            val dy = y - 16
            if (dx * dx + dy * dy <= 36) { f.wet[i] = wet; f.pigment[i] = pig }
        }
        return f
    }

    private fun extent(f: Field, threshold: Float): Int = f.wet.count { it > threshold }

    @Test
    fun waterSpreadsFasterOnThirstierPaper() {
        val dry = WatercolourField.run(blob(absorbency = 0.1f), Params(dry = 0f), 12)
        val thirsty = WatercolourField.run(blob(absorbency = 0.9f), Params(dry = 0f), 12)
        assertTrue(extent(thirsty, 0.05f) > extent(dry, 0.05f), "wicking reaches further on absorbent paper")
    }

    @Test
    fun waterAndPigmentAreConservedWhileNothingDries() {
        val f = blob()
        val water = f.wet.sum()
        val pig = f.pigment.sum()
        WatercolourField.run(f, Params(dry = 0f), 40)
        assertEquals(water, f.wet.sum(), water * 1e-3f)
        assertEquals(pig, f.pigment.sum(), pig * 1e-3f)
    }

    @Test
    fun dryingRemovesWaterButNotPigment() {
        val f = blob()
        val pig = f.pigment.sum()
        WatercolourField.run(f, Params(dry = 0.1f), 60)
        assertTrue(f.wet.sum() < 1f, "almost no water left: ${f.wet.sum()}")
        assertEquals(pig, f.pigment.sum(), pig * 1e-3f)
    }

    private fun pigmentOutside(f: Field, radius: Int): Float {
        var out = 0f
        for (y in 0 until n) for (x in 0 until n) {
            if ((x - 16) * (x - 16) + (y - 16) * (y - 16) > radius * radius) out += f.pigment[y * n + x]
        }
        return out
    }

    @Test
    fun edgeFlowCarriesPigmentTowardTheDryingMarginAndNothingElseDoes() {
        // a wet disc of pigment on thirsty paper: with edge flow the pigment migrates outward past the
        // disc as the margin dries; with no edge flow and no bleed it stays put
        val withEdge = blob(absorbency = 0.7f)
        WatercolourField.run(withEdge, Params(wick = 0.2f, bleed = 0f, edge = 0.2f, dry = 0.03f), 30)
        val without = blob(absorbency = 0.7f)
        WatercolourField.run(without, Params(wick = 0.2f, bleed = 0f, edge = 0f, dry = 0.03f), 30)
        val start = pigmentOutside(blob(), 6)
        assertEquals(0f, pigmentOutside(without, 6), 1e-5f)
        assertTrue(pigmentOutside(withEdge, 6) > start + 0.5f, "pigment reaches the margin: ${pigmentOutside(withEdge, 6)}")
        assertEquals(blob().pigment.sum(), withEdge.pigment.sum(), 0.2f)
    }

    @Test
    fun pigmentOnlyMovesThroughWetPaper() {
        val f = Field(n, n)
        f.pigment[16 * n + 16] = 1f // a pigment grain on dry paper, no water anywhere
        WatercolourField.run(f, Params(dry = 0f), 20)
        assertEquals(1f, f.pigment[16 * n + 16], 1e-6f)
        assertEquals(1f, f.pigment.sum(), 1e-5f)
    }

    @Test
    fun granulationSettlesPigmentIntoValleys() {
        val f = Field(4, 1)
        f.tooth[0] = 0f; f.tooth[1] = 0.5f; f.tooth[2] = 1f; f.tooth[3] = 0.25f
        WatercolourField.deposit(f, floatArrayOf(1f, 1f, 1f, 1f), FloatArray(4), Params(granulation = 0.8f))
        assertTrue(f.pigment[0] > f.pigment[1] && f.pigment[1] > f.pigment[2], "valley > flat > crest: ${f.pigment.toList()}")
        val flat = Field(4, 1)
        WatercolourField.deposit(flat, floatArrayOf(1f, 1f, 1f, 1f), FloatArray(4), Params(granulation = 0f))
        assertTrue(flat.pigment.all { it == 1f })
    }

    @Test
    fun fieldsStayInRangeAndTheSameInputsGiveTheSameResult() {
        val a = WatercolourField.run(blob(), Params(wick = 9f, bleed = 9f, edge = 9f, dry = 0.02f), 50)
        val b = WatercolourField.run(blob(), Params(wick = 9f, bleed = 9f, edge = 9f, dry = 0.02f), 50)
        assertTrue(a.wet.all { it in 0f..1f } && a.pigment.all { it >= 0f && it.isFinite() })
        assertTrue(a.wet.contentEquals(b.wet) && a.pigment.contentEquals(b.pigment))
        assertTrue(abs(a.pigment.sum() - blob().pigment.sum()) < 0.5f)
    }
}
