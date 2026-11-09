package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A brush's paint load (spec/painterly.md 3.3). [BrushLoadConfig] is what a pack declares;
 * [BrushLoadBudget] is the Kotlin **prediction** of how that load depletes, which a stroke program can
 * read (`loadAmount`, `loadMix`) to branch on "this lane is nearly dry". The truth of how much paint
 * reached the canvas is the GPU paint transaction's (phase P5); the budget never decides a pixel.
 */
@Serializable
data class BrushLoadFill(
    /** A pigment recipe: `id` or `id+id:weight` (weights default to 1, normalised). */
    val pigment: String,
    val laneFrom: Int,
    val laneTo: Int,
    /** 0..1 of a column's capacity. */
    val amount: Float,
)

@Serializable
data class BrushLoadConfig(
    val lanes: Int = DEFAULT_LANES,
    val depth: Int = DEFAULT_DEPTH,
    val capacity: Float = 1f,
    val fill: List<BrushLoadFill> = emptyList(),
) {
    fun sanitized(): BrushLoadConfig {
        val l = lanes.coerceIn(1, MAX_LANES)
        return copy(
            lanes = l,
            depth = depth.coerceIn(1, MAX_DEPTH),
            capacity = if (capacity.isFinite()) capacity.coerceIn(0.01f, 4f) else 1f,
            fill = fill.map {
                val a = it.laneFrom.coerceIn(0, l - 1)
                val b = it.laneTo.coerceIn(0, l - 1)
                it.copy(laneFrom = minOf(a, b), laneTo = maxOf(a, b), amount = if (it.amount.isFinite()) it.amount.coerceIn(0f, 1f) else 0f)
            },
        )
    }

    companion object {
        const val DEFAULT_LANES = 16
        const val DEFAULT_DEPTH = 3
        const val MAX_LANES = 32
        const val MAX_DEPTH = 4
    }
}

/** A recipe string `a+b:0.5` as (pigment id, normalised weight) pairs; empty for a blank recipe. */
object PigmentRecipe {
    fun parse(text: String): List<Pair<String, Float>> {
        val terms = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }.map { term ->
            val i = term.lastIndexOf(':')
            val weight = if (i > 0) term.substring(i + 1).toFloatOrNull() else null
            if (weight != null && weight.isFinite() && weight >= 0f) term.substring(0, i).trim() to weight
            else term to 1f
        }
        val total = terms.sumOf { it.second.toDouble() }.toFloat()
        if (!(total > 0f)) return emptyList()
        return terms.map { it.first to it.second / total }
    }
}

/** Per-stroke, deterministic, allocation-free-in-the-loop prediction of a brush's load. */
class BrushLoadBudget(config: BrushLoadConfig) {
    private val config = config.sanitized()
    val lanes: Int get() = config.lanes
    private val depth = this.config.depth
    private val cell = this.config.capacity / this.config.depth

    /** Amount per (lane, depth layer); layer 0 is the tip and is spent first. */
    private val amounts = FloatArray(lanes * depth)
    private val secondFraction = FloatArray(lanes)

    init {
        for (fill in this.config.fill) {
            val recipe = PigmentRecipe.parse(fill.pigment)
            // The budget needs only how much of a column's paint is not its first pigment.
            val second = if (recipe.size > 1) 1f - recipe.first().second else 0f
            for (lane in fill.laneFrom..fill.laneTo) {
                for (layer in 0 until depth) amounts[lane * depth + layer] = cell * fill.amount
                secondFraction[lane] = second
            }
        }
    }

    /** Column index serving a -1..1 coordinate across the brush width. */
    fun laneIndex(coordinate: Float): Int {
        val c = if (coordinate.isFinite()) coordinate.coerceIn(-1f, 1f) else 0f
        return ((c + 1f) * 0.5f * (lanes - 1)).roundToInt().coerceIn(0, lanes - 1)
    }

    /** 0..1: how full the column serving [coordinate] is, against its capacity. */
    fun amount(coordinate: Float): Float {
        val lane = laneIndex(coordinate)
        var sum = 0f
        for (layer in 0 until depth) sum += amounts[lane * depth + layer]
        return (sum / config.capacity).coerceIn(0f, 1f)
    }

    /** 0..1: the share of the column's paint that is its second pigment. */
    fun mix(coordinate: Float): Float = secondFraction[laneIndex(coordinate)]

    /** 0..1: the mean fill across the columns, against capacity; what is left of the load after a stroke. */
    fun remaining(): Float {
        var sum = 0f
        for (a in amounts) sum += a
        return (sum / (config.capacity * lanes)).coerceIn(0f, 1f)
    }

    /** Takes up to [draw] of capacity from the column's tip layer first; returns what it could give. */
    fun consume(coordinate: Float, draw: Float): Float {
        if (!(draw > 0f) || !draw.isFinite()) return 0f
        val lane = laneIndex(coordinate)
        var left = draw
        for (layer in 0 until depth) {
            val i = lane * depth + layer
            val take = minOf(left, amounts[i])
            amounts[i] -= take
            left -= take
            if (left <= 0f) break
        }
        return draw - left
    }
}
