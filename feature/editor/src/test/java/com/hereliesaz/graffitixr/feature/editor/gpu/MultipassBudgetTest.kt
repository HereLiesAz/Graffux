package com.hereliesaz.graffitixr.feature.editor.gpu

import com.hereliesaz.graffitixr.common.azphalt.wgpu.MultipassSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class MultipassBudgetTest {
    @Test
    fun draftScaleBecomesTheEngineDivisor() {
        assertEquals(1, GpuBudget.draftDivisor(1f))
        assertEquals(2, GpuBudget.draftDivisor(0.5f))
        assertEquals(4, GpuBudget.draftDivisor(0.25f))
        assertEquals(8, GpuBudget.draftDivisor(0.1f))
        assertEquals(8, GpuBudget.draftDivisor(0f))
    }

    @Test
    fun everyTierAndThermalStateMapsIntoTheScheduler() {
        val provider = ThermalGpuBudgetProvider(GpuTierTable.default)
        for (tier in listOf(GpuTierTable.CONSERVATIVE, GpuTierTable.HIGH)) {
            provider.setTier(tier)
            for (status in listOf(ThermalSnapshot.STATUS_NONE, ThermalSnapshot.STATUS_SEVERE)) {
                provider.onThermal(ThermalSnapshot(status))
                val b = provider.budget.value
                val m = b.toMultipassBudget()
                assertEquals(GpuBudget.draftDivisor(tier.draftResolutionScale), m.draftScale)
                assertEquals(b.qualityLevels, m.qualityLevels)
                assertEquals(b.refinementFraction, m.refinementFraction, 0f)
                assertEquals(tier.tileSizePx, m.tileSizePx)
                // The user's toggle and transition survive; the budget replaces engine defaults.
                val s = MultipassSettings(enabled = true, transitionMs = 80f).withBudget(m)
                assertEquals(true, s.enabled)
                assertEquals(80f, s.transitionMs, 0f)
                assertEquals(m.draftScale, s.draftScale)
                assertEquals(maxOf(2, m.qualityLevels), s.passes)
                assertEquals(m.refinementFraction, s.refineFraction, 0f)
                assertEquals(m.tileSizePx, s.maxChunkPx)
            }
        }
    }
}
