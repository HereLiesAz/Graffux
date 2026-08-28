package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.hypot
import kotlin.random.Random

private const val AIRBRUSH_SEED_SALT = 0x4149524252534831L // "AIRBRSH1"

/**
 * Krita-style airbrush/build-up primitive: while the stylus or finger is held roughly still,
 * paint keeps depositing over time rather than only while the pointer moves. [BrushStamps]'
 * dab-placement loops (`dabs`/`dynamicDabs`) are arc-length driven and never revisit the same
 * position twice, so a held-still pointer alone produces at most one dab there. This primitive
 * produces the *additional* dabs a held pointer should generate; a caller concatenates them with
 * the normal movement-driven dabs from `dynamicDabs`.
 *
 * Deliberately out of scope here, and documented rather than silently skipped: perpendicular/
 * longitudinal scatter, distance-based rotation, and masked/dual-tip resolution are not applied
 * to held dabs — each would need either a synthetic "distance travelled" value or its own design
 * decision about what "distance" even means while the brush is stationary. Held dabs use plain
 * size/opacity jitter, sensor dynamics, and colour-source resolution only.
 */
object AirbrushEngine {

    /**
     * @param dabsPerSecond deposit cadence while held; a non-positive value disables airbrush
     *   entirely (returns an empty list, matching a brush with no airbrush behavior configured).
     * @param stillnessRadiusPx a sample counts as "held" if it stays within this radius of the
     *   current run's anchor position; movement past that radius starts a new anchor and resets
     *   the deposit cadence from there.
     */
    fun heldDabs(
        samples: List<BrushSample>,
        diameterPx: Float,
        brush: AzphaltBrush,
        dabsPerSecond: Float,
        stillnessRadiusPx: Float,
        seed: Long,
    ): List<Dab> {
        val real = samples.filterNot { it.predicted }
        val diameter = diameterPx.coerceAtLeast(0f)
        if (dabsPerSecond <= 0f || diameter <= 0f || real.size < 2) return emptyList()

        val intervalMs = (1000f / dabsPerSecond).coerceAtLeast(1f)
        val baseRadius = diameter / 2f
        val rng = Random(seed xor AIRBRUSH_SEED_SALT)
        val startTime = real.first().uptimeMillis
        val out = ArrayList<Dab>()

        var anchor = real.first()
        var nextEmitTime = anchor.uptimeMillis.toFloat() + intervalMs
        var index = 0

        for (i in 1 until real.size) {
            val sample = real[i]
            val dist = hypot(sample.x - anchor.x, sample.y - anchor.y)
            if (dist > stillnessRadiusPx) {
                // Movement broke the held run; the new sample becomes the next anchor and the
                // deposit cadence restarts from here.
                anchor = sample
                nextEmitTime = anchor.uptimeMillis.toFloat() + intervalMs
                continue
            }
            while (nextEmitTime < sample.uptimeMillis.toFloat()) {
                val heldSample = anchor.copy(uptimeMillis = nextEmitTime.toLong(), predicted = false)
                val dynamic = BrushSensorEngine.resolve(heldSample, brush.dynamics, startTime, seed, index)
                val sizeR = rng.nextFloat()
                val opacR = rng.nextFloat()
                val radius = baseRadius * dynamic.sizeMultiplier * (1f - brush.sizeJitter * sizeR)
                val alpha = (
                    brush.opacity * dynamic.opacityMultiplier * (1f - brush.opacityJitter * opacR)
                    ).coerceIn(0f, 1f)
                out.add(
                    Dab(
                        x = anchor.x,
                        y = anchor.y,
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
                )
                index++
                nextEmitTime += intervalMs
            }
        }
        return out
    }
}
