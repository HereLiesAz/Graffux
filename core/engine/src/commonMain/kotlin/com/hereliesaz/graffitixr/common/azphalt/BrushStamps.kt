package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val RAD_TO_DEG = 57.29578f
private const val DEG_TO_RAD = 0.017453292f
private const val MASK_SEED_SALT = 0x4D41534B5F544950L
private const val COLOR_SEED_SALT = 0x434F4C4F525F4D58L
private const val LONGITUDINAL_SEED_SALT = 0x4C4F4E475F534341L
private const val MASK_LONGITUDINAL_SEED_SALT = 0x4D41534B5F4C4F4EL
private const val COUNT_SEED_SALT = 0x434F554E545F4A54L

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
    /**
     * Resolved surface penetration in [0, 1]; renderers/material stages must not reinterpret raw
     * pressure. The CPU round compositors and ImpastoEngine scale deposit strength by it (clamped),
     * and the GPU stamp shaders use it as the substrate-penetration gate. Defaults to 1 (full
     * contact); only an active [BrushContactConfig] resolves it from pressure/contact, so ordinary
     * brushes are unaffected. Appended at the end of the public value contract so existing positional Dab callers retain
     * the exact meaning of every pre-Phase-3 argument.
     */
    val contactDepth: Float = 1f,
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
            // A non-finite point has no place on the stroke (an infinite segment would never end).
            if (segLen == 0f || !segLen.isFinite()) continue
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

    /**
     * A sampled stroke's movement dabs. This is [LiveStrokeDabs.movementDabs]: the same incremental
     * generators the live stroke runs, fed the samples in order, so a desktop stroke, a Brush Studio
     * preview and an Android commit of the same samples produce the same dabs. (A separate
     * whole-stroke resolver used to live here and drifted from the live path on speed shrink,
     * pressure fade, the first-dab blot and the first-dab heading.)
     */
    fun dynamicDabs(samples: List<BrushSample>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> =
        LiveStrokeDabs.movementDabs(samples, diameterPx, brush, seed)

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
        // The first dab has no previous centre. The live generator (IncrementalStaticDabGenerator)
        // has to stamp it before the next centre exists, so both use heading 0 for it.
        if (count < 2 || i == 0) return 0f
        val a = i - 1
        val b = i
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
