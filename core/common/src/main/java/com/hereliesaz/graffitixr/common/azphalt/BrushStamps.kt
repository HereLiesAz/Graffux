package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private const val RAD_TO_DEG = 57.29578f
private const val DEG_TO_RAD = 0.017453292f
private const val MASK_SEED_SALT = 0x4D41534B5F544950L
private const val COLOR_SEED_SALT = 0x434F4C4F525F4D58L

/** Resolved secondary-tip instruction attached to a primary dab. */
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
)

/**
 * A concrete render instruction. [radius] is half the primary tip width; [tipRatio] is height/width.
 * Geometry and sensor options are already resolved here so every renderer consumes the same dabs.
 */
data class Dab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
    val tipRatio: Float = 1f,
    val flowMultiplier: Float = 1f,
    val hueShiftDeg: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val valueMultiplier: Float = 1f,
    /** Foreground→background selector used by GRADIENT source. */
    val colorMix: Float = 0f,
    /** Independent deterministic per-dab sample used by UNIFORM_RANDOM source. */
    val sourceRandom: Float = 0f,
    val mask: MaskDab? = null,
)

/** Arc-length dab placement and deterministic sensor resolution for stamp brushes. */
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

    /** Static/legacy path. Defaults remain pixel-compatible because ratio=1 and isotropic=true. */
    fun dabs(points: List<Float>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        val diameter = diameterPx.coerceAtLeast(0f)
        if (diameter <= 0f) return emptyList()
        val baseRadius = diameter / 2f
        val centres = place(points, brush.spacing * brush.spacingReferencePx(diameter))
        val count = centres.size / 2
        if (count == 0) return emptyList()

        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val colorRng = Random(seed xor COLOR_SEED_SALT)
        val out = ArrayList<Dab>(count)
        for (i in 0 until count) {
            val cx = centres[2 * i]; val cy = centres[2 * i + 1]
            val headingDeg = headingAt(centres, i, count)
            val sizeR = rng.nextFloat()
            val opacR = rng.nextFloat()
            val scatR = rng.nextFloat()

            val radius = baseRadius * (1f - brush.sizeJitter * sizeR)
            val alpha = (brush.opacity * (1f - brush.opacityJitter * opacR)).coerceIn(0f, 1f)
            var x = cx; var y = cy
            if (brush.scatter > 0f && diameter > 0f) {
                val mag = brush.scatter * diameter * (scatR * 2f - 1f)
                val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                x += mag * cos(perpRad)
                y += mag * sin(perpRad)
            }
            val angle = brush.angle + if (brush.followStroke) headingDeg else 0f
            val mask = resolveStaticMask(
                brush.maskedBrush, x, y, diameter, headingDeg, maskRng,
            )
            out.add(
                Dab(
                    x = x,
                    y = y,
                    radius = radius,
                    alpha = alpha,
                    angleDeg = angle,
                    tipRatio = brush.tipRatio,
                    colorMix = brush.colorMix.coerceIn(0f, 1f),
                    sourceRandom = colorRng.nextFloat(),
                    mask = mask,
                )
            )
        }
        return out
    }

    /**
     * Sensor-aware placement. Spacing is evaluated at each emitted dab and advances by the current
     * resolved brush size. This is the important Krita behavior missing from a fixed-diameter spacing
     * loop: pressure/speed driven size and spacing change the next impression distance together.
     */
    fun dynamicDabs(samples: List<BrushSample>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        val real = samples.filterNot { it.predicted }
        val diameter = diameterPx.coerceAtLeast(0f)
        if (real.isEmpty() || diameter <= 0f) return emptyList()
        val hasMaskDynamics = brush.maskedBrush?.dynamics?.isNotEmpty() == true
        if (brush.dynamics.isEmpty() && !hasMaskDynamics) {
            val points = ArrayList<Float>(real.size * 2)
            real.forEach { points.add(it.x); points.add(it.y) }
            return dabs(points, diameter, brush, seed)
        }

        val arc = cumulativeArc(real)
        val total = arc.last()
        val baseRadius = diameter / 2f
        val rng = Random(seed)
        val maskRng = Random(seed xor MASK_SEED_SALT)
        val colorRng = Random(seed xor COLOR_SEED_SALT)
        val out = ArrayList<Dab>()
        val startTime = real.first().uptimeMillis
        var at = 0f
        var index = 0

        do {
            val sample = interpolateSample(real, arc, at)
            val dynamic = BrushSensorEngine.resolve(sample, brush.dynamics, startTime, seed, index)
            val sizeR = rng.nextFloat()
            val opacR = rng.nextFloat()
            val scatR = rng.nextFloat()

            val resolvedDiameter = diameter * dynamic.sizeMultiplier
            val radius = baseRadius * dynamic.sizeMultiplier * (1f - brush.sizeJitter * sizeR)
            val alpha = (
                brush.opacity * dynamic.opacityMultiplier * (1f - brush.opacityJitter * opacR)
                ).coerceIn(0f, 1f)
            val headingDeg = sample.drawingAngleDeg

            var x = sample.x
            var y = sample.y
            val scatter = brush.scatter * dynamic.scatterMultiplier
            if (scatter > 0f) {
                val mag = scatter * resolvedDiameter * (scatR * 2f - 1f)
                val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                x += mag * cos(perpRad)
                y += mag * sin(perpRad)
            }

            val angle = brush.angle +
                (if (brush.followStroke) headingDeg else 0f) +
                dynamic.rotationOffsetDeg
            val mask = resolveDynamicMask(
                brush.maskedBrush,
                sample,
                x,
                y,
                resolvedDiameter,
                headingDeg,
                startTime,
                seed,
                index,
                maskRng,
            )
            out.add(
                Dab(
                    x = x,
                    y = y,
                    radius = radius.coerceAtLeast(0f),
                    alpha = alpha,
                    angleDeg = angle,
                    tipRatio = brush.tipRatio,
                    flowMultiplier = dynamic.flowMultiplier,
                    hueShiftDeg = dynamic.hueShiftDeg,
                    saturationMultiplier = dynamic.saturationMultiplier,
                    valueMultiplier = dynamic.valueMultiplier,
                    colorMix = (dynamic.mixValue ?: brush.colorMix).coerceIn(0f, 1f),
                    sourceRandom = colorRng.nextFloat(),
                    mask = mask,
                )
            )

            if (total <= 0f) break
            val spacingReference = if (brush.isotropicSpacing) {
                resolvedDiameter
            } else {
                resolvedDiameter * brush.tipRatio.coerceIn(0.05f, 1f)
            }
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
        rng: Random,
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
        } else {
            // Keep the independent RNG cadence stable whether scatter is enabled or not.
            rng.nextFloat()
        }
        return MaskDab(
            x = mx,
            y = my,
            radius = primaryDiameter * cfg.sizeRatio / 2f,
            tipRatio = cfg.tipRatio,
            alpha = cfg.opacity,
            angleDeg = cfg.angle + if (cfg.followStroke) headingDeg else 0f,
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
        startTime: Long,
        seed: Long,
        index: Int,
        rng: Random,
    ): MaskDab? {
        if (config == null) return null
        val cfg = config.sanitized()
        val dynamic = BrushSensorEngine.resolve(
            sample, cfg.dynamics, startTime, seed xor MASK_SEED_SALT, index,
        )
        val maskDiameter = primaryDiameter * cfg.sizeRatio * dynamic.sizeMultiplier
        var mx = x
        var my = y
        val scatter = cfg.scatter * dynamic.scatterMultiplier
        if (scatter > 0f) {
            val mag = scatter * maskDiameter * (rng.nextFloat() * 2f - 1f)
            val perp = (headingDeg + 90f) * DEG_TO_RAD
            mx += mag * cos(perp)
            my += mag * sin(perp)
        } else {
            rng.nextFloat()
        }
        return MaskDab(
            x = mx,
            y = my,
            radius = maskDiameter / 2f,
            tipRatio = cfg.tipRatio,
            alpha = (cfg.opacity * dynamic.opacityMultiplier).coerceIn(0f, 1f),
            angleDeg = cfg.angle +
                (if (cfg.followStroke) headingDeg else 0f) + dynamic.rotationOffsetDeg,
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
