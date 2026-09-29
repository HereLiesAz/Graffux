package com.hereliesaz.graffitixr.feature.editor.prediction

/**
 * TEMPORARY. Decides when the stroke-prediction ranking becomes a GitHub issue and what it says;
 * [send] does the filing (PredictionReportRepository, via EditorViewModel). Remove with it.
 *
 * Fires every [REPORT_EVERY_STROKES] Brush strokes, and on [flush] (editor closed) when at least
 * [FLUSH_MIN_STROKES] are unreported. Each issue carries the session totals so far.
 *
 * Posting semantics (each set of stats is filed at most once):
 * - Only strokes drawn since the last *successful* post count as unreported. With none, [flush]
 *   and [onBrushStroke] file nothing, however often they are called (onStop/onResume churn).
 * - New strokes after a post make the next report; its body still carries the session totals so
 *   far (the tournament's ranking is cumulative), but it is only filed because of the new strokes.
 * - [send] reports back through its `done` callback. On failure the batch's strokes go back to
 *   unreported, so the next trigger (threshold, flush, or next launch via [PendingStore]) retries
 *   them. On success nothing is re-sent, and [PendingStore.clear] drops the crash-recovery copy so
 *   CrashIssueUploader cannot file it a second time on the next launch.
 *
 * That last point was the duplicate-issue bug: the pending copy was written after every stroke
 * but deleted only when a later stroke found nothing unreported, which never happens. So after a
 * [flush] posted the session, the stale copy stayed in cacheDir and the next launch filed the
 * same numbers again.
 *
 * Every stroke is tagged with the engine that drew it ([ENGINE_AZPHALT], the editor's own
 * live-stroke pipeline, or [ENGINE_JETPACK_INK] when an Ink utensil is in hand), and the
 * issue title and body name each engine behind the strokes it covers — feel numbers from the two
 * engines are not comparable, so a report must never leave a reader guessing which one it measured.
 */
class PredictionRankingReporter(
    private val device: String,
    private val pendingStore: PendingStore = PendingStore.NONE,
    private val send: (title: String, body: String, done: (success: Boolean) -> Unit) -> Unit,
) {
    /** Crash-recovery copy of [pendingIssue], filed on next launch if this process dies first. */
    interface PendingStore {
        fun save(title: String, body: String)
        fun clear()

        companion object {
            val NONE = object : PendingStore {
                override fun save(title: String, body: String) = Unit
                override fun clear() = Unit
            }
        }
    }

    private var totalStrokes = 0
    private var unreported = 0
    // Engine -> strokes it drew since the last filed issue. Insertion-ordered so the tag is stable.
    private val unreportedByEngine = LinkedHashMap<String, Int>()

    @Synchronized
    fun onBrushStroke(report: String, refreshRateHz: Float, engine: String = ENGINE_AZPHALT) {
        totalStrokes += 1
        unreported += 1
        unreportedByEngine[engine] = (unreportedByEngine[engine] ?: 0) + 1
        if (unreported >= REPORT_EVERY_STROKES) {
            emit(report, refreshRateHz)
        } else {
            pendingStore.save(issueTitle(device, totalStrokes, engineTag()), body(report, refreshRateHz))
        }
    }

    @Synchronized
    fun flush(report: String, refreshRateHz: Float) {
        if (unreported >= FLUSH_MIN_STROKES) emit(report, refreshRateHz)
    }

    /** The issue [onBrushStroke] would file right now, or null when everything is reported. */
    @Synchronized
    fun pendingIssue(report: String, refreshRateHz: Float): Pair<String, String>? =
        if (unreported == 0) {
            null
        } else {
            issueTitle(device, totalStrokes, engineTag()) to body(report, refreshRateHz)
        }

    private fun body(report: String, refreshRateHz: Float) =
        issueBody(device, refreshRateHz, totalStrokes, report, engineTag())

    private fun emit(report: String, refreshRateHz: Float) {
        val engines = engineTag()
        val title = issueTitle(device, totalStrokes, engines)
        val body = issueBody(device, refreshRateHz, totalStrokes, report, engines)
        // Keep the recovery copy until the post is confirmed, in case the process dies mid-post.
        pendingStore.save(title, body)
        val batch = unreported
        val batchByEngine = LinkedHashMap(unreportedByEngine)
        unreported = 0
        unreportedByEngine.clear()
        send(title, body) { success -> onSent(success, batch, batchByEngine) }
    }

    @Synchronized
    private fun onSent(success: Boolean, batch: Int, batchByEngine: Map<String, Int>) {
        if (success) {
            // Strokes drawn while the post was in flight are still unreported and already saved.
            if (unreported == 0) pendingStore.clear()
        } else {
            unreported += batch
            batchByEngine.forEach { (engine, n) -> unreportedByEngine[engine] = (unreportedByEngine[engine] ?: 0) + n }
        }
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
