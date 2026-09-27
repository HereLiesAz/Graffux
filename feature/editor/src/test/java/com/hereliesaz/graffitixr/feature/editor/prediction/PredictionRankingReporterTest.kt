package com.hereliesaz.graffitixr.feature.editor.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionRankingReporterTest {
    private val sent = mutableListOf<Pair<String, String>>()
    private val reporter = PredictionRankingReporter("Pixel 9, Android 17") { t, b -> sent += t to b }

    @Test
    fun filesOncePerBatchOfStrokes() {
        repeat(PredictionRankingReporter.REPORT_EVERY_STROKES * 2 - 1) { reporter.onBrushStroke("f1: linear", 120f) }
        assertEquals(1, sent.size)
        assertTrue(sent[0].first.contains("25 strokes"))
        assertTrue(sent[0].second.contains("~~~\nf1: linear\n~~~"))
        assertTrue(sent[0].second.contains("120 Hz"))
    }

    @Test
    fun flushNeedsEnoughUnreportedStrokes() {
        repeat(PredictionRankingReporter.FLUSH_MIN_STROKES - 1) { reporter.onBrushStroke("r", 60f) }
        reporter.flush("r", 60f)
        assertEquals(0, sent.size)
        reporter.onBrushStroke("r", 60f)
        reporter.flush("r", 60f)
        assertEquals(1, sent.size)
        reporter.flush("r", 60f)
        assertEquals(1, sent.size)
    }
}
