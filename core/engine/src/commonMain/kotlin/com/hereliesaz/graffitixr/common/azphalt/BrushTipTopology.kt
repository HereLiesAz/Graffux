package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val TIP_DEG_TO_RAD = 0.017453292f

/** Physical contact family. LEGACY_STAMP preserves the historical ellipse/stamp behavior. */
@Serializable
enum class BrushTipKind {
    @SerialName("legacyStamp") LEGACY_STAMP,
    @SerialName("bristle") BRISTLE,
    @SerialName("chisel") CHISEL,
    @SerialName("calligraphy") CALLIGRAPHY,
    @SerialName("pencil") PENCIL,
}

/**
 * Scale model for real hairs versus coarse simulated bundles.
 *
 * [bristleDiameterPx] never scales with brush diameter. Larger brushes increase estimated hair
 * population instead. [mechanicalBundleDiameterPx] similarly keeps the coarse solver bounded: one
 * mechanical tuft represents a small cluster of constant-diameter hairs, and larger brush sizes
 * receive more tufts rather than larger hairs.
 */
@Serializable
data class BrushBristlePopulationConfig(
    val enabled: Boolean = false,
    val bristleDiameterPx: Float = 1.15f,
    val packingFraction: Float = 0.72f,
    val mechanicalBundleDiameterPx: Float = 5.5f,
    val minMechanicalTufts: Int = 3,
    val maxMechanicalTufts: Int = 48,
    /** Constant-size cell used by the hover topology visualization. */
    val previewCellDiameterPx: Float = 2.4f,
    val maxPreviewCells: Int = 384,
) {
    fun sanitized(): BrushBristlePopulationConfig = copy(
        bristleDiameterPx = bristleDiameterPx.coerceIn(0.25f, 8f),
        packingFraction = packingFraction.coerceIn(0.05f, 0.95f),
        mechanicalBundleDiameterPx = mechanicalBundleDiameterPx.coerceIn(1f, 64f),
        minMechanicalTufts = minMechanicalTufts.coerceIn(1, 48),
        maxMechanicalTufts = maxMechanicalTufts.coerceIn(minMechanicalTufts.coerceIn(1, 48), 96),
        previewCellDiameterPx = previewCellDiameterPx.coerceIn(0.5f, 12f),
        maxPreviewCells = maxPreviewCells.coerceIn(16, 1024),
    )
}

@Serializable
data class BrushTipGeometryConfig(
    val kind: BrushTipKind = BrushTipKind.LEGACY_STAMP,
    /** Height/width of a chisel marker face before 3D lean projection. */
    val chiselAspect: Float = 0.28f,
    /** Height/width of a broad calligraphy nib. */
    val calligraphyAspect: Float = 0.16f,
    /** How much additional side area a pencil exposes when laid over. */
    val pencilSideCoverage: Float = 1f,
    val population: BrushBristlePopulationConfig = BrushBristlePopulationConfig(),
) {
    fun sanitized(): BrushTipGeometryConfig = copy(
        chiselAspect = chiselAspect.coerceIn(0.05f, 1f),
        calligraphyAspect = calligraphyAspect.coerceIn(0.03f, 1f),
        pencilSideCoverage = pencilSideCoverage.coerceIn(0f, 2f),
        population = population.sanitized(),
    )
}

data class BrushTipCell(
    val xPx: Float,
    val yPx: Float,
    /** Constant cell radius for a given brush definition; it does not grow with overall brush size. */
    val radiusPx: Float,
    /** 0..1: higher means this cell is lower on the contact plane and reaches the page first. */
    val contactWeight: Float,
)

data class BrushTipPreviewGeometry(
    val hull: List<Pair<Float, Float>>,
    val cells: List<BrushTipCell>,
    val estimatedBristleCount: Int,
    val resolvedMechanicalTuftCount: Int,
    val widthPx: Float,
    val heightPx: Float,
    val leanX: Float,
    val leanY: Float,
    val twistDeg: Float,
)

