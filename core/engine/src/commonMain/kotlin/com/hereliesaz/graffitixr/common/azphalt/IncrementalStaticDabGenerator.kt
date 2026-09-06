package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live-stroke counterpart of [BrushStamps.dabs].
 *
 * It owns placement and RNG state for one stroke, so appending a point produces only the dabs that
 * become newly placeable on that segment. Old stroke geometry is never walked again. The RNG draw
 * order intentionally mirrors BrushStamps.dabs so static jitter/scatter/count/color stay stable.
 */
class IncrementalStaticDabGenerator(
    diameterPx: Float,
    private val brush: AzphaltBrush,
    seed: Long,
) {
    private val diameter = diameterPx.coerceAtLeast(0f)
    private val baseRadius = diameter / 2f
    private val step = (brush.spacing * brush.spacingReferencePx(diameter)).coerceAtLeast(0.01f)
    private val placer = IncrementalDabPlacer(step)

    private val rng = Random(seed)
    private val maskRng = Random(seed xor MASK_SEED_SALT)
    private val maskLongRng = Random(seed xor MASK_LONGITUDINAL_SEED_SALT)
    private val colorRng = Random(seed xor COLOR_SEED_SALT)
    private val longRng = Random(seed xor LONGITUDINAL_SEED_SALT)
    private val countRng = Random(seed xor COUNT_SEED_SALT)

    private var placementIndex = 0
    private var previousCentreX: Float? = null
    private var previousCentreY: Float? = null

    fun appendPoint(x: Float, y: Float): List<Dab> {
        if (diameter <= 0f) return emptyList()
        val centres = placer.append(x, y)
        if (centres.isEmpty()) return emptyList()

        val out = ArrayList<Dab>()
        var i = 0
        while (i + 1 < centres.size) {
            val cx = centres[i]
            val cy = centres[i + 1]
            val px = previousCentreX
            val py = previousCentreY
            val headingDeg = if (px == null || py == null) {
                0f
            } else {
                val dx = cx - px
                val dy = cy - py
                if (dx == 0f && dy == 0f) 0f else atan2(dy, dx) * RAD_TO_DEG
            }
            val at = placementIndex * step
            val resolvedCount = resolveDabCount(countRng)
            repeat(resolvedCount) {
                val sizeR = rng.nextFloat()
                val opacR = rng.nextFloat()
                val scatR = rng.nextFloat()
                val longitudinalR = longRng.nextFloat()

                val radius = baseRadius * (1f - brush.sizeJitter * sizeR)
                val alpha = (brush.opacity * (1f - brush.opacityJitter * opacR)).coerceIn(0f, 1f)
                var dx = cx
                var dy = cy
                if (brush.scatter > 0f && diameter > 0f) {
                    val mag = brush.scatter * diameter * (scatR * 2f - 1f)
                    val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                    dx += mag * cos(perpRad)
                    dy += mag * sin(perpRad)
                }
                if (brush.scatterLongitudinal > 0f && diameter > 0f) {
                    val mag = brush.scatterLongitudinal * diameter * (longitudinalR * 2f - 1f)
                    val headingRad = headingDeg * DEG_TO_RAD
                    dx += mag * cos(headingRad)
                    dy += mag * sin(headingRad)
                }
                val angle = brush.angle +
                    (if (brush.followStroke) headingDeg else 0f) +
                    brush.rotationPerPx * at

                out += Dab(
                    x = dx,
                    y = dy,
                    radius = radius,
                    alpha = alpha,
                    angleDeg = angle,
                    tipRatio = brush.tipRatio,
                    hardness = brush.hardness.coerceIn(0f, 1f),
                    colorMix = brush.colorMix.coerceIn(0f, 1f),
                    sourceRandom = colorRng.nextFloat(),
                    mask = resolveStaticMask(dx, dy, headingDeg, at),
                )
            }
            previousCentreX = cx
            previousCentreY = cy
            placementIndex++
            i += 2
        }
        return out
    }

    fun reset() {
        placer.reset()
        placementIndex = 0
        previousCentreX = null
        previousCentreY = null
    }

    private fun resolveDabCount(rng: Random): Int {
        if (brush.count <= 1) return 1
        if (brush.countJitter <= 0f) return brush.count
        val factor = 1f - brush.countJitter * rng.nextFloat()
        return (brush.count * factor).roundToInt().coerceIn(1, brush.count)
    }

    private fun resolveStaticMask(x: Float, y: Float, headingDeg: Float, at: Float): MaskDab? {
        val config = brush.maskedBrush ?: return null
        val cfg = config.sanitized()
        var mx = x
        var my = y
        if (cfg.scatter > 0f) {
            val mag = cfg.scatter * diameter * cfg.sizeRatio * (maskRng.nextFloat() * 2f - 1f)
            val perp = (headingDeg + 90f) * DEG_TO_RAD
            mx += mag * cos(perp)
            my += mag * sin(perp)
        } else {
            maskRng.nextFloat()
        }
        if (cfg.scatterLongitudinal > 0f) {
            val mag = cfg.scatterLongitudinal * diameter * cfg.sizeRatio * (maskLongRng.nextFloat() * 2f - 1f)
            val headingRad = headingDeg * DEG_TO_RAD
            mx += mag * cos(headingRad)
            my += mag * sin(headingRad)
        } else {
            maskLongRng.nextFloat()
        }
        return MaskDab(
            x = mx,
            y = my,
            radius = diameter * cfg.sizeRatio / 2f,
            tipRatio = cfg.tipRatio,
            alpha = cfg.opacity,
            angleDeg = cfg.angle + (if (cfg.followStroke) headingDeg else 0f) + cfg.rotationPerPx * at,
            flowMultiplier = cfg.flow,
            invert = cfg.invert,
            blendMode = cfg.blendMode,
        )
    }

    private companion object {
        const val RAD_TO_DEG = 57.29578f
        const val DEG_TO_RAD = 0.017453292f
        const val MASK_SEED_SALT = 0x4D41534B5F544950L
        const val COLOR_SEED_SALT = 0x434F4C4F525F4D58L
        const val LONGITUDINAL_SEED_SALT = 0x4C4F4E475F534341L
        const val MASK_LONGITUDINAL_SEED_SALT = 0x4D41534B5F4C4F4EL
        const val COUNT_SEED_SALT = 0x434F554E545F4A54L
    }
}
