package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.max
import kotlin.math.min

/**
 * Watercolour field passes (spec/painterly.md 3.8, phase P8): wicking and bleed into paper, pigment
 * transport, edge darkening and drying, as a fixed-timestep per-pixel update. Kotlin reference and test
 * oracle for `core/wgpu-engine/src/shaders/watercolour.wgsl`; every tick reads the previous tick's fields
 * (a gather, so the order of pixels never matters) and sums the four neighbours in a fixed order, which
 * is what makes the GPU port agree and a replay repeat.
 *
 * Fields per pixel: [Field.wet] 0..1, [Field.pigment] >= 0 (total pigment amount). Static per pixel:
 * [Field.absorbency] (paper sizing) and [Field.tooth] (paper height). One tick, for each pixel i and
 * 4-neighbour j (left, right, up, down; out-of-range neighbours are skipped):
 *
 * - wicking: `wet_i += wick * (a_i + a_j) / 2 * (wet_j - wet_i)` (symmetric, so water is conserved)
 * - bleed: `pig_i += bleed * min(wet_i, wet_j) * (pig_j - pig_i)` (pigment spreads only through wet paper)
 * - edge darkening: `pig_i += edge * (max(wet_j - wet_i, 0) * pig_j - max(wet_i - wet_j, 0) * pig_i)` (pigment
 *   flows from wetter to drier neighbours, so a blob drying from its rim collects pigment there; symmetric
 *   between a pair, so pigment is conserved)
 * - drying: `wet_i *= 1 - dry` (water leaves; pigment stays)
 */
object WatercolourField {
    class Field(val width: Int, val height: Int) {
        val wet = FloatArray(width * height)
        val pigment = FloatArray(width * height)
        val absorbency = FloatArray(width * height) { 0.5f }
        val tooth = FloatArray(width * height) { 0.5f }

        fun copy(): Field = Field(width, height).also {
            wet.copyInto(it.wet); pigment.copyInto(it.pigment)
            absorbency.copyInto(it.absorbency); tooth.copyInto(it.tooth)
        }
    }

    data class Params(
        val wick: Float = 0.15f,
        val bleed: Float = 0.12f,
        val edge: Float = 0.10f,
        val dry: Float = 0.01f,
        /** 0..1: how strongly pigment settles into tooth valleys at deposit. */
        val granulation: Float = 0f,
    ) {
        /** Clamped to the ranges the fixed-timestep scheme is stable in (the sum of four neighbour flows < 1). */
        fun sanitized(): Params = Params(
            wick = wick.coerceIn(0f, MAX_K),
            bleed = bleed.coerceIn(0f, MAX_K),
            edge = edge.coerceIn(0f, MAX_K),
            dry = dry.coerceIn(0f, 1f),
            granulation = granulation.coerceIn(0f, 1f),
        )

        companion object {
            const val MAX_K = 0.2f
        }
    }

    /** Pigment a deposit of [amount] leaves on paper of [tooth]: more in valleys (low tooth), less on crests. */
    fun granulate(amount: Float, tooth: Float, granulation: Float): Float =
        amount * max(0f, 1f + granulation * (0.5f - tooth) * 2f)

    /** Adds [amount] per pixel of pigment (granulated) and [water] per pixel of wetness, in place. */
    fun deposit(field: Field, amount: FloatArray, water: FloatArray, params: Params) {
        val p = params.sanitized()
        for (i in field.pigment.indices) {
            if (amount[i] > 0f) field.pigment[i] += granulate(amount[i], field.tooth[i], p.granulation)
            if (water[i] > 0f) field.wet[i] = min(1f, field.wet[i] + water[i])
        }
    }

    /** Advances [field] by [ticks] fixed timesteps and returns it. */
    fun run(field: Field, params: Params, ticks: Int): Field {
        val p = params.sanitized()
        val w = field.width
        val h = field.height
        var wet = field.wet.copyOf()
        var pig = field.pigment.copyOf()
        var wetNext = FloatArray(wet.size)
        var pigNext = FloatArray(pig.size)
        val dx = intArrayOf(-1, 1, 0, 0)
        val dy = intArrayOf(0, 0, -1, 1)
        repeat(ticks) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var wetSum = 0f
                var pigSum = 0f
                for (k in 0 until 4) {
                    val nx = x + dx[k]
                    val ny = y + dy[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    wetSum += p.wick * (field.absorbency[i] + field.absorbency[j]) / 2f * (wet[j] - wet[i])
                    pigSum += p.bleed * min(wet[i], wet[j]) * (pig[j] - pig[i])
                    pigSum += p.edge * (max(wet[j] - wet[i], 0f) * pig[j] - max(wet[i] - wet[j], 0f) * pig[i])
                }
                wetNext[i] = ((wet[i] + wetSum) * (1f - p.dry)).coerceIn(0f, 1f)
                pigNext[i] = max(pig[i] + pigSum, 0f)
            }
            val tw = wet; wet = wetNext; wetNext = tw
            val tp = pig; pig = pigNext; pigNext = tp
        }
        wet.copyInto(field.wet)
        pig.copyInto(field.pigment)
        return field
    }
}