/** Shared size-aware contact topology for painting mechanics and hover visualization. */
object BrushTipTopology {
    fun preview(
        diameterPx: Float,
        legacyTipRatio: Float,
        morphology: BrushMorphology,
        pose: BrushDevicePresentationState,
        geometry: BrushTipGeometryConfig,
    ): BrushTipPreviewGeometry {
        val cfg = geometry.sanitized()
        val diameter = diameterPx.coerceAtLeast(0.5f)
        val leanMagnitude = sqrt(pose.leanX * pose.leanX + pose.leanY * pose.leanY).coerceIn(0f, 1f)
        val footprint = footprint(diameter, legacyTipRatio, morphology, leanMagnitude, cfg)
        val hull = rotatedHull(footprint.first, footprint.second, pose.twistDeg, cfg.kind, morphology)
        val population = cfg.population
        val area = footprintArea(footprint.first, footprint.second, cfg.kind, morphology)
        val hairArea = circleArea(population.bristleDiameterPx)
        val bristleCount = if (cfg.kind == BrushTipKind.BRISTLE && population.enabled) {
            max(1, (area * population.packingFraction / hairArea.coerceAtLeast(1e-4f)).toInt())
        } else 0
        val tuftCount = resolvedMechanicalTuftCount(
            diameterPx = diameter,
            morphology = morphology,
            geometry = cfg,
            legacyTipRatio = legacyTipRatio,
        )
        val cells = if (cfg.kind == BrushTipKind.BRISTLE && population.enabled) {
            previewCells(
                width = footprint.first,
                height = footprint.second,
                twistDeg = pose.twistDeg,
                leanX = pose.leanX,
                leanY = pose.leanY,
                morphology = morphology,
                population = population,
            )
        } else emptyList()

        return BrushTipPreviewGeometry(
            hull = hull,
            cells = cells,
            estimatedBristleCount = bristleCount,
            resolvedMechanicalTuftCount = tuftCount,
            widthPx = footprint.first,
            heightPx = footprint.second,
            leanX = pose.leanX,
            leanY = pose.leanY,
            twistDeg = pose.twistDeg,
        )
    }

    /**
     * Resolves the coarse mechanical topology for one selected brush size.
     *
     * Population-enabled bristle brushes deliberately force the tuft path on: the physical model is
     * itself the opt-in. Count scales with projected brush-tip AREA, while each emitted mechanical
     * bundle retains [BrushBristlePopulationConfig.mechanicalBundleDiameterPx]. Thus doubling brush
     * diameter approaches four times as many represented hair groups instead of twice-as-large
     * hairs. Legacy/non-bristle brushes are returned byte-for-byte equivalent after sanitization.
     */
    fun resolvedTuftConfig(
        config: BrushTuftConfig,
        diameterPx: Float,
        geometry: BrushTipGeometryConfig,
        legacyTipRatio: Float = 1f,
    ): BrushTuftConfig {
        val base = config.sanitized()
        val tip = geometry.sanitized()
        if (!tip.population.enabled || tip.kind != BrushTipKind.BRISTLE) return base

        val diameter = diameterPx.coerceAtLeast(0.5f)
        val size = footprint(diameter, legacyTipRatio, base.morphology, 0f, tip)
        val count = resolvedMechanicalTuftCount(
            diameterPx = diameter,
            morphology = base.morphology,
            geometry = tip,
            legacyTipRatio = legacyTipRatio,
        )
        return base.copy(
            enabled = true,
            count = count,
            rootSpan = (size.first / diameter).coerceIn(0.05f, 1.5f),
            physicalHeightSpan = (size.second / diameter).coerceIn(0.05f, 1.5f),
            physicalBundleDiameterPx = tip.population.mechanicalBundleDiameterPx,
            emitTuftDabs = true,
        ).sanitized()
    }

