package com.hereliesaz.graffitixr.feature.editor.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryPressureTest {
    private val eps = 1e-5f

    @Test
    fun `trim levels map to resident budget factors`() {
        assertEquals(1f, MemoryPressureScaler.factor(0), eps)
        assertEquals(0.5f, MemoryPressureScaler.factor(MemoryPressureScaler.TRIM_RUNNING_MODERATE), eps)
        assertEquals(0.25f, MemoryPressureScaler.factor(MemoryPressureScaler.TRIM_RUNNING_LOW), eps)
        assertEquals(0f, MemoryPressureScaler.factor(MemoryPressureScaler.TRIM_RUNNING_CRITICAL), eps)
        assertEquals(1f, MemoryPressureScaler.factor(MemoryPressureScaler.TRIM_UI_HIDDEN), eps)
        assertEquals(0.25f, MemoryPressureScaler.factor(MemoryPressureScaler.TRIM_BACKGROUND), eps)
        assertEquals(0f, MemoryPressureScaler.factor(MemoryPressureScaler.TRIM_MODERATE), eps)
        assertEquals(0f, MemoryPressureScaler.factor(80), eps)
    }

    @Test
    fun `pressure only lowers the budget until relieved and keeps the floor`() {
        val provider = ThermalGpuBudgetProvider(GpuTierTable.HIGH)
        val full = provider.budget.value.residentBudgetBytes
        provider.onTrimMemory(MemoryPressureScaler.TRIM_RUNNING_LOW)
        val low = provider.budget.value.residentBudgetBytes
        assertEquals(full / 4, low)
        // A milder level afterwards does not raise it again.
        provider.onTrimMemory(MemoryPressureScaler.TRIM_RUNNING_MODERATE)
        assertEquals(low, provider.budget.value.residentBudgetBytes)
        // Thermal and tier changes keep the memory factor.
        provider.onThermal(ThermalSnapshot(ThermalSnapshot.STATUS_NONE, 0.1f))
        assertEquals(low, provider.budget.value.residentBudgetBytes)
        provider.onTrimMemory(MemoryPressureScaler.TRIM_RUNNING_CRITICAL)
        assertEquals(GpuBudget.MIN_RESIDENT_MIB * GpuBudget.MIB, provider.budget.value.residentBudgetBytes)
        provider.onMemoryRelieved()
        assertEquals(full, provider.budget.value.residentBudgetBytes)
    }
}
