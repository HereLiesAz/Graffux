package com.hereliesaz.graffitixr.feature.editor.prediction

/**
 * TEMPORARY. Decides when the stroke-prediction ranking becomes a GitHub issue and what it says;
 * [send] does the filing (PredictionReportRepository, via EditorViewModel). Remove with it.
 *
 * Fires every [REPORT_EVERY_STROKES] Brush strokes, and on [flush] (editor closed) when at least
 * [FLUSH_MIN_STROKES] are unreported. Each issue carries the session totals so far.
 */
class PredictionRankingReporter(
    private val device: String,
    private val send: (title: String, body: String) -> Unit,
) {
    private var totalStrokes = 0
    private var unreported = 0

    fun onBrushStroke(report: String, refreshRateHz: Float) {
        totalStrokes += 1
        unreported += 1
        if (unreported >= REPORT_EVERY_STROKES) emit(report, refreshRateHz)
    }

    fun flush(report: String, refreshRateHz: Float) {
        if (unreported >= FLUSH_MIN_STROKES) emit(report, refreshRateHz)
    }

    /**
     * The issue [onBrushStroke] would file right now, or null when everything is reported. Saved
     * to disk after every stroke so a crash before the next batch doesn't lose the ranking; the
     * next launch files it (CrashIssueUploader).
     */
    fun pendingIssue(report: String, refreshRateHz: Float): Pair<String, String>? =
        if (unreported == 0) {
            null
        } else {
            issueTitle(device, totalStrokes) to issueBody(device, refreshRateHz, totalStrokes, report)
        }

    private fun emit(report: String, refreshRateHz: Float) {
        unreported = 0
        send(issueTitle(device, totalStrokes), issueBody(device, refreshRateHz, totalStrokes, report))
    }

    companion object {
        const val REPORT_EVERY_STROKES = 25
        const val FLUSH_MIN_STROKES = 5

        /** cacheDir file holding [pendingIssue]: title on the first line, body after. */
        const val PENDING_FILE = "prediction_ranking_pending.txt"

        internal fun issueTitle(device: String, strokes: Int) =
            "[prediction-ranking] $device, $strokes strokes"

        internal fun issueBody(device: String, refreshRateHz: Float, strokes: Int, report: String) = """
            |Automatic stroke-prediction ranking (temporary; see `PredictionRankingReporter`).
            |
            |- Device: $device
            |- Display: ${"%.0f".format(refreshRateHz)} Hz (frame ${"%.1f".format(MS_PER_SECOND / refreshRateHz)} ms)
            |- Brush strokes this session: $strokes
            |
            |Mean distance from the real pen position per frame ahead (f1 = next frame), best first.
            |`lead` is the signed error along the direction of travel: + = ran ahead (overshoot).
            |`tail(damped)` is the tail actually drawn.
            |
            |~~~
            |$report
            |~~~
        """.trimMargin()

        private const val MS_PER_SECOND = 1000f
    }
}
