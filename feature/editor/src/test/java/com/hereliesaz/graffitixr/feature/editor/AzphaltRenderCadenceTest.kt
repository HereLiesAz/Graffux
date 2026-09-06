package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltRenderCadenceTest {
    @Test
    fun gate_limits_presentation_without_owning_input() {
        val gate = AzphaltRenderCadence()
        assertTrue(gate.shouldRender(1_000L, 60))
        assertFalse(gate.shouldRender(1_005L, 60))
        assertFalse(gate.shouldRender(1_010L, 60))
        assertTrue(gate.shouldRender(1_017L, 60))
    }

    @Test
    fun reset_allows_first_frame_immediately() {
        val gate = AzphaltRenderCadence()
        assertTrue(gate.shouldRender(100L, 60))
        assertFalse(gate.shouldRender(105L, 60))
        gate.reset()
        assertTrue(gate.shouldRender(106L, 60))
    }

    @Test
    fun zero_rate_disables_throttle() {
        val gate = AzphaltRenderCadence()
        assertTrue(gate.shouldRender(100L, 0))
        assertTrue(gate.shouldRender(101L, 0))
    }
}
