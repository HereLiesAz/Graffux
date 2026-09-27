package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.geometry.Offset
import kotlin.math.max

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
    val confidence: Float = 1f,
)

interface GesturePredictor {
    val name: String
    fun reset()
    fun record(sample: GestureSample)
    fun predict(targetUptimeMillis: Long): GesturePrediction?

    /**
     * One prediction per entry of [targetUptimeMillis] (ascending). The default asks [predict] for
     * each target; models that pick their own horizon may return a different
     * [GesturePrediction.targetUptimeMillis], which [PredictionTournament] rescales onto the
     * requested frame. Override when the model has a real multi-point trajectory (Google Ink).
     */
    fun predictTrajectory(targetUptimeMillis: List<Long>): List<GesturePrediction?> =
        targetUptimeMillis.map { predict(it) }
}

/** Mean error of one model at one frame horizon, accumulated across every stroke this session. */
data class HorizonScore(
    val model: String,
    val horizonFrames: Int,
    val meanErrorPx: Float,
    val samples: Int,
)

/** Cheap baseline: project the most recent velocity forward. */
class LinearGesturePredictor : GesturePredictor {
    override val name: String = "linear"
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

/** Constant-acceleration baseline using the last three real samples. */
class AccelerationGesturePredictor : GesturePredictor {
    override val name: String = "acceleration"
    private val samples = ArrayDeque<GestureSample>(3)

    override fun reset() = samples.clear()

    override fun record(sample: GestureSample) {
        samples.addLast(sample)
        while (samples.size > 3) samples.removeFirst()
    }

