package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where Levels' midpoint handle sits for a gamma, and the gamma a handle position means. */
class PhotoLevelsTest {
    @Test
    fun `gamma 1 is the middle and the mapping round-trips`() {
        assertEquals(0.5f, levelsMidFraction(1f), 1e-6f)
        // 0.5^(1/2) = 0.70711: a gamma of 2 brightens, so its handle sits right of centre.
        assertEquals(0.70711f, levelsMidFraction(2f), 1e-4f)
        assertEquals(0.25f, levelsMidFraction(0.5f), 1e-6f)
        for (g in listOf(0.3f, 0.8f, 1.1f, 2.5f, 6f)) {
            assertEquals(g, levelsGammaAt(levelsMidFraction(g)), 1e-3f)
        }
    }
}
