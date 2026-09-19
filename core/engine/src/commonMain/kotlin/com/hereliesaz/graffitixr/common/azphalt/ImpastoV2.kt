package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Opt-in Phase-5 material-height behaviour. All-zero transport/pickup/wetness values keep the
 * historical [ImpastoEngine] path authoritative; this model is only used when a brush/media profile
 * explicitly requests v2 behaviour.
 */
data class ImpastoV2Config(
    /** Fraction of existing wet height removed by brush contact, 0..1. */
    val pickupRate: Float = 0f,
    /** Vehicle/wetness added under a height-producing contact, 0..1. */
    val wetnessDeposit: Float = 0f,
    /** Bounded pairwise height-leveling rate per simulation second, 0..1. */
    val levelingRate: Float = 0f,
    /** Apparent body/yield resistance, 0 = loose, 1 = stiff. */
    val body: Float = 1f,
    /** Additional resistance to flow, 0 = free, 1 = immobile. */
    val viscosity: Float = 0f,
    /** Strength of substrate/tooth resistance to v2 height interaction. */
    val substrateInteraction: Float = 0f,
) {
    fun sanitized(): ImpastoV2Config = copy(
        pickupRate = pickupRate.coerceIn(0f, 1f),
        wetnessDeposit = wetnessDeposit.coerceIn(0f, 1f),
        levelingRate = levelingRate.coerceIn(0f, 1f),
        body = body.coerceIn(0f, 1f),
        viscosity = viscosity.coerceIn(0f, 1f),
        substrateInteraction = substrateInteraction.coerceIn(0f, 1f),
    )

    val usesV2: Boolean
        get() = pickupRate != 0f || wetnessDeposit != 0f || levelingRate != 0f ||
            substrateInteraction != 0f
}

/** Scratch owned by a layer/session so live simulation never allocates a full-canvas delta per tick. */
class ImpastoV2Workspace(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
    }

    internal val delta = FloatArray(width * height)
}

/** Accounting for one ordered height-contact pass. */
data class ImpastoContactStats(
    val depositedHeight: Float,
    val pickedUpHeight: Float,
    val pixelsTouched: Int,
    val dirtyRegion: DirtyRegion?,
)

/** Accounting for one bounded wet-height settling step. */
data class ImpastoLevelingStats(
    val activeTilesProcessed: Int,
    val pixelsProcessed: Int,
    val edgesProcessed: Int,
    val heightBefore: Float,
    val heightAfter: Float,
)

/**
 * Deterministic CPU reference for Phase-5 height evolution.
 *
 * Height stays normalized 0..1 and shares the existing Phase-4 wetness field. Structure is a
 * normalized per-pixel scalar: 1 = recovered/stiff, 0 = fully sheared/mobile. Contact softens
 * structure; explicit simulation time recovers it. No wall clock is consulted.
 */
object ImpastoV2Engine {
    const val DEFAULT_ITERATIONS = 2
    const val MAX_ITERATIONS = 4

