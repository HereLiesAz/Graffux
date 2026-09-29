package com.hereliesaz.graffitixr.feature.editor.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionRankingReporterTest {
    private val sent = mutableListOf<Pair<String, String>>()
    private val reporter = PredictionRankingReporter("Pixel 9, Android 17") { t, b, done ->
        sent += t to b
        done(true)
    }

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

/** At-most-once posting: the duplicate-issue bug (#473/#475, #476/#482, #489/#495, #456/#458). */
class PredictionRankingReporterDedupTest {
    private class FakeStore : PredictionRankingReporter.PendingStore {
        var saved: Pair<String, String>? = null
        override fun save(title: String, body: String) {
            saved = title to body
        }
        override fun clear() {
            saved = null
        }
    }

    private val store = FakeStore()
    private val sent = mutableListOf<Pair<String, String>>()
    private var succeed = true
    private val reporter = PredictionRankingReporter("Pixel", store) { t, b, done ->
        sent += t to b
        done(succeed)
    }

    private fun strokes(n: Int) = repeat(n) { reporter.onBrushStroke("r", 60f) }

    @Test
    fun postingTwiceWithoutNewStrokesPostsOnce() {
        strokes(PredictionRankingReporter.FLUSH_MIN_STROKES)
        reporter.flush("r", 60f)
        reporter.flush("r", 60f) // onStop/onResume churn
        reporter.flush("r", 60f)
        assertEquals(1, sent.size)
        // The crash-recovery copy is gone, so the next launch has nothing to re-file.
        assertNull(store.saved)
        assertNull(reporter.pendingIssue("r", 60f))
    }

    /**
     * Semantics: a new report is filed only because of strokes drawn since the last successful
     * post; its body carries the session totals so far (the ranking is cumulative).
     */
    @Test
    fun newStrokesAfterAPostProduceASecondReport() {
        strokes(PredictionRankingReporter.FLUSH_MIN_STROKES)
        reporter.flush("r", 60f)
        strokes(PredictionRankingReporter.FLUSH_MIN_STROKES - 1)
        reporter.flush("r", 60f)
        assertEquals("too few new strokes to file", 1, sent.size)
        assertTrue("new strokes are saved for recovery", store.saved != null)
        strokes(1)
        reporter.flush("r", 60f)
        assertEquals(2, sent.size)
        assertTrue(sent[0].first.contains("5 strokes"))
        assertTrue(sent[1].first.contains("10 strokes"))
        reporter.flush("r", 60f)
        assertEquals(2, sent.size)
        assertNull(store.saved)
    }

    @Test
    fun failedPostIsRetriedButSuccessfulOneIsNot() {
        succeed = false
        strokes(PredictionRankingReporter.FLUSH_MIN_STROKES)
        reporter.flush("r", 60f)
        assertEquals(1, sent.size)
        assertTrue("failed batch is kept for the next launch", store.saved != null)

        succeed = true
        reporter.flush("r", 60f) // retry, no new strokes needed
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertNull(store.saved)

        reporter.flush("r", 60f)
        assertEquals(2, sent.size)
    }

    @Test
    fun strokesDrawnWhileAPostIsInFlightStayUnreported() {
        var pending: ((Boolean) -> Unit)? = null
        val async = PredictionRankingReporter("Pixel", store) { t, b, done ->
            sent += t to b
            pending = done
        }
        repeat(PredictionRankingReporter.FLUSH_MIN_STROKES) { async.onBrushStroke("r", 60f) }
        async.flush("r", 60f)
        async.onBrushStroke("r", 60f)
        pending!!(true)
        assertTrue("in-flight stroke still saved", store.saved != null)
        assertTrue(async.pendingIssue("r", 60f)!!.first.contains("6 strokes"))
    }
}

class PredictionRankingReporterPendingTest {
    @Test
    fun pendingIssueTracksUnfiledStrokesAndClearsOnSend() {
        val reporter = PredictionRankingReporter("Pixel") { _, _, done -> done(true) }
        assertEquals(null, reporter.pendingIssue("r", 60f))
        reporter.onBrushStroke("r", 60f)
        val pending = reporter.pendingIssue("r", 60f)!!
        assertTrue(pending.first.contains("1 strokes"))
        repeat(PredictionRankingReporter.REPORT_EVERY_STROKES - 1) { reporter.onBrushStroke("r", 60f) }
        assertEquals(null, reporter.pendingIssue("r", 60f))
    }
}
