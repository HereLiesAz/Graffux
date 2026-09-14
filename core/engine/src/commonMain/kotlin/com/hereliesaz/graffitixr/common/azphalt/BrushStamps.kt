package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val RAD_TO_DEG = 57.29578f
private const val DEG_TO_RAD = 0.017453292f
private const val MAX_LIFT_TAPER_DIAMETERS = 8f
private const val FAST_LIFT_END_FLOOR = 0.6f
private const val SPEED_SIZE_SENSITIVITY = 0.2f
private const val MASK_SEED_SALT = 0x4D41534B5F544950L
private const val COLOR_SEED_SALT = 0x434F4C4F525F4D58L
private const val LONGITUDINAL_SEED_SALT = 0x4C4F4E475F534341L
private const val MASK_LONGITUDINAL_SEED_SALT = 0x4D41534B5F4C4F4EL
private const val COUNT_SEED_SALT = 0x434F554E545F4A54L
private const val BLOT_SEED_SALT = 0x424C4F545F534841L

private fun resolveDabCount(brush: AzphaltBrush, rng: Random): Int {
    if (brush.count <= 1) return 1
    if (brush.countJitter <= 0f) return brush.count
    val factor = 1f - brush.countJitter * rng.nextFloat()
    return (brush.count * factor).roundToInt().coerceIn(1, brush.count)
}

data class MaskDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val tipRatio: Float,
    val alpha: Float,
    val angleDeg: Float,
    val flowMultiplier: Float = 1f,
    val invert: Boolean = false,
    val blendMode: MaskedBrushBlendMode = MaskedBrushBlendMode.MULTIPLY,
) {
    val keepInside: Boolean
        get() = when (blendMode) {
            MaskedBrushBlendMode.MULTIPLY -> !invert
            MaskedBrushBlendMode.SUBTRACT -> invert
        }
}

data class Dab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float = 0f,
    val tipRatio: Float = 1f,
    val hardness: Float = 1f,
    val flowMultiplier: Float = 1f,
    val hueShiftDeg: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val valueMultiplier: Float = 1f,
    val colorMix: Float = 0f,
    val sourceRandom: Float = 0f,
    val mask: MaskDab? = null,
)

object BrushStamps {
    fun place(points: List<Float>, stepPx: Float): List<Float> {
        val n = points.size / 2
        if (n == 0) return emptyList()
        if (n == 1) return listOf(points[0], points[1])
        val step = if (stepPx > 0f) stepPx else 0.01f
        val out = ArrayList<Float>()
        out.add(points[0]); out.add(points[1])
        var nextAt = step
        var travelled = 0f
        for (i in 0 until n - 1) {
            val ax = points[2 * i]; val ay = points[2 * i + 1]
            val bx = points[2 * i + 2]; val by = points[2 * i + 3]
            val segLen = hypot(bx - ax, by - ay)
            if (segLen == 0f) continue
            while (nextAt <= travelled + segLen) {
                val t = (nextAt - travelled) / segLen
                out.add(ax + (bx - ax) * t)
                out.add(ay + (by - ay) * t)
                nextAt += step
            }
            travelled += segLen
        }
        return out
    }

