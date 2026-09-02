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
        shadeInto(out, colorPixels, height, width, imgHeight, 0, 0, width, imgHeight, lightAzimuthDeg, lightElevationDeg, strength)
        return out
    }

    /**
     * Regional counterpart to [shade], for callers that need to re-shade only a small area cheaply
     * — e.g. a live stamp-stroke preview, where re-running the full [shade] every drag frame would
     * turn a once-per-stroke O(width×height) pass into a many-times-per-stroke one (see roadmap
     * item 12's live-preview note). Writes shaded pixels directly into [out] (which the caller
     * should have pre-seeded with [rawColorPixels]'s unshaded values, NOT a previously-shaded
     * frame's output — see below) for exactly the rows/columns in
     * `[left, right) x [top, bottom)`, clamped to the canvas; every other pixel of [out] is left
     * untouched.
     *
     * [rawColorPixels] must be the *unshaded*, purely-painted colour for every pixel this call
     * touches — never a previously shaded result. [shade]'s multiplier is not idempotent: applying
     * it twice to an already-shaded pixel does not converge to the correct answer for the pixel's
     * final height, it just compounds an error. A caller that re-shades the same region across
     * multiple frames (as height keeps changing under a held brush) must always re-derive from the
     * raw painted colour, e.g. by keeping a separate unshaded canvas around (mirroring how
     * `EditorViewModel`'s live preview already keeps `stampLiveBitmap` as the raw dab-compositing
     * target and applies shading into a *second*, display-only bitmap — see its wiring for the
     * concrete pattern).
     *
     * Because [shade]'s gradient at a pixel reads its immediate neighbours (`x±1`, `y±1`), a caller
     * whose *raw colour* changed only inside some bounds must still re-shade a 1-pixel-wider region
     * than that to also refresh every pixel whose gradient input changed — this function does not
     * do that widening itself (it has no way to know which bounds are "the new paint" vs. "already
     * padded"), so callers should pass an already-dilated region; see [DirtyRegion] for computing
     * one and padding it before calling this.
     */
    fun shadeInto(
        out: IntArray,
        rawColorPixels: IntArray,
        height: FloatArray,
        width: Int,
        imgHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        lightAzimuthDeg: Float,
        lightElevationDeg: Float,
        strength: Float,
    ) {
        val x0 = left.coerceIn(0, width)
        val x1 = right.coerceIn(0, width)
        val y0 = top.coerceIn(0, imgHeight)
        val y1 = bottom.coerceIn(0, imgHeight)
        if (x0 >= x1 || y0 >= y1 || width <= 0 || imgHeight <= 0) return
        if (strength <= 0f) {
            for (y in y0 until y1) {
                val row = y * width
                for (x in x0 until x1) out[row + x] = rawColorPixels[row + x]
            }
            return
        }

        val azimuthRad = lightAzimuthDeg * IMPASTO_DEG_TO_RAD
        val elevationRad = lightElevationDeg * IMPASTO_DEG_TO_RAD
        val lx = cos(azimuthRad) * cos(elevationRad)
        val ly = sin(azimuthRad) * cos(elevationRad)
        val lz = sin(elevationRad)
        // The diffuse response of a perfectly flat surface (normal = (0,0,1)) is just lz. Shading
        // is expressed relative to that baseline so flat/unpainted regions are always left alone.
        val baselineDiffuse = lz

        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dHdx = (at(height, width, imgHeight, x + 1, y) - at(height, width, imgHeight, x - 1, y)) / 2f
                val dHdy = (at(height, width, imgHeight, x, y + 1) - at(height, width, imgHeight, x, y - 1)) / 2f
                val idx = y * width + x
                if (dHdx == 0f && dHdy == 0f) {
                    out[idx] = rawColorPixels[idx]
                    continue
                }
                val nx = -dHdx
                val ny = -dHdy
                val nz = 1f
                val invLen = 1f / sqrt(nx * nx + ny * ny + nz * nz)
                val diffuse = (nx * invLen * lx + ny * invLen * ly + nz * invLen * lz)
                val multiplier = (1f + strength * (diffuse - baselineDiffuse)).coerceIn(0f, 3f)
                out[idx] = if (multiplier == 1f) rawColorPixels[idx] else scaleRgb(rawColorPixels[idx], multiplier)
            }
        }
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
