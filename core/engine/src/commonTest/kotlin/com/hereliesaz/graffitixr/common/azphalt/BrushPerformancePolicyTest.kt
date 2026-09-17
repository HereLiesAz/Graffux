package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrushPerformancePolicyTest {
    private fun physicalBrush(minTufts: Int = 3, maxTufts: Int = 96) = AzphaltBrush(
        name = "Performance physical flat",
        spacing = 0.38f,
        tipRatio = 0.7f,
        contact = BrushContactConfig(
            tufts = BrushTuftConfig(morphology = BrushMorphology.FLAT),
            tipGeometry = BrushTipGeometryConfig(
                kind = BrushTipKind.BRISTLE,
                population = BrushBristlePopulationConfig(
                    enabled = true,
                    mechanicalBundleDiameterPx = 4f,
                    minMechanicalTufts = minTufts,
                    maxMechanicalTufts = maxTufts,
                    maxPreviewCells = 384,
                ),
            ),
        ),
    )

    private fun sample(x: Float, time: Long, distance: Float) = BrushSample(
        x = x,
        y = 0f,
        uptimeMillis = time,
        pressure = 0.8f,
        distancePx = distance,
        speedPxPerMs = if (time == 0L) 0f else 1f,
        drawingAngleDeg = 0f,
    )

    @Test
    fun nonPhysicalBrushIsReturnedUnchanged() {
        val brush = AzphaltBrush(name = "Legacy")
        assertSame(brush, brush.cappedForPerformanceTier(BrushPerformanceTier.CONSTRAINED))
    }

    @Test
    fun tiersDeterministicallyCapPhysicalPopulationWithoutChangingPreset() {
        val original = physicalBrush()
        val constrained = original.cappedForPerformanceTier(BrushPerformanceTier.CONSTRAINED)
        val balanced = original.cappedForPerformanceTier(BrushPerformanceTier.BALANCED)
        val full = original.cappedForPerformanceTier(BrushPerformanceTier.FULL)

        assertEquals(96, original.contact.tipGeometry.population.maxMechanicalTufts)
        assertEquals(16, constrained.contact.tipGeometry.population.maxMechanicalTufts)
        assertEquals(32, balanced.contact.tipGeometry.population.maxMechanicalTufts)
        assertEquals(96, full.contact.tipGeometry.population.maxMechanicalTufts)
        assertEquals(96, constrained.contact.tipGeometry.population.maxPreviewCells)
        assertEquals(192, balanced.contact.tipGeometry.population.maxPreviewCells)
        assertEquals(384, full.contact.tipGeometry.population.maxPreviewCells)
        assertEquals(
            constrained,
            original.cappedForPerformanceTier(BrushPerformanceTier.CONSTRAINED),
            "same brush+tier must resolve to the same stroke snapshot",
        )
    }

    @Test
    fun configuredMinimumIsNeverViolated() {
        val brush = physicalBrush(minTufts = 24, maxTufts = 96)
            .cappedForPerformanceTier(BrushPerformanceTier.CONSTRAINED)
        assertEquals(24, brush.contact.tipGeometry.population.maxMechanicalTufts)
    }

    @Test
    fun maxDiameterOnConstrainedTierStaysUnderFixedTuftCeiling() {
        val brush = physicalBrush().cappedForPerformanceTier(BrushPerformanceTier.CONSTRAINED)
        val resolved = brush.contact.resolvedForBrushDiameter(4096f, brush.tipRatio)
        assertEquals(16, resolved.tufts.count)
        assertTrue(BrushTuftTopology.layout(resolved.tufts).size <= 16)
    }

    @Test
    fun cappedBrushPreservesIncrementalAndCanonicalReplayParity() {
        val brush = physicalBrush().cappedForPerformanceTier(BrushPerformanceTier.CONSTRAINED)
        val samples = listOf(
            sample(0f, 0L, 0f),
            sample(12f, 16L, 12f),
            sample(24f, 32L, 24f),
        )
        val diameter = 320f
        val seed = 93017L
        val expected = BrushStamps.dynamicDabs(samples, diameter, brush, seed)
        val generator = IncrementalDynamicDabGenerator(diameter, brush, seed)
        val total = samples.last().distancePx
        val actual = samples.flatMap { generator.append(it, predictedTotal = total) }

        assertTrue(expected.isNotEmpty())
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (canonical, live) ->
            assertEquals(canonical.x, live.x, 1e-4f, "x[$index]")
            assertEquals(canonical.y, live.y, 1e-4f, "y[$index]")
            assertEquals(canonical.radius, live.radius, 1e-4f, "radius[$index]")
            assertEquals(canonical.alpha, live.alpha, 1e-4f, "alpha[$index]")
            assertEquals(canonical.contactDepth, live.contactDepth, 1e-4f, "depth[$index]")
        }
    }
}
