package com.hereliesaz.graffitixr.feature.editor.gpu

import com.hereliesaz.graffitixr.nativebridge.PassKind
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTelemetryTest {
    private val ms = 1_000_000L

    @Test
    fun `gpu timings win over cpu wall time and each is labelled`() {
        val t = GpuTelemetry { 16 * ms }
        t.onPass(PassKind.STAMP.ordinal, 3 * ms, gpu = false)
        t.onPass(PassKind.STAMP.ordinal, 1 * ms, gpu = true)
        t.onPass(PassKind.READBACK.ordinal, 2 * ms, gpu = false)
        val info = GpuInfo(renderer = "Adreno (TM) 740", engine = "vulkan", apiVersion = "1.3.0")
        val report = t.report(info, null, null)
        assertTrue(report, report.contains("stamp p50 1.00ms p95 1.00ms (n=1) gpu"))
        assertTrue(report, report.contains("readback p50 2.00ms p95 2.00ms (n=1) cpu"))
        assertTrue(report, report.contains("composite n/a"))
        assertTrue(report, report.contains("multipass n/a"))
        assertTrue(report, report.contains("vulkan 1.3.0"))
        assertTrue(report, report.startsWith("  gpu: Adreno (TM) 740"))
        assertTrue(report, !report.endsWith("\n"))
    }

    @Test
    fun `batches over the frame budget are counted`() {
        val t = GpuTelemetry { 16 * ms }
        // Batch 1: 10 + 3 = 13ms, fine. Batch 2: 15 + 4 = 19ms, a miss.
        t.onPass(PassKind.STAMP.ordinal, 10 * ms, false)
        t.onPass(PassKind.READBACK.ordinal, 3 * ms, false)
        t.onPass(PassKind.STAMP.ordinal, 15 * ms, false)
        t.onPass(PassKind.READBACK.ordinal, 4 * ms, false)
        // GPU samples never count toward misses.
        t.onPass(PassKind.STAMP.ordinal, 100 * ms, true)
        val report = t.report(GpuInfo(), null, null)
        assertTrue(report, report.contains("frame budget: 1 of 2 batches over 16.0ms"))
    }

    @Test
    fun `sample count is the window the percentiles cover, not the lifetime`() {
        val t = GpuTelemetry { 16 * ms }
        repeat(600) { t.onPass(PassKind.STAMP.ordinal, 1 * ms, gpu = true) }
        val report = t.report(GpuInfo(), null, null)
        assertTrue(report, report.contains("stamp p50 1.00ms p95 1.00ms (n=512) gpu"))
    }

    @Test
    fun `thermal start and end, and calibration passes are ignored`() {
        val t = GpuTelemetry { 16 * ms }
        t.onThermal(ThermalSnapshot(ThermalSnapshot.STATUS_NONE, 0.3f))
        t.paused = true
        t.onPass(PassKind.STAMP.ordinal, 5 * ms, false)
        t.paused = false
        val report = t.report(GpuInfo(), null, ThermalSnapshot(ThermalSnapshot.STATUS_MODERATE, 0.81f))
        assertTrue(report, report.contains("thermal: start none (headroom 0.30), end moderate (headroom 0.81)"))
        assertTrue(report, report.contains("stamp n/a"))
    }
}
