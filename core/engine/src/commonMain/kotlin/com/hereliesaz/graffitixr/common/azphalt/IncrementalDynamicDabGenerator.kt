package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stateful live resolver for dynamic/taper stamp brushes.
 *
 * The authoritative commit path still uses [BrushStamps.dynamicDabs]. Live painting deliberately
 * treats end-taper/lift-off as provisional until finger-up because the final stroke length does not
 * exist yet. Everything else is resolved once as the stroke advances, so input cost depends on the
 * new segment rather than the accumulated stroke history.
 */
class IncrementalDynamicDabGenerator(
    diameterPx: Float,
    private val brush: AzphaltBrush,
    private val seed: Long,
) {
    private val diameter = diameterPx.coerceAtLeast(0f)
    private val baseRadius = diameter / 2f
    private val rng = Random(seed)
    private val maskRng = Random(seed xor MASK_SEED_SALT)
    private val maskLongRng = Random(seed xor MASK_LONGITUDINAL_SEED_SALT)
    private val colorRng = Random(seed xor COLOR_SEED_SALT)
    private val longRng = Random(seed xor LONGITUDINAL_SEED_SALT)
    private val countRng = Random(seed xor COUNT_SEED_SALT)
    private val blotRng = Random(seed xor BLOT_SEED_SALT)

    private var previous: BrushSample? = null
    private var startTime = 0L
    private var travelled = 0f
    private var nextAt = 0f
    private var index = 0
    private var firstSample: BrushSample? = null
    private var secondSample: BrushSample? = null
    private var firstMovementSeen = false
    private var dwellMs = 0f

    fun append(sampleIn: BrushSample): List<Dab> {
        if (sampleIn.predicted || diameter <= 0f) return emptyList()
        val sample = sampleIn.copy(predicted = false)
        val prev = previous
        if (prev == null) {
            previous = sample
            firstSample = sample
            startTime = sample.uptimeMillis
            return emitAt(sample, 0f)
        }
        if (secondSample == null) secondSample = sample

        val segmentLength = hypot(sample.x - prev.x, sample.y - prev.y)
        if (!firstMovementSeen) {
            val anchor = firstSample!!
            if (hypot(sample.x - anchor.x, sample.y - anchor.y) <= brush.airbrushStillnessRadiusPx) {
                dwellMs = (sample.uptimeMillis - anchor.uptimeMillis).coerceAtLeast(0L).toFloat()
            } else {
                firstMovementSeen = true
            }
        }
        if (segmentLength <= 0f) {
            previous = sample
            return emptyList()
        }

        val segmentStart = travelled
        val segmentEnd = travelled + segmentLength
        val out = ArrayList<Dab>()
        // The first point was emitted at 0. nextAt was advanced by emitAt().
        while (nextAt <= segmentEnd + EPSILON) {
            if (nextAt > segmentStart + EPSILON) {
                val t = ((nextAt - segmentStart) / segmentLength).coerceIn(0f, 1f)
                val interpolated = interpolate(prev, sample, t)
                out.addAll(emitAt(interpolated, nextAt))
            }
            if (nextAt <= segmentStart + EPSILON) {
                // Defensive progress if an extremely small/invalid dynamic spacing kept us behind.
                nextAt = segmentStart + 0.01f
            }
        }
        travelled = segmentEnd
        previous = sample
        return out
    }

    private fun emitAt(sample: BrushSample, at: Float): List<Dab> {
        val dynamic = BrushSensorEngine.resolve(sample, brush.dynamics, startTime, seed, index)
        val taper = brush.taper
        val blot = brush.blot
        val startTaperT = if (taper.startLengthPx > 0f) (at / taper.startLengthPx).coerceIn(0f, 1f) else 1f
        // Live end taper is intentionally deferred: changing total length would otherwise invalidate
        // already-rendered dabs and recreate the exact growing-prefix work Engine 2 removes.
        val taperSize = lerp(taper.minSize, 1f, startTaperT)
        val taperOpacity = lerp(taper.minOpacity, 1f, startTaperT)

        val dwellGrowthFactor = if (blot.dwellRampMs > 0f) {
            val dwellT = (dwellMs / blot.dwellRampMs).coerceIn(0f, 1f)
            1f + (blot.dwellGrowthMultiplier - 1f) * sqrt(dwellT)
        } else 1f
        val sharpnessFactor = if (blot.sharpnessMultiplier != 1f) {
            val a = firstSample
            val b = secondSample
            if (a != null && b != null) {
                val dt = (b.uptimeMillis - a.uptimeMillis).coerceAtLeast(1L).toFloat()
                val rate = (b.pressure - a.pressure).coerceAtLeast(0f) / dt
                val sharpnessT = (rate * blot.sharpnessRampMsPerUnit).coerceIn(0f, 1f)
                1f + (blot.sharpnessMultiplier - 1f) * sharpnessT
            } else 1f
        } else 1f
        val blotPeakFactor = dwellGrowthFactor * sharpnessFactor
        val blotT = if (blot.lengthPx > 0f) (at / blot.lengthPx).coerceIn(0f, 1f) else 1f
        val blotSize = lerp(blot.sizeMultiplier * blotPeakFactor, 1f, blotT)
        val blotOpacity = lerp(blot.opacityMultiplier * blotPeakFactor, 1f, blotT)
        val resolvedDiameter = diameter * dynamic.sizeMultiplier * taperSize * blotSize
        val headingDeg = sample.drawingAngleDeg
        val out = ArrayList<Dab>()

        repeat(resolveDabCount()) {
            val sizeR = rng.nextFloat()
            val opacR = rng.nextFloat()
            val scatR = rng.nextFloat()
            val longitudinalR = longRng.nextFloat()
            val radius = baseRadius * dynamic.sizeMultiplier * taperSize * blotSize *
                (1f - brush.sizeJitter * sizeR)
            val alpha = (brush.opacity * dynamic.opacityMultiplier * taperOpacity * blotOpacity *
                (1f - brush.opacityJitter * opacR)).coerceIn(0f, 1f)
            var x = sample.x
            var y = sample.y
            val scatter = brush.scatter * dynamic.scatterMultiplier
            if (scatter > 0f) {
                val mag = scatter * resolvedDiameter * (scatR * 2f - 1f)
                val perp = (headingDeg + 90f) * DEG_TO_RAD
                x += mag * cos(perp); y += mag * sin(perp)
            }
            val longitudinal = brush.scatterLongitudinal * dynamic.scatterMultiplier
            if (longitudinal > 0f) {
                val mag = longitudinal * resolvedDiameter * (longitudinalR * 2f - 1f)
                val heading = headingDeg * DEG_TO_RAD
                x += mag * cos(heading); y += mag * sin(heading)
            }
            val angle = brush.angle + (if (brush.followStroke) headingDeg else 0f) +
                dynamic.rotationOffsetDeg + brush.rotationPerPx * at
            val mask = resolveMask(sample, x, y, resolvedDiameter, headingDeg, at)
            out += Dab(
                x = x, y = y, radius = radius.coerceAtLeast(0f), alpha = alpha,
                angleDeg = angle,
                tipRatio = (brush.tipRatio * dynamic.tipRatioMultiplier).coerceIn(0.05f, 1f),
                hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                flowMultiplier = dynamic.flowMultiplier,
                hueShiftDeg = dynamic.hueShiftDeg,
                saturationMultiplier = dynamic.saturationMultiplier,
                valueMultiplier = dynamic.valueMultiplier,
                colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                sourceRandom = colorRng.nextFloat(), mask = mask,
            )
            if (blot.extraStamps > 0 && blotT < 1f) {
                val fade = (1f - blotT).coerceIn(0f, 1f)
                repeat(blot.extraStamps) {
                    val jitterAngle = (blotRng.nextFloat() * 2f - 1f) * blot.angleJitterDeg
                    val jitterMag = blot.positionJitter * resolvedDiameter * blotRng.nextFloat()
                    val jitterDir = blotRng.nextFloat() * 360f * DEG_TO_RAD
                    out += Dab(
                        x = x + jitterMag * cos(jitterDir), y = y + jitterMag * sin(jitterDir),
                        radius = radius.coerceAtLeast(0f), alpha = (alpha * fade).coerceIn(0f, 1f),
                        angleDeg = angle + jitterAngle,
                        tipRatio = (brush.tipRatio * dynamic.tipRatioMultiplier).coerceIn(0.05f, 1f),
                        hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                        flowMultiplier = dynamic.flowMultiplier, hueShiftDeg = dynamic.hueShiftDeg,
                        saturationMultiplier = dynamic.saturationMultiplier,
                        valueMultiplier = dynamic.valueMultiplier,
                        colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                        sourceRandom = colorRng.nextFloat(), mask = null,
                    )
                }
            }
        }

        val spacingReference = if (brush.isotropicSpacing) resolvedDiameter
            else resolvedDiameter * brush.tipRatio.coerceIn(0.05f, 1f)
        val step = (brush.spacing * spacingReference * dynamic.spacingMultiplier).coerceAtLeast(0.01f)
        nextAt = at + step
        index++
        return out
    }

    private fun resolveDabCount(): Int {
        if (brush.count <= 1) return 1
        if (brush.countJitter <= 0f) return brush.count
        val factor = 1f - brush.countJitter * countRng.nextFloat()
        return (brush.count * factor).roundToInt().coerceIn(1, brush.count)
    }

    private fun resolveMask(sample: BrushSample, x: Float, y: Float, primaryDiameter: Float, headingDeg: Float, at: Float): MaskDab? {
        val cfg = brush.maskedBrush?.sanitized() ?: return null
        val dynamic = BrushSensorEngine.resolve(sample, cfg.dynamics, startTime, seed xor MASK_SEED_SALT, index)
        val maskDiameter = primaryDiameter * cfg.sizeRatio * dynamic.sizeMultiplier
        var mx = x; var my = y
        val scatter = cfg.scatter * dynamic.scatterMultiplier
        if (scatter > 0f) {
            val mag = scatter * maskDiameter * (maskRng.nextFloat() * 2f - 1f)
            val perp = (headingDeg + 90f) * DEG_TO_RAD
            mx += mag * cos(perp); my += mag * sin(perp)
        } else maskRng.nextFloat()
        val longitudinal = cfg.scatterLongitudinal * dynamic.scatterMultiplier
        if (longitudinal > 0f) {
            val mag = longitudinal * maskDiameter * (maskLongRng.nextFloat() * 2f - 1f)
            val heading = headingDeg * DEG_TO_RAD
            mx += mag * cos(heading); my += mag * sin(heading)
        } else maskLongRng.nextFloat()
        return MaskDab(
            x = mx, y = my, radius = maskDiameter / 2f, tipRatio = cfg.tipRatio,
            alpha = (cfg.opacity * dynamic.opacityMultiplier).coerceIn(0f, 1f),
            angleDeg = cfg.angle + (if (cfg.followStroke) headingDeg else 0f) +
                dynamic.rotationOffsetDeg + cfg.rotationPerPx * at,
            flowMultiplier = (cfg.flow * dynamic.flowMultiplier).coerceAtLeast(0f),
            invert = cfg.invert, blendMode = cfg.blendMode,
        )
    }

    private fun interpolate(a: BrushSample, b: BrushSample, t: Float): BrushSample {
        val dx = b.x - a.x; val dy = b.y - a.y
        val heading = if (dx == 0f && dy == 0f) a.drawingAngleDeg else atan2(dy, dx) * RAD_TO_DEG
        return BrushSample(
            x = lerp(a.x, b.x, t), y = lerp(a.y, b.y, t),
            uptimeMillis = a.uptimeMillis + ((b.uptimeMillis - a.uptimeMillis) * t).toLong(),
            pressure = lerp(a.pressure, b.pressure, t), tiltRadians = lerp(a.tiltRadians, b.tiltRadians, t),
            orientationRadians = lerp(a.orientationRadians, b.orientationRadians, t),
            distancePx = lerp(a.distancePx, b.distancePx, t), speedPxPerMs = lerp(a.speedPxPerMs, b.speedPxPerMs, t),
            drawingAngleDeg = heading, predicted = false,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val RAD_TO_DEG = 57.29578f
        const val DEG_TO_RAD = 0.017453292f
        const val EPSILON = 1e-4f
        const val MASK_SEED_SALT = 0x4D41534B5F544950L
        const val COLOR_SEED_SALT = 0x434F4C4F525F4D58L
        const val LONGITUDINAL_SEED_SALT = 0x4C4F4E475F534341L
        const val MASK_LONGITUDINAL_SEED_SALT = 0x4D41534B5F4C4F4EL
        const val COUNT_SEED_SALT = 0x434F554E545F4A54L
        const val BLOT_SEED_SALT = 0x424C4F545F534841L
    }
}
