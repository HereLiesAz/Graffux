package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaintMaterialTest {

    @Test
    fun `default medium is the allocation-free legacy path`() {
        val medium = PaintMedium()
        assertEquals(MaterialMixingModel.LEGACY_RGB, medium.mixingModel)
        assertFalse(medium.usesMaterialPath)
    }

    @Test
    fun `pigment model opts into material path without enabling spatial channels`() {
        val medium = PaintMedium(mixingModel = MaterialMixingModel.PIGMENT_RYB)
        assertTrue(medium.usesMaterialPath)
        assertTrue(MaterialChannels().isColorOnly)
    }

    @Test
    fun `medium sanitization clamps bounded controls`() {
        val medium = PaintMedium(
            viscosity = -2f,
            yieldLikeStrength = 4f,
            dryingRate = -1f,
            pickupRate = 3f,
            depositionRate = -0.5f,
            heightResponse = -7f,
            substrateResponse = 2f,
        ).sanitized()

        assertEquals(0f, medium.viscosity)
        assertEquals(1f, medium.yieldLikeStrength)
        assertEquals(0f, medium.dryingRate)
        assertEquals(1f, medium.pickupRate)
        assertEquals(0f, medium.depositionRate)
        assertEquals(0f, medium.heightResponse)
        assertEquals(1f, medium.substrateResponse)
    }

    @Test
    fun `reservoir sanitization bounds live state`() {
        val reservoir = BrushReservoirState(
            load = 2f,
            wetness = -1f,
            carriedColor = MaterialColor(2f, -1f, 0.5f, 3f),
        ).sanitized()

        assertEquals(1f, reservoir.load)
        assertEquals(0f, reservoir.wetness)
        assertEquals(MaterialColor(1f, 0f, 0.5f, 1f), reservoir.carriedColor)
    }
}