    /**
     * Applies ordered dab contact to height/structure/wetness. Existing height is picked up before
     * new volume is deposited, so a brush does not immediately remove material it just laid down.
     */
    fun applyContactStroke(
        height: FloatArray,
        structure: FloatArray,
        width: Int,
        canvasHeight: Int,
        dabs: List<Dab>,
        hardness: Float,
        thicknessRate: Float,
        config: ImpastoV2Config,
        wetness: PersistentWetnessField?,
        substrateProfile: SubstrateProfile = SubstrateProfile.SMOOTH,
        substrateField: SubstrateField? = null,
    ): ImpastoContactStats {
        require(height.size >= width * canvasHeight)
        require(structure.size >= width * canvasHeight)
        if (width <= 0 || canvasHeight <= 0 || dabs.isEmpty()) {
            return ImpastoContactStats(0f, 0f, 0, null)
        }
        val cfg = config.sanitized()
        var deposited = 0f
        var picked = 0f
        var touched = 0
        var dirty: DirtyRegion? = null

        for (dab in dabs) {
            val radius = dab.radius
            if (radius <= 0f) continue
            val minX = max(0, floor(dab.x - radius).toInt())
            val maxX = min(width - 1, ceil(dab.x + radius).toInt())
            val minY = max(0, floor(dab.y - radius).toInt())
            val maxY = min(canvasHeight - 1, ceil(dab.y + radius).toInt())
            if (minX > maxX || minY > maxY) continue
            dirty = dirty?.union(DirtyRegion(minX, minY, maxX + 1, maxY + 1))
                ?: DirtyRegion(minX, minY, maxX + 1, maxY + 1)

            val flow = dab.flowMultiplier.coerceAtLeast(0f)
            val contact = dab.contactDepth.coerceIn(0f, 1f)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x + 0.5f - dab.x
                    val dy = y + 0.5f - dab.y
                    val dist = sqrt(dx * dx + dy * dy)
                    val coverage = BrushStamps.stampCoverage((dist / radius).coerceIn(0f, 1f), hardness)
                    if (coverage <= 0f) continue
                    val idx = y * width + x
                    val localHeight = height[idx].coerceIn(0f, 1f)
                    val substrateHeight = substrateField?.sampleHeight(x + 0.5f, y + 0.5f, substrateProfile)
                        ?: substrateProfile.sampleWithoutTexture().height
                    val substrateGate = SubstrateDepositionModel.coverageMultiplier(
                        contactDepth = contact,
                        localPaintHeightContribution = localHeight,
                        substrateResponse = cfg.substrateInteraction,
                        substrateHeight = substrateHeight,
                    )
                    if (substrateGate <= 0f) continue

                    val localWetness = wetness?.wetnessAt(x, y)?.coerceIn(0f, 1f) ?: 0f
                    val pickupFraction = (
                        cfg.pickupRate * coverage * contact * localWetness * substrateGate
                        ).coerceIn(0f, 1f)
                    val removed = localHeight * pickupFraction
                    val retained = (localHeight - removed).coerceAtLeast(0f)
                    if (removed > 0f) picked += removed

                    val increment = (
                        thicknessRate.coerceAtLeast(0f) *
                            coverage *
                            dab.alpha.coerceIn(0f, 1f) *
                            flow *
                            contact *
                            substrateGate
                        ).coerceAtLeast(0f)
                    val next = BrushStamps.buildUp(retained, increment)
                    height[idx] = next
                    if (next > retained) deposited += next - retained

                    // Contact shear breaks down structure locally; body controls how strongly the
                    // material resists that softening but never disables it completely.
                    val shear = coverage * contact * (0.35f + 0.65f * (1f - cfg.body))
                    structure[idx] = (structure[idx].coerceIn(0f, 1f) - shear).coerceIn(0f, 1f)

                    val wetDeposit = cfg.wetnessDeposit * coverage * dab.alpha.coerceIn(0f, 1f) * flow
                    if (wetDeposit > 0f) wetness?.addWetness(x, y, wetDeposit)
                    touched++
                }
            }
        }

        return ImpastoContactStats(deposited, picked, touched, dirty)
    }

    /**
     * Levels only already-active wet tiles. Pairwise right/down flux is equal/opposite, so with no
     * clipping the height channel is conserved. Inactive adjacent tiles are never woken implicitly.
     */
    fun advanceWetHeight(
        height: FloatArray,
        structure: FloatArray,
        width: Int,
        canvasHeight: Int,
        wetness: PersistentWetnessField,
        workspace: ImpastoV2Workspace,
        deltaSeconds: Float,
        config: ImpastoV2Config,
        substrateProfile: SubstrateProfile = SubstrateProfile.SMOOTH,
        substrateField: SubstrateField? = null,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ImpastoLevelingStats {
        require(height.size >= width * canvasHeight)
        require(structure.size >= width * canvasHeight)
        require(workspace.width == width && workspace.height == canvasHeight)
        require(wetness.width == width && wetness.height == canvasHeight)

        val cfg = config.sanitized()
        val active = wetness.activeTileCoordinates()
        val dt = deltaSeconds.coerceAtLeast(0f)
        val count = iterations.coerceIn(0, MAX_ITERATIONS)
        if (active.isEmpty() || dt <= 0f || cfg.levelingRate <= 0f || count == 0) {
            return ImpastoLevelingStats(active.size, 0, 0, activeHeight(height, wetness), activeHeight(height, wetness))
        }

        val tileSize = wetness.tileSize
        val columns = (width + tileSize - 1) / tileSize
        val activeIds = HashSet<Int>(active.size * 2)
        for ((tx, ty) in active) activeIds += ty * columns + tx

        var before = 0f
        var pixelsProcessed = 0
        forEachActivePixel(active, tileSize, width, canvasHeight) { index, _, _ ->
            workspace.delta[index] = 0f
            before += height[index]
        }

        var edges = 0
        val perIteration = cfg.levelingRate * dt / count
        repeat(count) {
            forEachActivePixel(active, tileSize, width, canvasHeight) { index, x, y ->
                workspace.delta[index] = 0f
            }

            forEachActivePixel(active, tileSize, width, canvasHeight) { index, x, y ->
                pixelsProcessed++
                if (x + 1 < width && isTileActive(x + 1, y, tileSize, columns, activeIds)) {
                    exchange(
                        height, structure, wetness, workspace.delta,
                        index, index + 1, x, y, x + 1, y,
                        perIteration, cfg, substrateProfile, substrateField,
                    )
                    edges++
                }
                if (y + 1 < canvasHeight && isTileActive(x, y + 1, tileSize, columns, activeIds)) {
                    exchange(
                        height, structure, wetness, workspace.delta,
                        index, index + width, x, y, x, y + 1,
                        perIteration, cfg, substrateProfile, substrateField,
                    )
                    edges++
                }
            }

            forEachActivePixel(active, tileSize, width, canvasHeight) { index, _, _ ->
                height[index] = (height[index] + workspace.delta[index]).coerceIn(0f, 1f)
            }
        }

        // Structure recovers only where material is still active/wet. Once dry, mobility is zero
        // regardless of structure, so scanning dry canvas just to push structure to 1 would be waste.
        val recoveryRate = 0.35f + 1.65f * cfg.body
        val recovery = 1f - exp((-recoveryRate * dt).toDouble()).toFloat()
        forEachActivePixel(active, tileSize, width, canvasHeight) { index, _, _ ->
            val current = structure[index].coerceIn(0f, 1f)
            structure[index] = (current + (1f - current) * recovery).coerceIn(0f, 1f)
        }

        val after = activeHeight(height, wetness)
        return ImpastoLevelingStats(active.size, pixelsProcessed, edges, before, after)
    }

    private fun exchange(
        height: FloatArray,
        structure: FloatArray,
        wetness: PersistentWetnessField,
        delta: FloatArray,
        a: Int,
        b: Int,
        ax: Int,
        ay: Int,
        bx: Int,
        by: Int,
        stepBase: Float,
        cfg: ImpastoV2Config,
        substrateProfile: SubstrateProfile,
        substrateField: SubstrateField?,
    ) {
        val diff = height[b] - height[a]
        if (diff == 0f) return
        val wet = ((wetness.wetnessAt(ax, ay) + wetness.wetnessAt(bx, by)) * 0.5f).coerceIn(0f, 1f)
        if (wet <= 0f) return
        val structureAvg = ((structure[a] + structure[b]) * 0.5f).coerceIn(0f, 1f)
        val yieldThreshold = cfg.body * structureAvg * 0.025f
        if (kotlin.math.abs(diff) <= yieldThreshold) return

        val substrateResistance = if (cfg.substrateInteraction <= 0f) 0f else {
            val sa = substrateField?.sample(ax + 0.5f, ay + 0.5f, substrateProfile)
                ?: substrateProfile.sampleWithoutTexture()
            val sb = substrateField?.sample(bx + 0.5f, by + 0.5f, substrateProfile)
                ?: substrateProfile.sampleWithoutTexture()
            cfg.substrateInteraction * (
                (sa.height + sb.height + sa.absorbency + sb.absorbency) * 0.25f
                ).coerceIn(0f, 1f)
        }

        val mobility = (
            wet *
                (1f - cfg.viscosity) *
                (1f - cfg.body * structureAvg) *
                (1f - substrateResistance)
            ).coerceIn(0f, 1f)
        val step = (stepBase * mobility).coerceIn(0f, 0.25f)
        if (step <= 0f) return
        val transferIntoA = diff * step
        delta[a] += transferIntoA
        delta[b] -= transferIntoA
    }

    private inline fun forEachActivePixel(
        tiles: List<Pair<Int, Int>>,
        tileSize: Int,
        width: Int,
        height: Int,
        block: (index: Int, x: Int, y: Int) -> Unit,
    ) {
        for ((tx, ty) in tiles) {
            val left = tx * tileSize
            val top = ty * tileSize
            val right = min(width, left + tileSize)
            val bottom = min(height, top + tileSize)
            for (y in top until bottom) {
                var index = y * width + left
                for (x in left until right) {
                    block(index, x, y)
                    index++
                }
            }
        }
    }

    private fun isTileActive(
        x: Int,
        y: Int,
        tileSize: Int,
        columns: Int,
        activeIds: Set<Int>,
    ): Boolean = ((y / tileSize) * columns + (x / tileSize)) in activeIds

    private fun activeHeight(height: FloatArray, wetness: PersistentWetnessField): Float {
        var total = 0f
        val active = wetness.activeTileCoordinates()
        forEachActivePixel(active, wetness.tileSize, wetness.width, wetness.height) { index, _, _ ->
            total += height[index]
        }
        return total
    }
}
