package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeFeelMeterTest {
    private fun snapshot(tracker: AzphaltLatencyTracker = AzphaltLatencyTracker()) = tracker.snapshot()

    @Test
    fun reportsEveryMeasurementWithItsContext() {
        val meter = StrokeFeelMeter()
        meter.onStrokeStart()
        meter.onSampleAccepted(eventUptimeMs = 100L, acceptedUptimeMs = 104L)
        meter.onStabilized(2.5f)
        meter.onFirstDabPresented(18.0)
        val tracker = AzphaltLatencyTracker()
        val id = tracker.beginInput(1_000_000L)
        tracker.markGenerated(id, 2_000_000L)
        tracker.markSubmitted(id, 3_000_000L)
        tracker.markPresented(id, 21_000_000L)

        val report = meter.report(tracker.snapshot(), "canvas 2000x2000, 3 layers")
        assertTrue(report, report.startsWith("feel (canvas 2000x2000, 3 layers)\n"))
        assertTrue(report, report.contains("touch->paint: median 20.0ms"))
        assertTrue(report, report.contains("input delivery: mean 4.0ms"))
        assertTrue(report, report.contains("first dab: mean 18.0ms"))
        assertTrue(report, report.contains("stabilizer lag: mean 2.5px"))
    }

    @Test
    fun firstDabIsCountedOncePerStroke() {
        val meter = StrokeFeelMeter()
        meter.onStrokeStart()
        meter.onFirstDabPresented(10.0)
        meter.onFirstDabPresented(99.0)
        assertTrue(meter.report(snapshot(), "x").contains("first dab: mean 10.0ms p95 10.0ms max 10.0ms (n=1)"))
    }

    @Test
    fun presentedLatencyIsReadPerSample() {
        val tracker = AzphaltLatencyTracker()
        val id = tracker.beginInput(1_000_000L)
        assertTrue(tracker.presentedLatencyMs(id) == null)
        tracker.markPresented(id, 13_000_000L)
        assertTrue(tracker.presentedLatencyMs(id) == 12.0)
    }

    @Test
    fun frameMeterCountsFramesThatMissedARefresh() {
        val meter = FrameIntervalMeter(framePeriodNs = 16_666_667L)
        repeat(8) { meter.add(16_666_667L) }
        meter.add(33_333_334L) // one missed refresh
        meter.add(50_000_000L) // two missed
        assertTrue(meter.report(), meter.report().contains("frames while drawing: 10, late 2 (20.0%), worst 50.0ms"))
    }
}
