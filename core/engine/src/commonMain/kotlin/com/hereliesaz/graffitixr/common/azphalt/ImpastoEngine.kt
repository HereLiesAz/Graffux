package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

private const val IMPASTO_DEG_TO_RAD = 0.017453292f


/** Stroke-local volume state for the opt-in Impasto-v2 material transfer path. */
data class ImpastoMaterialStrokeState(
    val reservoirLoad: Float = 1f,
) {
    fun sanitized(): ImpastoMaterialStrokeState = copy(reservoirLoad = reservoirLoad.coerceIn(0f, 1f))
}

/** Accounting returned by [ImpastoEngine.transferMaterialStroke]. */
data class ImpastoMaterialTransferStats(
    val state: ImpastoMaterialStrokeState,
    val depositedHeight: Float,
    val pickedUpHeight: Float,
    val dirtyRegion: DirtyRegion?,
)

/** Accounting returned by [ImpastoEngine.levelWetHeight]. */
data class ImpastoLevelStats(
    val activeTilesProcessed: Int,
    val pixelsVisited: Int,
    val edgesMoved: Int,
    val transferredHeight: Float,
)

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


    /**
     * Impasto-v2 height transfer. Existing [deposit]/[depositStroke] remain the v1 compatibility
     * contract; callers opt into this method explicitly.
     *
     * The brush reservoir is normalized rather than a literal millilitre volume. A dab deposits
     * proportionally to current load, resolved contact depth and [PaintMedium.heightResponse].
     * Pickup can only fill capacity already freed by deposition and removes real height from the
     * contacted canvas before contaminating subsequent dabs. The load exchange is deliberately
     * bounded per dab so a single broad stamp cannot consume an entire brush simply because it
     * covers more pixels.
     */
    fun transferMaterialStroke(
        height: FloatArray,
        width: Int,
        imgHeight: Int,
        dabs: List<Dab>,
        hardness: Float,
        thicknessRate: Float,
        medium: PaintMedium,
        initialState: ImpastoMaterialStrokeState = ImpastoMaterialStrokeState(),
        substrateProfile: SubstrateProfile = SubstrateProfile.SMOOTH,
        substrateField: SubstrateField? = null,
    ): ImpastoMaterialTransferStats {
        if (width <= 0 || imgHeight <= 0 || height.size < width * imgHeight) {
            return ImpastoMaterialTransferStats(initialState.sanitized(), 0f, 0f, null)
        }
        val material = medium.sanitized()
        val rate = thicknessRate.coerceAtLeast(0f)
        var load = initialState.sanitized().reservoirLoad
        if (rate <= 0f || dabs.isEmpty() ||
            (material.heightResponse <= 0f && material.pickupRate <= 0f)
        ) {
            return ImpastoMaterialTransferStats(ImpastoMaterialStrokeState(load), 0f, 0f, null)
        }

        val profile = substrateProfile.sanitized()
        var depositedTotal = 0f
        var pickedUpTotal = 0f
        var dirty: DirtyRegion? = null

        for (dab in dabs) {
            val radius = dab.radius
            if (radius <= 0f) continue
            val cx = dab.x
            val cy = dab.y
            val minX = max(0, floor(cx - radius).toInt())
            val maxX = min(width - 1, ceil(cx + radius).toInt())
            val minY = max(0, floor(cy - radius).toInt())
            val maxY = min(imgHeight - 1, ceil(cy + radius).toInt())
            if (minX > maxX || minY > maxY) continue

            val loadAtStart = load
            val capacityAtStart = (1f - loadAtStart).coerceIn(0f, 1f)
            val contactDepth = dab.contactDepth.coerceIn(0f, 1f)
            val alphaFlow = dab.alpha.coerceIn(0f, 1f) * dab.flowMultiplier.coerceAtLeast(0f)
            var depositedThisDab = 0f
            var pickedThisDab = 0f
            var coverageMass = 0f
            var touched = false

            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x + 0.5f - cx
                    val dy = y + 0.5f - cy
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist >= radius) continue
                    val coverage = BrushStamps.stampCoverage((dist / radius).coerceIn(0f, 1f), hardness)
                    if (coverage <= 0f) continue
                    val idx = y * width + x
                    val before = height[idx].coerceIn(0f, 1f)
                    val substrateHeight = substrateField?.sampleHeight(x + 0.5f, y + 0.5f, profile)
                        ?: profile.baseHeight
                    val gate = SubstrateDepositionModel.coverageMultiplier(
                        contactDepth = contactDepth,
                        localPaintHeightContribution = before,
                        substrateResponse = material.substrateResponse,
                        substrateHeight = substrateHeight,
                    )
                    val contact = (coverage * contactDepth * alphaFlow * gate).coerceIn(0f, 1f)
                    if (contact <= 0f) continue
                    coverageMass += coverage

                    // Pickup happens from the pre-deposition surface and only into free capacity.
                    val removalRequest = rate * material.pickupRate * capacityAtStart * contact
                    val removed = min(before, removalRequest)
                    var local = (before - removed).coerceAtLeast(0f)
                    pickedThisDab += removed

                    val increment = rate * material.heightResponse * material.depositionRate *
                        loadAtStart * contact
                    val after = BrushStamps.buildUp(local, increment)
                    depositedThisDab += (after - local).coerceAtLeast(0f)
                    local = after.coerceIn(0f, 1f)

                    if (local != before) {
                        height[idx] = local
                        touched = true
                    }
                }
            }

            if (coverageMass > 0f) {
                val denom = (coverageMass * rate.coerceAtLeast(MIN_TRANSFER_RATE)).coerceAtLeast(MIN_TRANSFER_RATE)
                val spent = ((depositedThisDab / denom) * CONTACT_LOAD_EXCHANGE)
                    .coerceIn(0f, loadAtStart)
                val afterDeposit = (loadAtStart - spent).coerceIn(0f, 1f)
                val capacityAfterDeposit = 1f - afterDeposit
                val refill = ((pickedThisDab / denom) * CONTACT_LOAD_EXCHANGE)
                    .coerceIn(0f, capacityAfterDeposit)
                load = (afterDeposit + refill).coerceIn(0f, 1f)
            }

            depositedTotal += depositedThisDab
            pickedUpTotal += pickedThisDab
            if (touched) {
                val dabRegion = DirtyRegion(minX, minY, maxX + 1, maxY + 1)
                dirty = dirty?.union(dabRegion) ?: dabRegion
            }
        }

        return ImpastoMaterialTransferStats(
            state = ImpastoMaterialStrokeState(load),
            depositedHeight = depositedTotal,
            pickedUpHeight = pickedUpTotal,
            dirtyRegion = dirty,
        )
    }

    /**
     * Bounded wet-height leveling over Phase-4 active material tiles.
     *
     * Height exchange is pairwise and equal/opposite, so absent clamping it conserves paint height.
     * Flow follows the combined paint+substrate surface, which naturally lets material settle into
     * substrate valleys. Yield recovery is analytic: as wetness falls, the effective yield threshold
     * rises toward [PaintMedium.yieldLikeStrength], freezing small gradients without another
     * persistent structure image. High wetness represents recently sheared/weak structure.
     */
    fun levelWetHeight(
        height: FloatArray,
        width: Int,
        imgHeight: Int,
        wetness: PersistentWetnessField,
        medium: PaintMedium,
        deltaSeconds: Float,
        region: DirtyRegion? = null,
        substrateProfile: SubstrateProfile = SubstrateProfile.SMOOTH,
        substrateField: SubstrateField? = null,
        iterations: Int = DEFAULT_LEVELING_ITERATIONS,
    ): ImpastoLevelStats {
        require(width == wetness.width && imgHeight == wetness.height) {
            "Impasto wetness dimensions must match height dimensions"
        }
        require(height.size >= width * imgHeight) {
            "Impasto height must contain width*height values"
        }
        val material = medium.sanitized()
        val dt = deltaSeconds.coerceAtLeast(0f)
        val count = iterations.coerceIn(0, MAX_LEVELING_ITERATIONS)
        val active = wetness.activeTileCoordinates()
        if (active.isEmpty() || dt <= 0f || count == 0 || material.levelingRate <= 0f) {
            return ImpastoLevelStats(active.size, 0, 0, 0f)
        }

        val bounds = (region ?: DirtyRegion(0, 0, width, imgHeight)).clampTo(width, imgHeight)
        if (bounds.isEmpty) return ImpastoLevelStats(active.size, 0, 0, 0f)

        val tileSize = wetness.tileSize
        val columns = (width + tileSize - 1) / tileSize
        val activeIds = HashSet<Int>(active.size * 2)
        for ((tx, ty) in active) activeIds += ty * columns + tx
        val profile = substrateProfile.sanitized()

        fun tileActive(x: Int, y: Int): Boolean =
            ((y / tileSize) * columns + (x / tileSize)) in activeIds

        fun terrain(x: Int, y: Int): Float {
            if (material.substrateResponse <= 0f) return 0f
            val sampled = substrateField?.sampleHeight(x + 0.5f, y + 0.5f, profile)
                ?: profile.baseHeight
            return sampled * material.substrateResponse
        }

        var visited = 0
        var movedEdges = 0
        var transferred = 0f
        val stepBase = (material.levelingRate * dt / count).coerceIn(0f, MAX_LEVEL_EDGE_STEP)

        fun exchange(a: Int, ax: Int, ay: Int, b: Int, bx: Int, by: Int) {
            val wet = ((wetness.wetnessAt(ax, ay) + wetness.wetnessAt(bx, by)) * 0.5f)
                .coerceIn(0f, 1f)
            if (wet <= 0f) return
            val fluidity = wet * (1f - material.viscosity)
            if (fluidity <= 0f) return

            val surfaceA = height[a].coerceAtLeast(0f) + terrain(ax, ay)
            val surfaceB = height[b].coerceAtLeast(0f) + terrain(bx, by)
            val diff = surfaceB - surfaceA
            val recoveredStructure = material.yieldLikeStrength * (1f - wet)
            val threshold = recoveredStructure * YIELD_HEIGHT_THRESHOLD
            val excess = abs(diff) - threshold
            if (excess <= 0f) return

            val amount = excess * stepBase * fluidity
            if (amount <= 0f) return
            if (diff > 0f) {
                val moved = min(amount, height[b].coerceAtLeast(0f))
                if (moved <= 0f) return
                height[b] = (height[b] - moved).coerceAtLeast(0f)
                height[a] = (height[a] + moved).coerceIn(0f, 1f)
                transferred += moved
            } else {
                val moved = min(amount, height[a].coerceAtLeast(0f))
                if (moved <= 0f) return
                height[a] = (height[a] - moved).coerceAtLeast(0f)
                height[b] = (height[b] + moved).coerceIn(0f, 1f)
                transferred += moved
            }
            movedEdges++
        }

        repeat(count) {
            for ((tx, ty) in active) {
                val tileLeft = tx * tileSize
                val tileTop = ty * tileSize
                val left = max(bounds.left, tileLeft)
                val top = max(bounds.top, tileTop)
                val right = min(bounds.right, min(width, tileLeft + tileSize))
                val bottom = min(bounds.bottom, min(imgHeight, tileTop + tileSize))
                if (left >= right || top >= bottom) continue

                for (y in top until bottom) {
                    var idx = y * width + left
                    for (x in left until right) {
                        visited++
                        if (x + 1 < bounds.right && tileActive(x + 1, y)) {
                            exchange(idx, x, y, idx + 1, x + 1, y)
                        }
                        if (y + 1 < bounds.bottom && tileActive(x, y + 1)) {
                            exchange(idx, x, y, idx + width, x, y + 1)
                        }
                        idx++
                    }
                }
            }
        }

        return ImpastoLevelStats(
            activeTilesProcessed = active.size,
            pixelsVisited = visited,
            edgesMoved = movedEdges,
            transferredHeight = transferred,
        )
    }

    /**
     * V2 presentation: historical relief diffuse plus wetness-driven glossy/specular response.
     * Canonical pigment/height/wetness inputs are never mutated; lighting remains presentation only.
     */
    fun shadeMaterialInto(
        out: IntArray,
        rawColorPixels: IntArray,
        height: FloatArray,
        wetness: PersistentWetnessField?,
        width: Int,
        imgHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        lightAzimuthDeg: Float,
        lightElevationDeg: Float,
        reliefStrength: Float,
        medium: PaintMedium,
    ) {
        val x0 = left.coerceIn(0, width)
        val x1 = right.coerceIn(0, width)
        val y0 = top.coerceIn(0, imgHeight)
        val y1 = bottom.coerceIn(0, imgHeight)
        if (x0 >= x1 || y0 >= y1 || width <= 0 || imgHeight <= 0) return
        val material = medium.sanitized()
        if (reliefStrength <= 0f && material.wetSpecularStrength <= 0f) {
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
        val baselineDiffuse = lz
        // Fixed view vector (0,0,1); Blinn half-vector is normalize(light + view).
        val hx0 = lx
        val hy0 = ly
        val hz0 = lz + 1f
        val hInv = 1f / sqrt(hx0 * hx0 + hy0 * hy0 + hz0 * hz0)
        val hx = hx0 * hInv
        val hy = hy0 * hInv
        val hz = hz0 * hInv

        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dHdx = (at(height, width, imgHeight, x + 1, y) - at(height, width, imgHeight, x - 1, y)) / 2f
                val dHdy = (at(height, width, imgHeight, x, y + 1) - at(height, width, imgHeight, x, y - 1)) / 2f
                val nx0 = -dHdx
                val ny0 = -dHdy
                val nz0 = 1f
                val invLen = 1f / sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
                val nx = nx0 * invLen
                val ny = ny0 * invLen
                val nz = nz0 * invLen
                val diffuse = nx * lx + ny * ly + nz * lz
                val multiplier = (1f + reliefStrength.coerceAtLeast(0f) * (diffuse - baselineDiffuse))
                    .coerceIn(0f, 3f)

                val wet = wetness?.wetnessAt(x, y)?.coerceIn(0f, 1f) ?: 0f
                val roughness = (
                    material.baseRoughness * (1f - wet) + MIN_WET_ROUGHNESS * wet
                    ).coerceIn(MIN_WET_ROUGHNESS, 1f)
                val shininess = 4f + (1f - roughness) * 60f
                val nDotH = (nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f)
                val specular = material.wetSpecularStrength * wet * nDotH.toDouble().pow(shininess.toDouble()).toFloat()
                out[y * width + x] = shadeRgb(rawColorPixels[y * width + x], multiplier, specular)
            }
        }
    }


    private fun at(height: FloatArray, width: Int, imgHeight: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, imgHeight - 1)
        return height[cy * width + cx]
    }

    /** Scales only the RGB channels of a packed ARGB int; alpha is preserved exactly. */
    private fun shadeRgb(argb: Int, factor: Float, specular: Float): Int {
        val a = argb ushr 24 and 0xFF
        val add = (255f * specular.coerceIn(0f, 1f))
        val r = ((argb shr 16 and 0xFF) * factor + add).toInt().coerceIn(0, 255)
        val g = ((argb shr 8 and 0xFF) * factor + add).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor + add).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun scaleRgb(argb: Int, factor: Float): Int {
        val a = argb ushr 24 and 0xFF
        val r = ((argb shr 16 and 0xFF) * factor).let { it.toInt().coerceIn(0, 255) }
        val g = ((argb shr 8 and 0xFF) * factor).let { it.toInt().coerceIn(0, 255) }
        val b = ((argb and 0xFF) * factor).let { it.toInt().coerceIn(0, 255) }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    private const val CONTACT_LOAD_EXCHANGE = 0.12f
    private const val MIN_TRANSFER_RATE = 1e-4f
    private const val YIELD_HEIGHT_THRESHOLD = 0.08f
    private const val MAX_LEVEL_EDGE_STEP = 0.25f
    private const val MIN_WET_ROUGHNESS = 0.08f
    const val DEFAULT_LEVELING_ITERATIONS = 2
    const val MAX_LEVELING_ITERATIONS = 4

}