    fun dabs(points: List<Float>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        val diameter = diameterPx.coerceAtLeast(0f)
        if (diameter <= 0f) return emptyList()
        val baseRadius = diameter / 2f
        val step = brush.spacing * brush.spacingReferencePx(diameter)
        val centres = place(points, step)
        val count = centres.size / 2
        if (count == 0) return emptyList()

        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val maskLongRng = Random(seed xor MASK_LONGITUDINAL_SEED_SALT)
        val colorRng = Random(seed xor COLOR_SEED_SALT)
        val longRng = Random(seed xor LONGITUDINAL_SEED_SALT)
        val countRng = Random(seed xor COUNT_SEED_SALT)
        val out = ArrayList<Dab>(count)
        for (i in 0 until count) {
            val cx = centres[2 * i]; val cy = centres[2 * i + 1]
            val headingDeg = headingAt(centres, i, count)
            val resolvedCount = resolveDabCount(brush, countRng)
            repeat(resolvedCount) {
                val sizeR = rng.nextFloat()
                val opacR = rng.nextFloat()
                val scatR = rng.nextFloat()
                val longR = longRng.nextFloat()

                val radius = baseRadius * (1f - brush.sizeJitter * sizeR)
                val alpha = (brush.opacity * (1f - brush.opacityJitter * opacR)).coerceIn(0f, 1f)
                var x = cx; var y = cy
                if (brush.scatter > 0f && diameter > 0f) {
                    val mag = brush.scatter * diameter * (scatR * 2f - 1f)
                    val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                    x += mag * cos(perpRad)
                    y += mag * sin(perpRad)
                }
                if (brush.scatterLongitudinal > 0f && diameter > 0f) {
                    val mag = brush.scatterLongitudinal * diameter * (longR * 2f - 1f)
                    val headingRad = headingDeg * DEG_TO_RAD
                    x += mag * cos(headingRad)
                    y += mag * sin(headingRad)
                }
                val angle = brush.angle +
                    (if (brush.followStroke) headingDeg else 0f) +
                    brush.rotationPerPx * (i * step)
                val mask = resolveStaticMask(
                    brush.maskedBrush, x, y, diameter, headingDeg, i * step, maskRng, maskLongRng,
                )
                out.add(
                    Dab(
                        x = x,
                        y = y,
                        radius = radius,
                        alpha = alpha,
                        angleDeg = angle,
                        tipRatio = brush.tipRatio,
                        hardness = brush.hardness.coerceIn(0f, 1f),
                        colorMix = brush.colorMix.coerceIn(0f, 1f),
                        sourceRandom = colorRng.nextFloat(),
                        mask = mask,
                    )
                )
            }
        }
        return out
    }

