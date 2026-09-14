package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaintMediaProfileTest {

    @Test
    fun `legacy dry profile preserves the allocation-free compatibility path`() {
        val profile = PaintMediaProfile.LEGACY_DRY

        assertEquals("legacy-dry", profile.id)
        assertEquals(1, profile.version)
        assertEquals(PaintMedium(), profile.medium)
        assertTrue(profile.channels.isColorOnly)
        assertEquals(1f, profile.initialLoad)
        assertEquals(0f, profile.initialWetness)
        assertFalse(profile.usesMaterialPath)
    }

    @Test
    fun `profile sanitization clamps coefficients without rewriting identity`() {
        val profile = PaintMediaProfile(
            id = "custom-oil",
            version = 7,
            medium = PaintMedium(
                viscosity = 3f,
                pickupRate = -1f,
                depositionRate = 2f,
            ),
            initialLoad = 4f,
            initialWetness = -3f,
        ).sanitized()

        assertEquals("custom-oil", profile.id)
        assertEquals(7, profile.version)
        assertEquals(1f, profile.medium.viscosity)
        assertEquals(0f, profile.medium.pickupRate)
        assertEquals(1f, profile.medium.depositionRate)
        assertEquals(1f, profile.initialLoad)
        assertEquals(0f, profile.initialWetness)
    }

    @Test
    fun `colour remains explicit stroke intent rather than profile identity`() {
        val profile = PaintMediaProfile(
            id = "same-medium",
            medium = PaintMedium(
                mixingModel = MaterialMixingModel.PIGMENT_RYB,
                pickupRate = 0.4f,
            ),
            initialLoad = 0.75f,
            initialWetness = 0.35f,
        )
        val red = MaterialColor(1f, 0f, 0f, 1f)
        val blue = MaterialColor(0f, 0f, 1f, 1f)

        val redReservoir = profile.initialReservoir(red)
        val blueReservoir = profile.initialReservoir(blue)

        assertEquals(red, redReservoir.carriedColor)
        assertEquals(blue, blueReservoir.carriedColor)
        assertNotEquals(redReservoir.carriedColor, blueReservoir.carriedColor)
        assertEquals(redReservoir.load, blueReservoir.load)
        assertEquals(redReservoir.wetness, blueReservoir.wetness)
        assertEquals(profile.medium, profile.medium)
    }

    @Test
    fun `spatial or reservoir behavior opts a profile into the material path`() {
        assertTrue(
            PaintMediaProfile(
                id = "wet-start",
                initialWetness = 0.2f,
            ).usesMaterialPath,
        )
        assertTrue(
            PaintMediaProfile(
                id = "height",
                channels = MaterialChannels(hasHeight = true),
            ).usesMaterialPath,
        )
        assertTrue(
            PaintMediaProfile(
                id = "pigment",
                medium = PaintMedium(mixingModel = MaterialMixingModel.PIGMENT_RYB),
            ).usesMaterialPath,
        )
    }

    @Test
    fun `blank ids and nonpositive versions are rejected instead of silently repaired`() {
        assertFailsWith<IllegalArgumentException> { PaintMediaProfile(id = "   ") }
        assertFailsWith<IllegalArgumentException> { PaintMediaProfile(id = "bad-version", version = 0) }
    }
}
