package com.hereliesaz.graffitixr.feature.editor.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTierTest {
    private fun result(stamp: Double, readback: Double, composite: Double) =
        CalibrationResult(stamp, readback, composite, "vulkan")

    @Test
    fun `rows ascend and the default is the most conservative`() {
        val rows = GpuTierTable.rows
        assertEquals(GpuTierTable.CONSERVATIVE, GpuTierTable.default)
        assertEquals(rows.first(), GpuTierTable.default)
        rows.zipWithNext().forEach { (a, b) ->
            assertTrue(b.minStampDabsPerMs >= a.minStampDabsPerMs)
            assertTrue(b.minReadbackMBps >= a.minReadbackMBps)
            assertTrue(b.maxCompositeMs <= a.maxCompositeMs)
            assertTrue(b.residentBudgetMiB >= a.residentBudgetMiB)
        }
        // fp16 stays off until an fp16 path is verified on devices.
        assertTrue(rows.none { it.fp16 })
        assertEquals(rows.size, rows.map { it.name }.toSet().size)
    }

    @Test
    fun `mapping picks the most capable row whose thresholds all hold`() {
        assertEquals(GpuTierTable.HIGH, GpuTierMapper.map(result(100.0, 5_000.0, 3.0)))
        assertEquals(GpuTierTable.STANDARD, GpuTierMapper.map(result(30.0, 2_000.0, 10.0)))
        assertEquals(GpuTierTable.CONSERVATIVE, GpuTierMapper.map(result(5.0, 200.0, 40.0)))
    }

    @Test
    fun `one weak measurement holds the tier down`() {
        // Fast stamping but slow readback is not HIGH.
        assertEquals(GpuTierTable.STANDARD, GpuTierMapper.map(result(100.0, 2_000.0, 3.0)))
        // Everything fast but composite slow.
        assertEquals(GpuTierTable.CONSERVATIVE, GpuTierMapper.map(result(100.0, 5_000.0, 50.0)))
    }

    @Test
    fun `thresholds are inclusive`() {
        val h = GpuTierTable.HIGH
        assertEquals(h, GpuTierMapper.map(result(h.minStampDabsPerMs, h.minReadbackMBps, h.maxCompositeMs)))
    }

    @Test
    fun `missing or invalid results map to the default`() {
        assertEquals(GpuTierTable.default, GpuTierMapper.map(null))
        assertEquals(GpuTierTable.default, GpuTierMapper.map(result(Double.NaN, 5_000.0, 1.0)))
        assertEquals(GpuTierTable.default, GpuTierMapper.map(result(100.0, 0.0, 1.0)))
        assertFalse(result(1.0, 1.0, -1.0).isValid)
    }

    @Test
    fun `byName round trips every row`() {
        GpuTierTable.rows.forEach { assertEquals(it, GpuTierTable.byName(it.name)) }
        assertNull(GpuTierTable.byName("nope"))
    }
}