    fun dynamicDabs(samples: List<BrushSample>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        val real = samples.filterNot { it.predicted }
        val diameter = diameterPx.coerceAtLeast(0f)
        if (real.isEmpty() || diameter <= 0f) return emptyList()
        val hasMaskDynamics = brush.maskedBrush?.dynamics?.isNotEmpty() == true
        val taper = brush.taper
        val blot = brush.blot
        // Freeze physical hair/bundle population from the selected brush size. Dynamic diameter
        // changes later in this function deform the fixed identities rather than spawning hairs.
        val contactConfig = brush.contact.resolvedForBrushDiameter(diameter, brush.tipRatio)
        if (
            brush.dynamics.isEmpty() && !hasMaskDynamics && !taper.isActive() && !blot.isActive() &&
            !contactConfig.isActive()
        ) {
            val points = ArrayList<Float>(real.size * 2)
            real.forEach { points.add(it.x); points.add(it.y) }
            return dabs(points, diameter, brush, seed)
        }

        val arc = cumulativeArc(real)
        val total = arc.last()
        val baseRadius = diameter / 2f
        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val maskLongRng = Random(seed xor MASK_LONGITUDINAL_SEED_SALT)
        val colorRng = Random(seed xor COLOR_SEED_SALT)
        val longRng = Random(seed xor LONGITUDINAL_SEED_SALT)
        val countRng = Random(seed xor COUNT_SEED_SALT)
        val blotRng = Random(seed xor BLOT_SEED_SALT)
        val out = ArrayList<Dab>()
        val startTime = real.first().uptimeMillis
        val peakSpeed = real.maxOf { it.speedPxPerMs }.coerceAtLeast(1e-4f)
        val endSpeed = real.last().speedPxPerMs
        val liftDecelT = (1f - (endSpeed / peakSpeed)).coerceIn(0f, 1f)
        val startSpeedT = (real.first().speedPxPerMs / peakSpeed).coerceIn(0f, 1f)
        val dwellMs = if (blot.dwellRampMs > 0f) {
            val anchor = real.first()
            val stillRadius = brush.airbrushStillnessRadiusPx
            var lastStillIndex = 0
            for (i in 1 until real.size) {
                if (hypot(real[i].x - anchor.x, real[i].y - anchor.y) > stillRadius) break
                lastStillIndex = i
            }
            (real[lastStillIndex].uptimeMillis - anchor.uptimeMillis).toFloat()
        } else 0f
        val dwellGrowthFactor = if (blot.dwellRampMs > 0f) {
            val dwellT = (dwellMs / blot.dwellRampMs).coerceIn(0f, 1f)
            1f + (blot.dwellGrowthMultiplier - 1f) * sqrt(dwellT)
        } else 1f
        val sharpnessFactor = if (blot.sharpnessMultiplier != 1f && real.size >= 2) {
            val dt = (real[1].uptimeMillis - real[0].uptimeMillis).coerceAtLeast(1L).toFloat()
            val pressureRatePerMs = (real[1].pressure - real[0].pressure).coerceAtLeast(0f) / dt
            val sharpnessT = (pressureRatePerMs * blot.sharpnessRampMsPerUnit).coerceIn(0f, 1f)
            1f + (blot.sharpnessMultiplier - 1f) * sharpnessT
        } else 1f
        val blotPeakFactor = dwellGrowthFactor * sharpnessFactor
        var at = 0f
        var index = 0
        var mechanicalState = BrushMechanicalState()

        do {
            val sample = interpolateSample(real, arc, at)
            val strokePositionT = if (total > 0f) (at / total).coerceIn(0f, 1f) else 0f
            val pressureFadeFactor = 1f - strokePositionT
            val dynamic = BrushSensorEngine.resolve(sample, brush.dynamics, startTime, seed, index)
            val mechanics = BrushContactModel.step(sample, mechanicalState, contactConfig)
            mechanicalState = mechanics.state
            val contact = mechanics.contact

            val naturalStartZone = diameter * MAX_LIFT_TAPER_DIAMETERS * startSpeedT
            val effectiveStartZone = if (taper.startLengthPx > 0f) taper.startLengthPx else naturalStartZone
            val startTaperT = if (effectiveStartZone > 0f) (at / effectiveStartZone).coerceIn(0f, 1f) else 1f
            val naturalStartMinSz = 1f - startSpeedT
            val naturalStartMinOp = 1f - startSpeedT
            val startMinSz = if (taper.startLengthPx > 0f) taper.minSize else naturalStartMinSz
            val startMinOp = if (taper.startLengthPx > 0f) taper.minOpacity else naturalStartMinOp
            val startSizeFactor = lerp(startMinSz, 1f, startTaperT)
            val startOpacityFactor = lerp(startMinOp, 1f, startTaperT)

            val naturalEndZone = diameter * MAX_LIFT_TAPER_DIAMETERS * liftDecelT
            val guaranteedEndZone = minOf(maxOf(naturalEndZone, taper.endLengthPx), total * 0.5f)
            var endTaperT = if (guaranteedEndZone > 0f) {
                ((total - at) / guaranteedEndZone).coerceIn(0f, 1f)
            } else 1f
            if (taper.liftOffSynthesizesPressure && taper.endLengthPx > 0f && endTaperT < 1f) {
                val liftFactor = (sample.speedPxPerMs / peakSpeed).coerceIn(0f, 1f)
                endTaperT = (endTaperT * liftFactor).coerceIn(0f, 1f)
            }
            val naturalEndMinSz = lerp(FAST_LIFT_END_FLOOR, 0f, liftDecelT)
            val naturalEndMinOp = lerp(FAST_LIFT_END_FLOOR, 0f, liftDecelT)
            val endMinSz = if (taper.endLengthPx > 0f && (total - at) <= taper.endLengthPx) taper.minSize else naturalEndMinSz
            val endMinOp = if (taper.endLengthPx > 0f && (total - at) <= taper.endLengthPx) taper.minOpacity else naturalEndMinOp
            val endCurvedT = sqrt(endTaperT)
            val endSizeFactor = lerp(endMinSz, 1f, endCurvedT)
            val endOpacityFactor = lerp(endMinOp, 1f, endCurvedT)

            val taperSize = minOf(startSizeFactor, endSizeFactor)
            val taperOpacity = minOf(startOpacityFactor, endOpacityFactor)
            val blotT = if (blot.lengthPx > 0f) (at / blot.lengthPx).coerceIn(0f, 1f) else 1f
            val blotSize = lerp(blot.sizeMultiplier * blotPeakFactor, 1f, blotT)
            val blotOpacity = lerp(blot.opacityMultiplier * blotPeakFactor, 1f, blotT)
            val fadedSizeMultiplier = 1f + (dynamic.sizeMultiplier - 1f) * pressureFadeFactor
            val fadedOpacityMultiplier = 1f + (dynamic.opacityMultiplier - 1f) * pressureFadeFactor
            val resolvedDiameter = diameter * fadedSizeMultiplier * taperSize * blotSize
            val contactDiameter = resolvedDiameter * contact.widthMultiplier
            val headingDeg = sample.drawingAngleDeg
            val mechanicalHeadingDeg = headingDeg + contact.angleOffsetDeg
            val speedT = (sample.speedPxPerMs / peakSpeed).coerceIn(0f, 1f)
            val speedSizeFactor = 1f - speedT * SPEED_SIZE_SENSITIVITY

            repeat(resolveDabCount(brush, countRng)) {
                val sizeR = rng.nextFloat()
                val opacR = rng.nextFloat()
                val scatR = rng.nextFloat()
                val longR = longRng.nextFloat()
                val radius = baseRadius * fadedSizeMultiplier * taperSize * blotSize * speedSizeFactor *
                    contact.widthMultiplier * (1f - brush.sizeJitter * sizeR)
                val alpha = (brush.opacity * fadedOpacityMultiplier * taperOpacity * blotOpacity *
                    (1f - brush.opacityJitter * opacR)).coerceIn(0f, 1f)

                var x = sample.x + contact.offsetXFraction * contactDiameter
                var y = sample.y + contact.offsetYFraction * contactDiameter
                val scatter = brush.scatter * dynamic.scatterMultiplier
                if (scatter > 0f) {
                    val mag = scatter * contactDiameter * (scatR * 2f - 1f)
                    val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                    x += mag * cos(perpRad)
                    y += mag * sin(perpRad)
                }
                val longitudinalScatter = brush.scatterLongitudinal * dynamic.scatterMultiplier
                if (longitudinalScatter > 0f) {
                    val mag = longitudinalScatter * contactDiameter * (longR * 2f - 1f)
                    val headingRad = headingDeg * DEG_TO_RAD
                    x += mag * cos(headingRad)
                    y += mag * sin(headingRad)
                }

                val mechanicalAngle = if (contactConfig.isActive()) mechanicalHeadingDeg else if (brush.followStroke) headingDeg else 0f
                val angle = brush.angle + mechanicalAngle + dynamic.rotationOffsetDeg + brush.rotationPerPx * at
                val maskHeading = if (contactConfig.isActive()) mechanicalHeadingDeg else headingDeg
                val mask = resolveDynamicMask(
                    brush.maskedBrush, sample, x, y, contactDiameter, maskHeading, at,
                    startTime, seed, index, maskRng, maskLongRng,
                )
                val contactTipRatio = (brush.tipRatio * dynamic.tipRatioMultiplier * contact.tipRatioMultiplier)
                    .coerceIn(0.05f, 1f)
                val parent = Dab(
                    x = x,
                    y = y,
                    radius = radius.coerceAtLeast(0f),
                    alpha = alpha,
                    angleDeg = angle,
                    tipRatio = contactTipRatio,
                    hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                    flowMultiplier = dynamic.flowMultiplier,
                    hueShiftDeg = dynamic.hueShiftDeg,
                    saturationMultiplier = dynamic.saturationMultiplier,
                    valueMultiplier = dynamic.valueMultiplier,
                    colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                    sourceRandom = colorRng.nextFloat(),
                    mask = mask,
                )
                out.addAll(
                    BrushTuftDabExpander.expandIfEnabled(
                        parent = parent,
                        contactDiameterPx = contactDiameter,
                        contact = contact,
                        config = contactConfig.tufts,
                    )
                )

                if (blot.extraStamps > 0 && blotT < 1f) {
                    val fade = (1f - blotT).coerceIn(0f, 1f)
                    repeat(blot.extraStamps) {
                        val jitterAngle = (blotRng.nextFloat() * 2f - 1f) * blot.angleJitterDeg
                        val jitterMag = blot.positionJitter * contactDiameter * blotRng.nextFloat()
                        val jitterDir = blotRng.nextFloat() * 360f * DEG_TO_RAD
                        val blotParent = Dab(
                            x = x + jitterMag * cos(jitterDir),
                            y = y + jitterMag * sin(jitterDir),
                            radius = radius.coerceAtLeast(0f),
                            alpha = (alpha * fade).coerceIn(0f, 1f),
                            angleDeg = angle + jitterAngle,
                            tipRatio = contactTipRatio,
                            hardness = (brush.hardness * dynamic.hardnessMultiplier).coerceIn(0f, 1f),
                            flowMultiplier = dynamic.flowMultiplier,
                            hueShiftDeg = dynamic.hueShiftDeg,
                            saturationMultiplier = dynamic.saturationMultiplier,
                            valueMultiplier = dynamic.valueMultiplier,
                            colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                            sourceRandom = colorRng.nextFloat(),
                            mask = null,
                        )
                        out.addAll(
                            BrushTuftDabExpander.expandIfEnabled(
                                parent = blotParent,
                                contactDiameterPx = contactDiameter,
                                contact = contact,
                                config = contactConfig.tufts,
                            )
                        )
                    }
                }
            }

            if (total <= 0f) break
            val spacingReference = if (brush.isotropicSpacing) contactDiameter else
                contactDiameter * (brush.tipRatio * contact.tipRatioMultiplier).coerceIn(0.05f, 1f)
            val step = (brush.spacing * spacingReference * dynamic.spacingMultiplier).coerceAtLeast(0.01f)
            at += step
            index++
        } while (at <= total)

        return out
    }

