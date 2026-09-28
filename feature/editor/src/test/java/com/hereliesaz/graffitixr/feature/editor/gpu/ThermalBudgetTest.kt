package com.hereliesaz.graffitixr.feature.editor.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalBudgetTest {
    private val eps = 1e-5f

    @Test
    fun `cool device keeps the full budget`() {
        assertEquals(1f, ThermalBudgetScaler.scale(ThermalSnapshot(ThermalSnapshot.STATUS_NONE, 0.2f)), eps)
        assertEquals(1f, ThermalBudgetScaler.scale(ThermalSnapshot()), eps) // nothing known
        assertEquals(1f, ThermalBudgetScaler.scale(ThermalSnapshot(ThermalSnapshot.STATUS_LIGHT, Float.NaN)), eps)
    }

    @Test
    fun `headroom ramps the budget down monotonically to the floor`() {
        val hs = listOf(0f, 0.5f, 0.7f, 0.8f, 0.9f, 0.95f, 1f, 1.3f)
        val scales = hs.map { ThermalBudgetScaler.headroomScale(it) }
        scales.zipWithNext().forEach { (a, b) -> assertTrue("$scales", b <= a) }
        assertEquals(1f, ThermalBudgetScaler.headroomScale(0.7f), eps)
        assertEquals(0.625f, ThermalBudgetScaler.headroomScale(0.85f), eps)
        assertEquals(ThermalBudgetScaler.HEADROOM_FLOOR, ThermalBudgetScaler.headroomScale(1f), eps)
        assertEquals(ThermalBudgetScaler.HEADROOM_FLOOR, ThermalBudgetScaler.headroomScale(2f), eps)
    }

    @Test
    fun `status caps the budget and critical stops extra work`() {
        assertEquals(0.6f, ThermalBudgetScaler.scale(ThermalSnapshot(ThermalSnapshot.STATUS_MODERATE, 0.1f)), eps)
        assertEquals(0.3f, ThermalBudgetScaler.scale(ThermalSnapshot(ThermalSnapshot.STATUS_SEVERE, Float.NaN)), eps)
        assertEquals(0f, ThermalBudgetScaler.scale(ThermalSnapshot(ThermalSnapshot.STATUS_CRITICAL, 0.1f)), eps)
        // The stricter of the two wins.
        assertEquals(0.25f, ThermalBudgetScaler.scale(ThermalSnapshot(ThermalSnapshot.STATUS_MODERATE, 1.1f)), eps)
    }

    @Test
    fun `budget provider scales the tier and keeps floors`() {
        val provider = ThermalGpuBudgetProvider(GpuTierTable.HIGH)
        val full = provider.budget.value
        assertEquals(256L * GpuBudget.MIB, full.residentBudgetBytes)
        assertEquals(GpuTierTable.HIGH.dispatchBatchSize, full.dispatchBatchSize)
        assertEquals(GpuTierTable.HIGH.qualityLevels, full.qualityLevels)

        provider.onThermal(ThermalSnapshot(ThermalSnapshot.STATUS_MODERATE, 0.5f))
        val warm = provider.budget.value
        assertEquals(0.6f, warm.scale, eps)
        assertEquals((256 * 0.6f).toLong() * GpuBudget.MIB, warm.residentBudgetBytes)
        assertEquals((256 * 0.6f).toInt(), warm.dispatchBatchSize)
        assertEquals(1 + (3 * 0.6f).toInt(), warm.qualityLevels)

        provider.onThermal(ThermalSnapshot(ThermalSnapshot.STATUS_CRITICAL, 1.2f))
        val hot = provider.budget.value
        assertEquals(GpuBudget.MIN_RESIDENT_MIB * GpuBudget.MIB, hot.residentBudgetBytes)
        assertEquals(1, hot.dispatchBatchSize)
        assertEquals(1, hot.qualityLevels) // draft only
        assertEquals(0f, hot.refinementFraction, eps)

        // A tier change keeps the thermal state.
        provider.setTier(GpuTierTable.CONSERVATIVE)
        assertEquals(GpuTierTable.CONSERVATIVE, provider.budget.value.tier)
        assertEquals(0f, provider.budget.value.scale, eps)
    }
}
