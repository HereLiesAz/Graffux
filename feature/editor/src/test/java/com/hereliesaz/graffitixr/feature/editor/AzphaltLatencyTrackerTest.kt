package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltLatencyTrackerTest {
    @Test
    fun snapshot_reports_stage_and_total_percentiles() {
        val tracker = AzphaltLatencyTracker(capacity = 8)
        val a = tracker.beginInput(1_000L)
        tracker.markGenerated(a, 2_000L)
        tracker.markSubmitted(a, 4_000L)
        tracker.markPresented(a, 9_000L)

        val b = tracker.beginInput(10_000L)
        tracker.markGenerated(b, 12_000L)
        tracker.markSubmitted(b, 15_000L)
        tracker.markPresented(b, 20_000L)

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.completedSamples)
        assertEquals(2, snapshot.total.count)
        assertEquals(8_000L, snapshot.total.minNs)
        assertEquals(10_000L, snapshot.total.maxNs)
        assertEquals(1_000L, snapshot.inputToGenerated.minNs)
        assertEquals(3_000L, snapshot.generatedToSubmitted.maxNs)
        assertEquals(5_000L, snapshot.submittedToPresented.minNs)
    }

    @Test
    fun ring_discards_old_samples_without_growing() {
        val tracker = AzphaltLatencyTracker(capacity = 4)
        repeat(20) { i ->
            val base = i * 100L + 1L
            val id = tracker.beginInput(base)
            tracker.markGenerated(id, base + 1)
            tracker.markSubmitted(id, base + 2)
            tracker.markPresented(id, base + 3)
        }
        val snapshot = tracker.snapshot()
        assertEquals(4, snapshot.retainedSamples)
        assertEquals(4, snapshot.completedSamples)
        assertTrue(snapshot.total.maxNs > 0L)
    }
}
