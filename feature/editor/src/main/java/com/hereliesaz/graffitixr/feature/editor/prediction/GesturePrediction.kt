package com.hereliesaz.graffitixr.feature.editor.prediction

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
}

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
 * only; this class never mutates the authoritative stroke. The winner is the model with the lowest
 * exponentially-smoothed pixel error, with a tiny exploration bias toward models with fewer scores.
 */
class PredictionTournament(
    predictors: List<GesturePredictor>,
    private val errorSmoothing: Float = 0.2f,
) {
    private val predictors = predictors.toList()
    private val errors = predictors.associate { it.name to Float.POSITIVE_INFINITY }.toMutableMap()
    private val scoreCounts = predictors.associate { it.name to 0 }.toMutableMap()
    private val pending = ArrayDeque<List<GesturePrediction>>()

    init {
        require(this.predictors.map { it.name }.distinct().size == this.predictors.size)
        require(errorSmoothing in 0f..1f)
    }

    fun reset() {
        predictors.forEach { it.reset() }
        errors.keys.forEach { errors[it] = Float.POSITIVE_INFINITY }
        scoreCounts.keys.forEach { scoreCounts[it] = 0 }
        pending.clear()
    }

    /** Record a real sample and score any predictions whose target time has now arrived. */
    fun record(sample: GestureSample) {
        while (pending.isNotEmpty()) {
            val batch = pending.first()
            val target = batch.firstOrNull()?.targetUptimeMillis ?: run {
                pending.removeFirst()
                continue
            }
            if (target > sample.uptimeMillis) break
            pending.removeFirst()
            batch.forEach { prediction ->
                val error = (prediction.position - sample.position).getDistance()
                val count = scoreCounts.getValue(prediction.model)
                val previous = errors.getValue(prediction.model)
                errors[prediction.model] = if (count == 0 || !previous.isFinite()) {
                    error
                } else {
                    previous * (1f - errorSmoothing) + error * errorSmoothing
                }
                scoreCounts[prediction.model] = count + 1
            }
        }
        predictors.forEach { it.record(sample) }
    }

    fun predict(targetUptimeMillis: Long): GesturePrediction? {
        val predictions = predictors.mapNotNull { it.predict(targetUptimeMillis) }
        if (predictions.isEmpty()) return null
        pending.addLast(predictions)
        while (pending.size > 8) pending.removeFirst()
        return predictions.minByOrNull { prediction ->
            val count = scoreCounts.getValue(prediction.model)
            val error = errors.getValue(prediction.model)
            when {
                count == 0 -> -1f / (1 + count)
                !error.isFinite() -> Float.MAX_VALUE
                else -> error
            }
        }
    }

    fun leaderboard(): List<Pair<String, Float>> = predictors
        .map { it.name to errors.getValue(it.name) }
        .sortedBy { it.second }
}
