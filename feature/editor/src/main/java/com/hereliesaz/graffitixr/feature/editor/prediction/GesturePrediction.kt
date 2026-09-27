package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.geometry.Offset

/** One real pointer sample. Predicted samples never enter this stream. */
data class GestureSample(
    val position: Offset,
    val uptimeMillis: Long,
    val pressure: Float = 1f,
)

data class GesturePrediction(
    val model: String,
    val position: Offset,
    val targetUptimeMillis: Long,
    val pressure: Float = 1f,
)

interface GesturePredictor {
    val name: String
    fun reset()
    fun record(sample: GestureSample)

    /** Where the pen will be at [targetUptimeMillis]; null when the model can't say yet. */
    fun predict(targetUptimeMillis: Long): GesturePrediction?
}

/**
 * The drawn tail: the pen's predicted path from the latest real sample, in order, ending at the
 * tail's reach. Several points, not one, so the tail follows Ink's curve instead of a straight line.
 */
data class PredictionTail(val model: String, val points: List<Offset>)

/** Mean error of one model at one frame horizon, accumulated across every stroke this session. */
data class HorizonScore(
    val model: String,
    val horizonFrames: Int,
    val meanErrorPx: Float,
    val samples: Int,
    /** Mean signed error along the direction of travel: + = ran ahead of the pen (overshoot). */
    val meanLeadPx: Float = 0f,
)

/**
 * Projects the most recent velocity forward. Kept as the fallback for the first few samples of a
 * stroke, before Google Ink's Kalman filters are stable enough to predict.
 */
class LinearGesturePredictor : GesturePredictor {
    override val name: String = LINEAR
    private var previous: GestureSample? = null
    private var latest: GestureSample? = null

    override fun reset() {
        previous = null
        latest = null
    }

    override fun record(sample: GestureSample) {
        previous = latest
        latest = sample
    }

    override fun predict(targetUptimeMillis: Long): GesturePrediction? {
        val a = previous ?: return null
        val b = latest ?: return null
        val dt = b.uptimeMillis - a.uptimeMillis
        if (dt <= 0L) return null
        val futureMs = (targetUptimeMillis - b.uptimeMillis).coerceAtLeast(0L)
        val scale = futureMs.toFloat() / dt.toFloat()
        return GesturePrediction(
            model = name,
            position = b.position + (b.position - a.position) * scale,
            targetUptimeMillis = targetUptimeMillis,
            pressure = b.pressure,
        )
    }
}

/**
 * Stroke prediction for the presentation-only tail. Predictions never touch the authoritative
 * stroke.
 *
 * **Which model draws the tail.** Google Ink's Kalman predictor, used directly, is the predictor.
 * [LinearGesturePredictor] covers only the first few samples of a stroke, before Ink's filters are
 * stable. The two are tried in that fixed order; there is no per-stroke contest. This was settled
 * by on-device rankings (issues #425, #426, #435-#439, Pixel 5): AndroidX answered only ~30% of
 * samples and trailed the pen 80 px four frames out, acceleration overshot worst, and a damped tail
 * didn't beat the undamped one, so all three were removed.
 *
 * **How far ahead.** The tail should cover exactly the lag between touch and paint, no more: the
 * caller passes the measured touch-to-paint latency, and the tail reaches that far, clamped to
 * between one frame and [TAIL_FRAMES] frames. Every model's error roughly doubles per frame, and by
 * frames 3-4 it is 100+ px, which reads as a wrong line rather than a lead. Without a measurement
 * the tail reaches [TAIL_FRAMES] frames.
 *
 * **Rankings.** Every [predict] still asks each running model for the next [HORIZON_FRAMES] frames
 * and scores them when real input passes each target time. The true position there is interpolated
 * between the real samples around it, or taken at the pen-up point for predictions still pending
 * at lift ([endStroke]). [rankings]/[rankingReport] keep a session-long mean per (model, horizon),
 * with the distance and the signed "lead" along the direction of travel. Survives [reset] until
 * [resetRankings].
 *
 * Google Ink joins automatically on a real Android process. On JVM/Robolectric its native library
 * is absent, construction fails harmlessly, and linear runs alone. As a [RememberObserver] this
 * closes the native predictor when its Compose owner leaves composition.
 */
