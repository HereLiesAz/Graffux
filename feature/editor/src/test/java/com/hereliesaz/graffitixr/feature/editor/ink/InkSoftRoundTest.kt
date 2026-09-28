package com.hereliesaz.graffitixr.feature.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InkSoftRoundTest {

    @Test
    fun zeroFeatheringStaysOnTheHardStockPen() {
        assertFalse(InkSoftRound.isSoft(0f))
        assertFalse(InkSoftRound.isSoft(0.01f)) // rounds to the 0 step
        assertTrue(InkSoftRound.isSoft(0.3f))
        assertEquals(1f, InkSoftRound.tipScale(0f), 0f)
    }

    @Test
    fun tipWidensByTheBlurRadiusOnEachSide() {
        // BlurMaskFilter radius = size * feathering / 2, i.e. feathering tip radii past the edge.
        assertEquals(1.5f, InkSoftRound.tipScale(0.5f), 1e-6f)
    }

    @Test
    fun textureIdsRoundTripAndIgnoreForeignIds() {
        val id = InkSoftRound.textureId(0.33f, 0.78f)
        assertEquals("graffux.ink.softround/0.35/0.80", id)
        assertEquals(0.35f to 0.80f, InkSoftRound.parseTextureId(id))
        assertNull(InkSoftRound.parseTextureId("ink://pencil"))
        assertNull(InkSoftRound.parseTextureId("graffux.ink.softround/x/y"))
    }

    @Test
    fun targetProfileMatchesTheBlurBand() {
        val f = 0.5f
        // Edge at 1/(1+f) of the widened radius; fade from (1-f)/(1+f) to the rim.
        val inner = (1f - f) / (1f + f)
        assertEquals(1f, InkSoftRound.targetProfile(inner - 0.01f, f), 0f)
        assertEquals(0.5f, InkSoftRound.targetProfile(1f / (1f + f), f), 1e-4f)
        assertEquals(0f, InkSoftRound.targetProfile(1f, f), 0f)
        // Monotonic falloff.
        var last = 1f
        for (i in 0..100) {
            val v = InkSoftRound.targetProfile(i / 100f, f)
            assertTrue(v <= last + 1e-6f)
            last = v
        }
    }

    @Test
    fun solvedStampsAccumulateToTheTargetCrossSection() {
        for (f in listOf(0.2f, 0.5f, 1f)) {
            for (opacity in listOf(1f, 0.5f)) {
                val stamp = InkSoftRound.solveStampProfile(f, opacity)
                val achieved = InkSoftRound.accumulatedCrossSection(stamp, InkSoftRound.PARTICLE_GAP)
                for (i in 0 until InkSoftRound.BINS) {
                    val target = opacity * InkSoftRound.targetProfile(i / (InkSoftRound.BINS - 1f), f)
                    assertTrue(
                        "f=$f o=$opacity bin $i: target $target achieved ${achieved[i]}",
                        abs(target - achieved[i]) < TOLERANCE,
                    )
                }
            }
        }
    }

    @Test
    fun solvedStampsAreFainterThanTheTargetBecauseTheyOverlap() {
        val stamp = InkSoftRound.solveStampProfile(0.5f, 0.5f)
        // Centre: ~20 overlapping stamps must add up to 50%, so each one is far below 50%.
        assertTrue(stamp[0].toString(), stamp[0] < 0.1f)
        assertTrue(stamp.all { it in 0f..1f })
    }

    private companion object {
        // Stamps are round and the solve is per radius, so the straight-line cross-section can only
        // be approximated; a few percent is well under what reads as a different edge.
        const val TOLERANCE = 0.06f
    }
}
