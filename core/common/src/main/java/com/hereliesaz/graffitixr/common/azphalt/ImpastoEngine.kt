package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val IMPASTO_DEG_TO_RAD = 0.017453292f

/**
 * Krita-style paint-thickness (impasto) primitive: a height map that builds up alongside colour
 * and is later shaded into a relief highlight/shadow. Renderer-neutral like [BrushStamps]/[Dab] —
 * CPU raster and a future GPU path are meant to consume the same deposit/shade functions rather
 * than each reimplementing height accumulation and lighting.
 *
 * Height values are normalized 0..1 "paint thickness", entirely independent of the colour channel.
 * Nothing here persists a height map anywhere, attaches one to a layer, or feeds one into
 * [BrushStamps]/[StampBrushRenderer] automatically — callers own storage, lifetime, and wiring.
 * Deliberately scoped this way: layer persistence (save/load, undo snapshots, export) is a much
 * larger, riskier change this primitive does not attempt.
 */
object ImpastoEngine {

    /**
     * Deposits height for one resolved dab into [height] (row-major, size `width * imgHeight`),
     * using the same disc/hardness coverage falloff as [BrushStamps.stampCoverage] so a dab's
     * thickness footprint lines up with its colour footprint. Accumulates via [BrushStamps.buildUp]
     * — the same asymptotic curve alpha build-up already uses — so repeated passes thicken paint
     * without ever exceeding 1. A dab's [Dab.tipRatio] (elongated tips) is intentionally not
     * modelled here; the footprint is always circular. [hardness] follows the same 0..1 meaning as
     * a brush's hardness. A non-positive [thicknessRate] or [Dab.radius] is a no-op.
     */
    fun deposit(
        height: FloatArray,
        width: Int,
        imgHeight: Int,
        dab: Dab,
        hardness: Float,
        thicknessRate: Float,
    ) {
        val radius = dab.radius
        if (thicknessRate <= 0f || radius <= 0f || width <= 0 || imgHeight <= 0) return
        val cx = dab.x
        val cy = dab.y
        val minX = max(0, floor(cx - radius).toInt())
        val maxX = min(width - 1, ceil(cx + radius).toInt())
        val minY = max(0, floor(cy - radius).toInt())
        val maxY = min(imgHeight - 1, ceil(cy + radius).toInt())
        val flow = dab.flowMultiplier.coerceAtLeast(0f)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val dist = sqrt(dx * dx + dy * dy)
                val rNorm = (dist / radius).coerceIn(0f, 1f)
                val coverage = BrushStamps.stampCoverage(rNorm, hardness)
                if (coverage <= 0f) continue
                val idx = y * width + x
                val increment = thicknessRate * coverage * dab.alpha.coerceIn(0f, 1f) * flow
                height[idx] = BrushStamps.buildUp(height[idx], increment)
            }
        }
    }

    /** Deposits an entire resolved stroke's worth of dabs in order. */
    fun depositStroke(
        height: FloatArray,
        width: Int,
        imgHeight: Int,
        dabs: List<Dab>,
        hardness: Float,
        thicknessRate: Float,
    ) {
        for (dab in dabs) deposit(height, width, imgHeight, dab, hardness, thicknessRate)
    }

    /**
     * Shades [colorPixels] (row-major ARGB, size `width * imgHeight`) using [height]'s local slope
     * as a simple relief light — an emboss-style approximation matching Krita's Impasto rendering,
     * not a physically based normal map. Returns a new pixel array; neither input is mutated.
     *
     * A perfectly flat height map (uniform value, zero gradient everywhere) leaves every pixel
     * unchanged regardless of light direction or [strength], by construction: the per-pixel
     * multiplier is relative to the flat-region baseline, not an absolute brightness. [strength]
     * <= 0 also short-circuits to an unmodified copy.
     */
    fun shade(
        colorPixels: IntArray,
        height: FloatArray,
        width: Int,
        imgHeight: Int,
        lightAzimuthDeg: Float,
        lightElevationDeg: Float,
        strength: Float,
    ): IntArray {
        val out = colorPixels.copyOf()
        if (strength <= 0f || width <= 0 || imgHeight <= 0) return out

        val azimuthRad = lightAzimuthDeg * IMPASTO_DEG_TO_RAD
        val elevationRad = lightElevationDeg * IMPASTO_DEG_TO_RAD
        val lx = cos(azimuthRad) * cos(elevationRad)
        val ly = sin(azimuthRad) * cos(elevationRad)
        val lz = sin(elevationRad)
        // The diffuse response of a perfectly flat surface (normal = (0,0,1)) is just lz. Shading
        // is expressed relative to that baseline so flat/unpainted regions are always left alone.
        val baselineDiffuse = lz

        for (y in 0 until imgHeight) {
            for (x in 0 until width) {
                val dHdx = (at(height, width, imgHeight, x + 1, y) - at(height, width, imgHeight, x - 1, y)) / 2f
                val dHdy = (at(height, width, imgHeight, x, y + 1) - at(height, width, imgHeight, x, y - 1)) / 2f
                if (dHdx == 0f && dHdy == 0f) continue
                val nx = -dHdx
                val ny = -dHdy
                val nz = 1f
                val invLen = 1f / sqrt(nx * nx + ny * ny + nz * nz)
                val diffuse = (nx * invLen * lx + ny * invLen * ly + nz * invLen * lz)
                val multiplier = (1f + strength * (diffuse - baselineDiffuse)).coerceIn(0f, 3f)
                if (multiplier == 1f) continue
                val idx = y * width + x
                out[idx] = scaleRgb(colorPixels[idx], multiplier)
            }
        }
        return out
    }

    private fun at(height: FloatArray, width: Int, imgHeight: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, imgHeight - 1)
        return height[cy * width + cx]
    }

    /** Scales only the RGB channels of a packed ARGB int; alpha is preserved exactly. */
    private fun scaleRgb(argb: Int, factor: Float): Int {
        val a = argb ushr 24 and 0xFF
        val r = ((argb shr 16 and 0xFF) * factor).let { it.toInt().coerceIn(0, 255) }
        val g = ((argb shr 8 and 0xFF) * factor).let { it.toInt().coerceIn(0, 255) }
        val b = ((argb and 0xFF) * factor).let { it.toInt().coerceIn(0, 255) }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
