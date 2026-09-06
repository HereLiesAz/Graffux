package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.hypot
import kotlin.random.Random

/** Stateful live equivalent of [AirbrushEngine.heldDabs]. */
class IncrementalAirbrushGenerator(
    diameterPx: Float,
    private val brush: AzphaltBrush,
    dabsPerSecond: Float,
    private val stillnessRadiusPx: Float,
    seed: Long,
) {
    private val diameter = diameterPx.coerceAtLeast(0f)
    private val intervalMs = if (dabsPerSecond > 0f) (1000f / dabsPerSecond).coerceAtLeast(1f) else Float.POSITIVE_INFINITY
    private val baseRadius = diameter / 2f
    private val rng = Random(seed xor AIRBRUSH_SEED_SALT)
    private val seed = seed

    private var anchor: BrushSample? = null
    private var startTime = 0L
    private var nextEmitTime = 0f
    private var index = 0

    fun append(sample: BrushSample): List<Dab> {
        if (sample.predicted || diameter <= 0f || !intervalMs.isFinite()) return emptyList()
        val currentAnchor = anchor
        if (currentAnchor == null) {
            anchor = sample.copy(predicted = false)
            startTime = sample.uptimeMillis
            nextEmitTime = sample.uptimeMillis.toFloat() + intervalMs
            return emptyList()
        }

        if (hypot(sample.x - currentAnchor.x, sample.y - currentAnchor.y) > stillnessRadiusPx) {
            anchor = sample.copy(predicted = false)
            nextEmitTime = sample.uptimeMillis.toFloat() + intervalMs
            return emptyList()
        }

        val out = ArrayList<Dab>()
        while (nextEmitTime < sample.uptimeMillis.toFloat()) {
            val heldSample = currentAnchor.copy(uptimeMillis = nextEmitTime.toLong(), predicted = false)
            val dynamic = BrushSensorEngine.resolve(heldSample, brush.dynamics, startTime, seed, index)
            val sizeR = rng.nextFloat()
            val opacR = rng.nextFloat()
            val radius = baseRadius * dynamic.sizeMultiplier * (1f - brush.sizeJitter * sizeR)
            val alpha = (
                brush.opacity * dynamic.opacityMultiplier * (1f - brush.opacityJitter * opacR)
                ).coerceIn(0f, 1f)
            out += Dab(
                x = currentAnchor.x,
                y = currentAnchor.y,
                radius = radius.coerceAtLeast(0f),
                alpha = alpha,
                angleDeg = brush.angle + dynamic.rotationOffsetDeg,
                tipRatio = brush.tipRatio,
                flowMultiplier = dynamic.flowMultiplier,
                hueShiftDeg = dynamic.hueShiftDeg,
                saturationMultiplier = dynamic.saturationMultiplier,
                valueMultiplier = dynamic.valueMultiplier,
                colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                sourceRandom = rng.nextFloat(),
            )
            index++
            nextEmitTime += intervalMs
        }
        return out
    }

    private companion object {
        const val AIRBRUSH_SEED_SALT = 0x4149524252534831L
    }
}