    fun resolvedContactConfig(
        contact: BrushContactConfig,
        diameterPx: Float,
        legacyTipRatio: Float,
    ): BrushContactConfig {
        val cfg = contact.sanitized()
        return cfg.copy(
            tufts = resolvedTuftConfig(
                config = cfg.tufts,
                diameterPx = diameterPx,
                geometry = cfg.tipGeometry,
                legacyTipRatio = legacyTipRatio,
            ),
        )
    }

    fun resolvedMechanicalTuftCount(
        diameterPx: Float,
        morphology: BrushMorphology,
        geometry: BrushTipGeometryConfig,
        legacyTipRatio: Float = 1f,
    ): Int {
        val cfg = geometry.sanitized()
        val population = cfg.population
        if (!population.enabled || cfg.kind != BrushTipKind.BRISTLE) return 0

        val diameter = diameterPx.coerceAtLeast(0.5f)
        val size = footprint(diameter, legacyTipRatio, morphology, 0f, cfg)
        val area = footprintArea(size.first, size.second, cfg.kind, morphology)
        val bundleArea = circleArea(population.mechanicalBundleDiameterPx).coerceAtLeast(1e-4f)
        val raw = ceil(area * population.packingFraction / bundleArea).toInt()
        return raw.coerceIn(population.minMechanicalTufts, population.maxMechanicalTufts)
    }

    private fun circleArea(diameter: Float): Float {
        val radius = diameter * 0.5f
        return PI.toFloat() * radius * radius
    }

    private fun footprint(
        diameter: Float,
        legacyTipRatio: Float,
        morphology: BrushMorphology,
        lean: Float,
        cfg: BrushTipGeometryConfig,
    ): Pair<Float, Float> = when (cfg.kind) {
        BrushTipKind.LEGACY_STAMP -> diameter to (diameter * legacyTipRatio.coerceIn(0.05f, 1f))
        BrushTipKind.CHISEL -> diameter to (diameter * cfg.chiselAspect)
        BrushTipKind.CALLIGRAPHY -> diameter to (diameter * cfg.calligraphyAspect)
        BrushTipKind.PENCIL -> {
            // Upright = compact point. Laying the shaft down recruits the side of the graphite core.
            val length = diameter * (0.18f + lean * 0.82f * cfg.pencilSideCoverage).coerceIn(0.12f, 1.6f)
            val width = diameter * (0.12f + lean * 0.28f).coerceIn(0.08f, 0.5f)
            length to width
        }
        BrushTipKind.BRISTLE -> when (morphology) {
            BrushMorphology.ROUND -> diameter * 0.88f to diameter * 0.72f
            BrushMorphology.FLAT -> diameter to diameter * 0.34f
            BrushMorphology.FILBERT -> diameter * 0.96f to diameter * 0.58f
            BrushMorphology.RIGGER -> diameter * 0.32f to diameter * 0.88f
            BrushMorphology.FAN -> diameter * 1.12f to diameter * 0.42f
            BrushMorphology.RAKE -> diameter to diameter * 0.3f
            BrushMorphology.CUSTOM -> diameter to (diameter * legacyTipRatio.coerceIn(0.05f, 1f))
        }
    }

    private fun footprintArea(
        width: Float,
        height: Float,
        kind: BrushTipKind,
        morphology: BrushMorphology,
    ): Float = when {
        kind == BrushTipKind.CHISEL || kind == BrushTipKind.CALLIGRAPHY -> width * height
        kind == BrushTipKind.BRISTLE && morphology == BrushMorphology.RAKE -> width * height * 0.58f
        kind == BrushTipKind.BRISTLE && morphology == BrushMorphology.FAN -> width * height * 0.72f
        else -> PI.toFloat() * width * height * 0.25f
    }.coerceAtLeast(1e-4f)

