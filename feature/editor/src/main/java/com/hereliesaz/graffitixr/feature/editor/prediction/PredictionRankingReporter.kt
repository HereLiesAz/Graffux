package com.hereliesaz.graffitixr.feature.editor.prediction

/**
 * TEMPORARY. Decides when the stroke-prediction ranking becomes a GitHub issue and what it says;
 * [send] does the filing (PredictionReportRepository, via EditorViewModel). Remove with it.
 *
 * Fires every [REPORT_EVERY_STROKES] Brush strokes, and on [flush] (editor closed) when at least
 * [FLUSH_MIN_STROKES] are unreported. Each issue carries the session totals so far.
 *
 * Every stroke is tagged with the engine that drew it ([ENGINE_AZPHALT], the editor's own
 * live-stroke pipeline, or [ENGINE_JETPACK_INK] when an Ink utensil is in hand), and the
 * issue title and body name each engine behind the strokes it covers — feel numbers from the two
 * engines are not comparable, so a report must never leave a reader guessing which one it measured.
 */
class PredictionRankingReporter(
    private val device: String,
    private val send: (title: String, body: String) -> Unit,
) {
    private var totalStrokes = 0
    private var unreported = 0
    // Engine -> strokes it drew since the last filed issue. Insertion-ordered so the tag is stable.
    private val unreportedByEngine = LinkedHashMap<String, Int>()

    fun onBrushStroke(report: String, refreshRateHz: Float, engine: String = ENGINE_AZPHALT) {
        totalStrokes += 1
        unreported += 1
        unreportedByEngine[engine] = (unreportedByEngine[engine] ?: 0) + 1
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
            issueTitle(device, totalStrokes, engineTag()) to
                issueBody(device, refreshRateHz, totalStrokes, report, engineTag())
        }

    private fun emit(report: String, refreshRateHz: Float) {
        val engines = engineTag()
        unreported = 0
        unreportedByEngine.clear()
        send(issueTitle(device, totalStrokes, engines), issueBody(device, refreshRateHz, totalStrokes, report, engines))
    }

    /** e.g. `jetpack-ink` or, for a batch that spans a toggle, `azphalt 12 + jetpack-ink 13`. */
    private fun engineTag(): String = when (unreportedByEngine.size) {
        0 -> ENGINE_AZPHALT
        1 -> unreportedByEngine.keys.first()
        else -> unreportedByEngine.entries.joinToString(" + ") { "${it.key} ${it.value}" }
    }

    companion object {
        const val REPORT_EVERY_STROKES = 25
        const val FLUSH_MIN_STROKES = 5

        /** The editor's own live-stroke pipeline (stamp engine + provisional ink + prediction tail). */
        const val ENGINE_AZPHALT = "azphalt"

        /** Jetpack Ink's InProgressStrokesView live stroke, committed through CanvasStrokeRenderer. */
        const val ENGINE_JETPACK_INK = "jetpack-ink"

        /** cacheDir file holding [pendingIssue]: title on the first line, body after. */
        const val PENDING_FILE = "prediction_ranking_pending.txt"

        internal fun issueTitle(device: String, strokes: Int, engine: String = ENGINE_AZPHALT) =
            "[prediction-ranking] $device, $strokes strokes, engine: $engine"

        internal fun issueBody(
            device: String,
            refreshRateHz: Float,
            strokes: Int,
            report: String,
            engine: String = ENGINE_AZPHALT,
        ) = """
            |Automatic stroke-prediction ranking (temporary; see `PredictionRankingReporter`).
            |
            |- Device: $device
            |- Display: ${"%.0f".format(refreshRateHz)} Hz (frame ${"%.1f".format(MS_PER_SECOND / refreshRateHz)} ms)
            |- Brush strokes this session: $strokes
            |- engine: $engine
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