    override fun predict(targetUptimeMillis: Long): GesturePrediction? {
        if (samples.size < 3) return null
        val a = samples[0]
        val b = samples[1]
        val c = samples[2]
        val dt1 = (b.uptimeMillis - a.uptimeMillis).toFloat()
        val dt2 = (c.uptimeMillis - b.uptimeMillis).toFloat()
        if (dt1 <= 0f || dt2 <= 0f) return null
        val v1 = (b.position - a.position) / dt1
        val v2 = (c.position - b.position) / dt2
        val avgDt = max((dt1 + dt2) * 0.5f, 1f)
        val acceleration = (v2 - v1) / avgDt
        val future = (targetUptimeMillis - c.uptimeMillis).coerceAtLeast(0L).toFloat()
        val predicted = c.position + v2 * future + acceleration * (0.5f * future * future)
        return GesturePrediction(name, predicted, targetUptimeMillis, c.pressure)
    }
}

/**
 * Scores predictors against the real samples that eventually arrive. Predictions are presentation
 * only; this class never mutates the authoritative stroke.
 *
 * Every [predict] asks each model for the next [HORIZON_FRAMES] frames (1 = the next frame, 2 = the
 * one after, ...). A model that chose its own horizon (AndroidX, Google Ink's single endpoint) is
 * rescaled along the line from the latest real sample to its own prediction, so every model is
 * judged at the same instants. When real input passes a prediction's target time, the true
 * position there is interpolated between the two real samples that bracket it and the distance is
 * scored twice:
 * - Horizon 1 only feeds the per-stroke exponential average that picks the drawn tail. It resets
 *   every stroke, exactly as before.
 * - Every horizon feeds a session-long mean per (model, horizon) -- [rankings]/[rankingReport].
 *   This survives [reset], so it accumulates across strokes until [resetRankings].
 *
 * Google Ink is opportunistic: on a real Android process its native model joins automatically; on
 * JVM/Robolectric (where the native .so deliberately does not exist) construction fails harmlessly
 * and the pure Kotlin + AndroidX competitors continue. As a [RememberObserver], this tournament also
 * closes native predictors when its Compose owner leaves composition, so a prediction aid cannot
 * leak a native model merely because the editor screen was closed between strokes.
 */
@Suppress("TooManyFunctions") // Scoring, ranking and Compose lifecycle hooks; splitting adds nothing.
class PredictionTournament(
    suppliedPredictors: List<GesturePredictor>,
    private val errorSmoothing: Float = 0.2f,
    includeGoogleInk: Boolean = true,
) : RememberObserver {
    private val predictors: List<GesturePredictor> = buildList {
        addAll(suppliedPredictors)
        if (includeGoogleInk) runCatching { GoogleInkGesturePredictor() }.getOrNull()?.let(::add)
    }.distinctBy { it.name }

    private class Pending(val prediction: GesturePrediction, val horizonFrames: Int)
    private class Accumulator(var sumPx: Double = 0.0, var count: Int = 0)

    private val errors = predictors.associate { it.name to Float.POSITIVE_INFINITY }.toMutableMap()
    private val scoreCounts = predictors.associate { it.name to 0 }.toMutableMap()
    private val pending = ArrayDeque<Pending>()
    private val session = HashMap<Pair<String, Int>, Accumulator>()
    private var lastReal: GestureSample? = null

    init {
        require(errorSmoothing in 0f..1f)
    }

    /** New stroke: clears model state, pending predictions and tail selection -- not [rankings]. */
    fun reset() {
        predictors.forEach { it.reset() }
        errors.keys.forEach { errors[it] = Float.POSITIVE_INFINITY }
        scoreCounts.keys.forEach { scoreCounts[it] = 0 }
        pending.clear()
        lastReal = null
    }

    fun resetRankings() = session.clear()

    /** Record a real sample and score every prediction whose target time it has reached. */
    fun record(sample: GestureSample) {
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
     * Predicts the next [HORIZON_FRAMES] frames, one frame apart, with [targetUptimeMillis] as frame
     * 1. Returns horizon 1 from whichever model has the lowest recent horizon-1 error (the tail).
     */
    fun predict(targetUptimeMillis: Long): GesturePrediction? {
        val anchor = lastReal
        val frameMs = (targetUptimeMillis - (anchor?.uptimeMillis ?: targetUptimeMillis)).coerceAtLeast(1L)
        val targets = List(HORIZON_FRAMES) { targetUptimeMillis + it * frameMs }

        val aligned = predictors.flatMap { predictor ->
            val trajectory = predictor.predictTrajectory(targets)
            targets.indices.mapNotNull { h ->
                trajectory.getOrNull(h)?.let { alignTo(anchor, it, targets[h]) }?.let { Pending(it, h + 1) }
            }
        }
        pending.addAll(aligned)
        val nextFrame = aligned.filter { it.horizonFrames == 1 }.map { it.prediction }
        while (pending.size > predictors.size * HORIZON_FRAMES * 8) pending.removeFirst()
        if (nextFrame.isEmpty()) return null

        return nextFrame.minByOrNull { prediction ->
            val count = scoreCounts.getValue(prediction.model)
            val error = errors.getValue(prediction.model)
            when {
                count == 0 -> 0f
                !error.isFinite() -> Float.MAX_VALUE
                else -> error
            }
        }
    }

    /** Horizon-1 per-stroke exponential average, best first (drives tail selection). */
    fun leaderboard(): List<Pair<String, Float>> = predictors
        .map { it.name to errors.getValue(it.name) }
        .sortedBy { it.second }

    /** Session mean error per horizon (1..[HORIZON_FRAMES]), each list best first. */
    fun rankings(): Map<Int, List<HorizonScore>> = (1..HORIZON_FRAMES).associateWith { h ->
        predictors.mapNotNull { predictor ->
            session[predictor.name to h]?.takeIf { it.count > 0 }?.let {
                HorizonScore(predictor.name, h, (it.sumPx / it.count).toFloat(), it.count)
            }
        }.sortedBy { it.meanErrorPx }
    }

    /** One line per horizon: `f1: google-ink 2.1px (n=412) > linear 3.4px (n=430) > ...`. */
    fun rankingReport(): String = rankings().entries.joinToString("\n") { (h, scores) ->
        "f$h: " + if (scores.isEmpty()) {
            "no data"
        } else {
            scores.joinToString(" > ") { "${it.model} ${"%.1f".format(it.meanErrorPx)}px (n=${it.samples})" }
        }
    }

    private fun score(entry: Pending, actual: Offset) {
        val prediction = entry.prediction
        val error = (prediction.position - actual).getDistance()
        session.getOrPut(prediction.model to entry.horizonFrames) { Accumulator() }.apply {
            sumPx += error
            count += 1
        }
        if (entry.horizonFrames != 1) return
        val count = scoreCounts.getValue(prediction.model)
        val previous = errors.getValue(prediction.model)
        errors[prediction.model] = if (count == 0 || !previous.isFinite()) {
            error
        } else {
            previous * (1f - errorSmoothing) + error * errorSmoothing
        }
        scoreCounts[prediction.model] = count + 1
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
    }
}

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

/**
 * Moves a prediction that targeted its own time onto [target] along the line from the latest real
 * sample ([anchor]) through it. Null when that line is undefined.
 */
internal fun alignTo(anchor: GestureSample?, prediction: GesturePrediction, target: Long): GesturePrediction? {
    val span = anchor?.let { prediction.targetUptimeMillis - it.uptimeMillis } ?: 0L
    return when {
        prediction.targetUptimeMillis == target -> prediction
        anchor == null || span <= 0L -> null
        else -> {
            val f = (target - anchor.uptimeMillis).toFloat() / span
            prediction.copy(
                position = anchor.position + (prediction.position - anchor.position) * f,
                targetUptimeMillis = target,
            )
        }
    }
}
