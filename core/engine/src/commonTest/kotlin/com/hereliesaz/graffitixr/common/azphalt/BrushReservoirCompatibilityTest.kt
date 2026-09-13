package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals

class BrushReservoirCompatibilityTest {
    @Test
    fun `effective deposition keeps historical final clamp order`() {
        val state = BrushReservoirState(
            load = 0.25f,
            wetness = 0f,
            carriedColor = MaterialColor(1f, 1f, 1f),
        )

        // Historical Color Smudge evaluated (baseRate * load).coerceIn(0, 1).
        assertEquals(0.5f, BrushReservoirModel.effectiveDeposition(2f, state))
        assertEquals(0f, BrushReservoirModel.effectiveDeposition(-2f, state))
    }
}