    private fun previewCells(
        width: Float,
        height: Float,
        twistDeg: Float,
        leanX: Float,
        leanY: Float,
        morphology: BrushMorphology,
        population: BrushBristlePopulationConfig,
    ): List<BrushTipCell> {
        val step = population.previewCellDiameterPx
        val radius = step * 0.5f
        val halfW = width * 0.5f
        val halfH = height * 0.5f
        val candidates = ArrayList<BrushTipCell>()
        var y = -halfH
        var row = 0
        while (y <= halfH + 1e-4f && candidates.size < population.maxPreviewCells * 2) {
            val stagger = if (row % 2 == 0) 0f else step * 0.5f
            var x = -halfW + stagger
            while (x <= halfW + 1e-4f && candidates.size < population.maxPreviewCells * 2) {
                if (insideFootprint(x, y, halfW, halfH, morphology)) {
                    val normalizedX = if (halfW > 1e-4f) x / halfW else 0f
                    val normalizedY = if (halfH > 1e-4f) y / halfH else 0f
                    val plane = (normalizedX * leanX + normalizedY * leanY).coerceIn(-1f, 1f)
                    val contactWeight = (0.5f + plane * 0.5f).coerceIn(0f, 1f)
                    val rotated = rotate(x, y, twistDeg)
                    candidates += BrushTipCell(
                        xPx = rotated.first,
                        yPx = rotated.second,
                        radiusPx = radius,
                        contactWeight = contactWeight,
                    )
                }
                x += step
            }
            y += step * 0.8660254f
            row++
        }
        if (candidates.size <= population.maxPreviewCells) return candidates
        val stride = ceil(candidates.size.toFloat() / population.maxPreviewCells.toFloat())
            .toInt()
            .coerceAtLeast(1)
        return candidates.filterIndexed { index, _ -> index % stride == 0 }
            .take(population.maxPreviewCells)
    }

    private fun insideFootprint(
        x: Float,
        y: Float,
        halfW: Float,
        halfH: Float,
        morphology: BrushMorphology,
    ): Boolean {
        val nx = if (halfW > 1e-4f) x / halfW else 0f
        val ny = if (halfH > 1e-4f) y / halfH else 0f
        return when (morphology) {
            BrushMorphology.FLAT -> abs(nx) <= 1f && abs(ny) <= 1f
            BrushMorphology.RAKE -> {
                val tooth = ((nx + 1f) * 4.5f).toInt()
                abs(nx) <= 1f && abs(ny) <= 1f && tooth % 2 == 0
            }
            BrushMorphology.FAN -> abs(nx) <= 1f && abs(ny) <= (0.35f + (1f - abs(nx)) * 0.65f)
            BrushMorphology.RIGGER -> nx * nx + ny * ny <= 1f
            else -> nx * nx + ny * ny <= 1f
        }
    }

    private fun rotatedHull(
        width: Float,
        height: Float,
        twistDeg: Float,
        kind: BrushTipKind,
        morphology: BrushMorphology,
    ): List<Pair<Float, Float>> {
        val halfW = width * 0.5f
        val halfH = height * 0.5f
        val local = when {
            kind == BrushTipKind.CHISEL || kind == BrushTipKind.CALLIGRAPHY ||
                (kind == BrushTipKind.BRISTLE && morphology == BrushMorphology.FLAT) -> listOf(
                -halfW to -halfH,
                halfW to -halfH,
                halfW to halfH,
                -halfW to halfH,
            )
            kind == BrushTipKind.BRISTLE && morphology == BrushMorphology.FAN -> listOf(
                -halfW to halfH,
                -halfW * 0.82f to -halfH * 0.65f,
                0f to -halfH,
                halfW * 0.82f to -halfH * 0.65f,
                halfW to halfH,
            )
            else -> List(32) { index ->
                val angle = index.toFloat() / 32f * PI.toFloat() * 2f
                cos(angle) * halfW to sin(angle) * halfH
            }
        }
        return local.map { rotate(it.first, it.second, twistDeg) }
    }

    private fun rotate(x: Float, y: Float, deg: Float): Pair<Float, Float> {
        val rad = deg * TIP_DEG_TO_RAD
        val c = cos(rad)
        val s = sin(rad)
        return (x * c - y * s) to (x * s + y * c)
    }
}