    private fun resolveStaticMask(
        config: MaskedBrushConfig?,
        x: Float,
        y: Float,
        primaryDiameter: Float,
        headingDeg: Float,
        at: Float,
        rng: Random,
        longRng: Random,
    ): MaskDab? {
        if (config == null) return null
        val cfg = config.sanitized()
        var mx = x
        var my = y
        if (cfg.scatter > 0f) {
            val mag = cfg.scatter * primaryDiameter * cfg.sizeRatio * (rng.nextFloat() * 2f - 1f)
            val perp = (headingDeg + 90f) * DEG_TO_RAD
            mx += mag * cos(perp)
            my += mag * sin(perp)
        } else rng.nextFloat()
        if (cfg.scatterLongitudinal > 0f) {
            val mag = cfg.scatterLongitudinal * primaryDiameter * cfg.sizeRatio * (longRng.nextFloat() * 2f - 1f)
            val headingRad = headingDeg * DEG_TO_RAD
            mx += mag * cos(headingRad)
            my += mag * sin(headingRad)
        } else longRng.nextFloat()
        return MaskDab(
            x = mx,
            y = my,
            radius = primaryDiameter * cfg.sizeRatio / 2f,
            tipRatio = cfg.tipRatio,
            alpha = cfg.opacity,
            angleDeg = cfg.angle + (if (cfg.followStroke) headingDeg else 0f) + cfg.rotationPerPx * at,
            flowMultiplier = cfg.flow,
            invert = cfg.invert,
            blendMode = cfg.blendMode,
        )
    }

