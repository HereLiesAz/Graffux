package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushTipTopologyTest {
    private val bristleTip = BrushTipGeometryConfig(
        kind = BrushTipKind.BRISTLE,
        population = BrushBristlePopulationConfig(
            enabled = true,
            bristleDiameterPx = 1f,
            mechanicalBundleDiameterPx = 5f,
            minMechanicalTufts = 3,
            maxMechanicalTufts = 48,
            previewCellDiameterPx = 2f,
            maxPreviewCells = 512,
        ),
    )

    @Test
    fun largerBrushAddsPopulationWithoutEnlargingIndividualCells() {
        val small = BrushTipTopology.preview(
            diameterPx = 20f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.ROUND,
            pose = BrushDevicePresentationState(),
            geometry = bristleTip,
        )
        val large = BrushTipTopology.preview(
            diameterPx = 60f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.ROUND,
            pose = BrushDevicePresentationState(),
            geometry = bristleTip,
        )

        assertTrue(large.estimatedBristleCount > small.estimatedBristleCount * 5)
        assertTrue(large.resolvedMechanicalTuftCount > small.resolvedMechanicalTuftCount)
        assertEquals(small.cells.first().radiusPx, large.cells.first().radiusPx, 0f)
    }

    @Test
    fun sizeAwareTuftResolutionKeepsLegacyFixedUnlessExplicitlyEnabled() {
        val fixed = BrushTuftConfig(enabled = true, count = 7, morphology = BrushMorphology.FLAT)
        val legacy = BrushTipTopology.resolvedTuftConfig(
            fixed,
            diameterPx = 80f,
            geometry = BrushTipGeometryConfig(),
        )
        val physical = BrushTipTopology.resolvedTuftConfig(
            fixed,
            diameterPx = 80f,
            geometry = bristleTip,
        )

        assertEquals(7, legacy.count)
        assertTrue(physical.count > 7)
    }

    @Test
    fun rollDrivenTwistTurnsAngularTipWithoutChangingCanvasOrStrokeHeading() {
        val pose = BrushDevicePresentationState(
            initialized = true,
            leanX = 0f,
            leanY = 0f,
            twistDeg = 90f,
        )
        val chisel = BrushTipTopology.preview(
            diameterPx = 40f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.CUSTOM,
            pose = pose,
            geometry = BrushTipGeometryConfig(kind = BrushTipKind.CHISEL, chiselAspect = 0.25f),
        )

        val xs = chisel.hull.map { it.first }
        val ys = chisel.hull.map { it.second }
        val widthAfterTwist = (xs.maxOrNull()!! - xs.minOrNull()!!)
        val heightAfterTwist = (ys.maxOrNull()!! - ys.minOrNull()!!)
        assertTrue(heightAfterTwist > widthAfterTwist * 3f)
    }

    @Test
    fun pitchAndYawSelectDifferentFirstContactRegions() {
        val yawLean = BrushTipTopology.preview(
            diameterPx = 36f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.FLAT,
            pose = BrushDevicePresentationState(initialized = true, leanX = 1f),
            geometry = bristleTip,
        )
        val pitchLean = BrushTipTopology.preview(
            diameterPx = 36f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.FLAT,
            pose = BrushDevicePresentationState(initialized = true, leanY = 1f),
            geometry = bristleTip,
        )

        val yawWeightedX = yawLean.cells.sumOf { (it.xPx * it.contactWeight).toDouble() }.toFloat()
        val yawWeightedY = yawLean.cells.sumOf { (it.yPx * it.contactWeight).toDouble() }.toFloat()
        val pitchWeightedX = pitchLean.cells.sumOf { (it.xPx * it.contactWeight).toDouble() }.toFloat()
        val pitchWeightedY = pitchLean.cells.sumOf { (it.yPx * it.contactWeight).toDouble() }.toFloat()

        assertTrue(abs(yawWeightedX) > abs(yawWeightedY))
        assertTrue(abs(pitchWeightedY) > abs(pitchWeightedX))
    }

    @Test
    fun layingPencilOverExpandsProjectedCoverage() {
        val upright = BrushTipTopology.preview(
            diameterPx = 40f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.CUSTOM,
            pose = BrushDevicePresentationState(initialized = true),
            geometry = BrushTipGeometryConfig(kind = BrushTipKind.PENCIL),
        )
        val laidOver = BrushTipTopology.preview(
            diameterPx = 40f,
            legacyTipRatio = 1f,
            morphology = BrushMorphology.CUSTOM,
            pose = BrushDevicePresentationState(initialized = true, leanX = 0.9f, leanY = 0.4f),
            geometry = BrushTipGeometryConfig(kind = BrushTipKind.PENCIL),
        )

        assertTrue(laidOver.widthPx > upright.widthPx * 3f)
        assertTrue(laidOver.heightPx > upright.heightPx)
    }
}