@Suppress("TooManyFunctions") // Scoring, ranking and Compose lifecycle hooks; splitting adds nothing.
class PredictionTournament(
    suppliedPredictors: List<GesturePredictor>,
    includeGoogleInk: Boolean = true,
    /**
     * Run only the predictor with this name (see [MODEL_NAMES]). Null, or a name no predictor has,
     * runs them all.
     */
    soloModel: String? = null,
    /** Google Ink tuning (TEMPORARY Settings choice, reported so profiles can be ranked). */
    private val inkProfile: GoogleInkGesturePredictor.Profile = GoogleInkGesturePredictor.Profile.STANDARD,
) : RememberObserver {
    /** Tail priority order: Google Ink first, then whatever was supplied (the linear fallback). */
    private val predictors: List<GesturePredictor> = run {
        val all = buildList {
            if (includeGoogleInk && (soloModel == null || soloModel == GOOGLE_INK)) {
                runCatching { GoogleInkGesturePredictor(inkProfile) }.getOrNull()?.let(::add)
            }
            addAll(suppliedPredictors)
        }.distinctBy { it.name }
        all.filter { it.name == soloModel }.ifEmpty { all }
    }

    /** Names of the predictors actually running, in tail priority order, for reports. */
    val activeModels: List<String> get() = predictors.map { it.name }

    private class Pending(
        val prediction: GesturePrediction,
        val horizonFrames: Int,
        /** Latest real position when predicted; direction of travel is anchor -> actual. */
        val anchor: Offset?,
    )
    private class Accumulator(var sumPx: Double = 0.0, var sumLeadPx: Double = 0.0, var count: Int = 0)

    private val pending = ArrayDeque<Pending>()
    private val session = HashMap<Pair<String, Int>, Accumulator>()
    private var lastReal: GestureSample? = null

    // Cost of record() + predict() per real sample on the calling (UI) thread, for deciding
    // whether prediction needs to move off it. Session-long, like the rankings.
    private var costSumNs = 0L
    private var costMaxNs = 0L
    private var costCount = 0
    private var recordStartNs = 0L

    /** New stroke: clears model state and pending predictions, not [rankings]. */
    fun reset() {
        predictors.forEach { it.reset() }
        pending.clear()
        lastReal = null
    }

    fun resetRankings() {
        session.clear()
        costSumNs = 0L
        costMaxNs = 0L
        costCount = 0
    }

    /**
     * Pen lifted: score every still-pending prediction against the lift point, where the pen
     * stopped. Without this they were discarded at the next [reset], and those are exactly the
     * predictions that run past the end of a stroke -- so overshoot went unmeasured and every
     * model's lead read more negative than it is. [liftPosition] is the pen-up point when known
     * (it can arrive before the last move sample is recorded); otherwise the last recorded sample.
     */
    fun endStroke(liftPosition: Offset? = null) {
        val lift = liftPosition ?: lastReal?.position ?: return
        while (pending.isNotEmpty()) score(pending.removeFirst(), lift)
    }

    /** Record a real sample and score every prediction whose target time it has reached. */
    fun record(sample: GestureSample) {
        recordStartNs = System.nanoTime()
        val previous = lastReal
        if (pending.isNotEmpty()) {
            val survivors = ArrayDeque<Pending>(pending.size)
            while (pending.isNotEmpty()) {
                val entry = pending.removeFirst()
                if (entry.prediction.targetUptimeMillis <= sample.uptimeMillis) {
                    score(entry, actualAt(previous, sample, entry.prediction.targetUptimeMillis))
                } else {
                    survivors.addLast(entry)
                }
            }
            pending.addAll(survivors)
        }
        predictors.forEach { it.record(sample) }
        lastReal = sample
    }

    /**
     * [targetUptimeMillis] is the next display frame. Predicts [HORIZON_FRAMES] frames, one frame
     * apart, for the rankings, and returns the tail from the first model in priority order that can
     * predict its reach. [tailLeadMs] is how far behind the pen the paint actually is (measured
     * touch-to-paint latency); the tail reaches that far, clamped to 1..[TAIL_FRAMES] frames.
     */
    fun predict(targetUptimeMillis: Long, tailLeadMs: Long? = null): PredictionTail? {
        val anchor = lastReal
        val frameMs = (targetUptimeMillis - (anchor?.uptimeMillis ?: targetUptimeMillis)).coerceAtLeast(1L)
        val targets = List(HORIZON_FRAMES) { targetUptimeMillis + it * frameMs }
        for (predictor in predictors) {
            targets.forEachIndexed { h, target ->
                predictor.predict(target)?.let { pending.addLast(Pending(it, h + 1, anchor?.position)) }
            }
        }
        while (pending.size > predictors.size * HORIZON_FRAMES * PENDING_FRAMES_KEPT) pending.removeFirst()

        val tail = anchor?.let { buildTail(it, frameMs, tailLeadMs) }
        if (recordStartNs > 0L) {
            val cost = System.nanoTime() - recordStartNs
            costSumNs += cost
            costMaxNs = maxOf(costMaxNs, cost)
            costCount += 1
            recordStartNs = 0L
        }
        return tail
    }

    private fun buildTail(anchor: GestureSample, frameMs: Long, tailLeadMs: Long?): PredictionTail? {
        val reachMs = (tailLeadMs ?: (TAIL_FRAMES * frameMs)).coerceIn(frameMs, TAIL_FRAMES * frameMs)
        val end = anchor.uptimeMillis + reachMs
        val times = (1 until TAIL_FRAMES).map { anchor.uptimeMillis + it * frameMs }.filter { it < end } + end
        for (predictor in predictors) {
            val points = times.map { predictor.predict(it)?.position ?: return@map null }
            if (points.all { it != null }) return PredictionTail(predictor.name, points.filterNotNull())
        }
        return null
    }

    /** Session mean error per horizon (1..[HORIZON_FRAMES]), each list best first. */
    fun rankings(): Map<Int, List<HorizonScore>> = (1..HORIZON_FRAMES).associateWith { h ->
        predictors.mapNotNull { predictor ->
            session[predictor.name to h]?.takeIf { it.count > 0 }?.let {
                HorizonScore(
                    predictor.name, h, (it.sumPx / it.count).toFloat(), it.count,
                    (it.sumLeadPx / it.count).toFloat(),
                )
            }
        }.sortedBy { it.meanErrorPx }
    }

    /** One line per horizon: `f1: google-ink 2.1px lead +0.4 (n=412) > linear 3.4px ...`. */
    fun rankingReport(): String = "models: ${activeModels.joinToString()}, ink: ${inkProfile.label} " +
        "(tail: measured lag, max $TAIL_FRAMES frames)\n" + costLine() +
        rankings().entries.joinToString("\n") { (h, scores) ->
            "f$h: " + if (scores.isEmpty()) {
                "no data"
            } else {
                scores.joinToString(" > ") {
                    "${it.model} ${"%.1f".format(it.meanErrorPx)}px " +
                        "lead ${"%+.1f".format(it.meanLeadPx)} (n=${it.samples})"
                }
            }
        }

    private fun costLine(): String = if (costCount == 0) {
        ""
    } else {
        "cost per sample: mean ${"%.0f".format(costSumNs / costCount / NS_PER_US)} us, " +
            "max ${"%.0f".format(costMaxNs / NS_PER_US)} us (n=$costCount)\n"
    }

    private fun score(entry: Pending, actual: Offset) {
        val prediction = entry.prediction
        val error = (prediction.position - actual).getDistance()
        val travel = entry.anchor?.let { actual - it }
        val travelLength = travel?.getDistance() ?: 0f
        val lead = if (travel != null && travelLength > 0f) {
            val miss = prediction.position - actual
            (miss.x * travel.x + miss.y * travel.y) / travelLength
        } else {
            0f
        }
        session.getOrPut(prediction.model to entry.horizonFrames) { Accumulator() }.apply {
            sumPx += error
            sumLeadPx += lead
            count += 1
        }
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = closeNativePredictors()
    override fun onAbandoned() = closeNativePredictors()

    private fun closeNativePredictors() {
        predictors.filterIsInstance<AutoCloseable>().forEach { predictor ->
            runCatching { predictor.close() }
        }
    }

    companion object {
        /** Frames ahead every model is asked for and ranked at. */
        const val HORIZON_FRAMES = 4

        /** Frames ahead the drawn tail reaches. */
        const val TAIL_FRAMES = 2

        const val GOOGLE_INK = "google-ink"
        const val ANDROIDX = "androidx"

        /** Every predictor name, in the order Settings offers them for solo runs. */
        val MODEL_NAMES = listOf(GOOGLE_INK, ANDROIDX, LINEAR)

        /** TEMPORARY: SharedPreferences file/key holding the solo model ("" = all). */
        const val SOLO_PREFS = "stroke_prediction"
        const val SOLO_KEY = "solo_model"
        const val INK_PROFILE_KEY = "ink_profile"

        /** Unscored predictions kept per model and horizon before the oldest are dropped. */
        private const val PENDING_FRAMES_KEPT = 8
        private const val NS_PER_US = 1000.0
    }
}

private const val LINEAR = "linear"

/** True position at [time], linearly interpolated between the real samples around it. */
internal fun actualAt(previous: GestureSample?, current: GestureSample, time: Long): Offset {
    val start = previous?.uptimeMillis ?: current.uptimeMillis
    val inside = time > start && time < current.uptimeMillis
    return if (previous == null || !inside) {
        current.position
    } else {
        val f = (time - start).toFloat() / (current.uptimeMillis - start)
        previous.position + (current.position - previous.position) * f
    }
}