    private fun resolveDynamicMask(
        config: MaskedBrushConfig?,
        sample: BrushSample,
        x: Float,
        y: Float,
        primaryDiameter: Float,
        headingDeg: Float,
        at: Float,
        startTime: Long,
        seed: Long,
        index: Int,
        rng: Random,
        longRng: Random,
    ): MaskDab? {
        if (config == null) return null
        val cfg = config.sanitized()
        val dynamic = BrushSensorEngine.resolve(sample, cfg.dynamics, startTime, seed xor MASK_SEED_SALT, index)
        val maskDiameter = primaryDiameter * cfg.sizeRatio * dynamic.sizeMultiplier
        var mx = x
        var my = y
        val scatter = cfg.scatter * dynamic.scatterMultiplier
        if (scatter > 0f) {
            val mag = scatter * maskDiameter * (rng.nextFloat() * 2f - 1f)
            val perp = (headingDeg + 90f) * DEG_TO_RAD
            mx += mag * cos(perp)
            my += mag * sin(perp)
        } else rng.nextFloat()
        val longitudinalScatter = cfg.scatterLongitudinal * dynamic.scatterMultiplier
        if (longitudinalScatter > 0f) {
            val mag = longitudinalScatter * maskDiameter * (longRng.nextFloat() * 2f - 1f)
            val headingRad = headingDeg * DEG_TO_RAD
            mx += mag * cos(headingRad)
            my += mag * sin(headingRad)
        } else longRng.nextFloat()
        return MaskDab(
            x = mx,
            y = my,
            radius = maskDiameter / 2f,
            tipRatio = cfg.tipRatio,
            alpha = (cfg.opacity * dynamic.opacityMultiplier).coerceIn(0f, 1f),
            angleDeg = cfg.angle + (if (cfg.followStroke) headingDeg else 0f) +
                dynamic.rotationOffsetDeg + cfg.rotationPerPx * at,
            flowMultiplier = (cfg.flow * dynamic.flowMultiplier).coerceAtLeast(0f),
            invert = cfg.invert,
            blendMode = cfg.blendMode,
        )
    }

