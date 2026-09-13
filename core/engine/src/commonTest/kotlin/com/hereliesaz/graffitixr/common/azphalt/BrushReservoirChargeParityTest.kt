package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

class BrushReservoirChargeParityTest {
    @Test
    fun `reservoir depletion reproduces Color Smudge charge envelope`() {
        val colorRate = 0.83f
        val decayRate = 0.037f
        val initial = BrushReservoirState(
            load = 1f,
            wetness = 0f,
            carriedColor = MaterialColor(0.8f, 0.2f, 0.1f),
        )

        for (distance in listOf(0f, 1f, 7.5f, 32f, 96f, 240f)) {
            val oldCharge = (colorRate * exp(-decayRate * distance)).coerceIn(0f, 1f)
            val reservoir = BrushReservoirModel.stateAtDistance(initial, decayRate, distance)
            val newCharge = BrushReservoirModel.effectiveDeposition(colorRate, reservoir)
            assertTrue(
                abs(oldCharge - newCharge) < 0.000001f,
                "distance=$distance old=$oldCharge new=$newCharge",
            )
        }
    }
}