    private fun cumulativeArc(samples: List<BrushSample>): FloatArray {
        val out = FloatArray(samples.size)
        for (i in 1 until samples.size) {
            out[i] = out[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        }
        return out
    }

    private fun interpolateSample(samples: List<BrushSample>, arc: FloatArray, target: Float): BrushSample {
        if (samples.size == 1 || target <= 0f) return samples.first().copy(predicted = false)
        if (target >= arc.last()) {
            val last = samples.last()
            val prev = samples[samples.lastIndex - 1]
            return last.copy(
                drawingAngleDeg = headingDeg(prev.x, prev.y, last.x, last.y, last.drawingAngleDeg),
                predicted = false,
            )
        }
        var hi = 1
        while (hi < arc.size && arc[hi] < target) hi++
        val lo = hi - 1
        val span = (arc[hi] - arc[lo]).coerceAtLeast(1e-6f)
        val t = ((target - arc[lo]) / span).coerceIn(0f, 1f)
        val a = samples[lo]
        val b = samples[hi]
        val heading = headingDeg(a.x, a.y, b.x, b.y, a.drawingAngleDeg)
        val reportedPressure = when {
            a.reportedPressure != null && b.reportedPressure != null -> lerp(a.reportedPressure, b.reportedPressure, t)
            t < 0.5f -> a.reportedPressure ?: b.reportedPressure
            else -> b.reportedPressure ?: a.reportedPressure
        }
        return BrushSample(
            x = lerp(a.x, b.x, t),
            y = lerp(a.y, b.y, t),
            uptimeMillis = lerpLong(a.uptimeMillis, b.uptimeMillis, t),
            pressure = lerp(a.pressure, b.pressure, t),
            tiltRadians = lerp(a.tiltRadians, b.tiltRadians, t),
            orientationRadians = lerp(a.orientationRadians, b.orientationRadians, t),
            distancePx = lerp(a.distancePx, b.distancePx, t),
            speedPxPerMs = lerp(a.speedPxPerMs, b.speedPxPerMs, t),
            drawingAngleDeg = heading,
            predicted = false,
            touchMajorPx = lerp(a.touchMajorPx, b.touchMajorPx, t),
            touchMinorPx = lerp(a.touchMinorPx, b.touchMinorPx, t),
            reportedPressure = reportedPressure,
            telemetry = a.telemetry.blendTo(b.telemetry, t),
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun lerpLong(a: Long, b: Long, t: Float): Long = a + ((b - a) * t).toLong()

    private fun headingDeg(ax: Float, ay: Float, bx: Float, by: Float, fallback: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0f && dy == 0f) return fallback
        return atan2(dy, dx) * RAD_TO_DEG
    }

    fun stampCoverage(rNorm: Float, hardness: Float): Float {
        if (rNorm <= 0f) return 1f
        if (rNorm >= 1f) return 0f
        val h = hardness.coerceIn(0f, 1f)
        if (rNorm <= h) return 1f
        val denom = 1f - h
        if (denom <= 1e-4f) return 1f
        return ((1f - rNorm) / denom).coerceIn(0f, 1f)
    }

    fun buildUp(current: Float, flow: Float): Float {
        val c = current.coerceIn(0f, 1f)
        return (c + flow.coerceIn(0f, 1f) * (1f - c)).coerceIn(0f, 1f)
    }

    private fun headingAt(centres: List<Float>, i: Int, count: Int): Float {
        if (count < 2) return 0f
        val (a, b) = if (i == 0) 0 to 1 else (i - 1) to i
        val dx = centres[2 * b] - centres[2 * a]
        val dy = centres[2 * b + 1] - centres[2 * a + 1]
        if (dx == 0f && dy == 0f) return 0f
        return atan2(dy, dx) * RAD_TO_DEG
    }

    fun length(points: List<Float>): Float {
        val n = points.size / 2
        var total = 0f
        for (i in 0 until n - 1) {
            total += hypot(points[2 * i + 2] - points[2 * i], points[2 * i + 3] - points[2 * i + 1])
        }
        return total
    }
}
