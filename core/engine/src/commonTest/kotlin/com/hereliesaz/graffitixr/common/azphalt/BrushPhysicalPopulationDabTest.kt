package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushPhysicalPopulationDabTest {
    private fun physicalFlatBrush(populationEnabled: Boolean = true) = AzphaltBrush(
        name = "Physical flat",
        spacing = 0.38f,
        tipRatio = 0.7f,
        contact = BrushContactConfig(
            enabled = false,
            stiffness = 0.76f,
            drag = 0.9f,
            pressureCoupling = 0.7f,
            tufts = BrushTuftConfig(
                enabled = false,
                count = 3,
                morphology = BrushMorphology.FLAT,
            ),
            tipGeometry = BrushTipGeometryConfig(
                kind = BrushTipKind.BRISTLE,
                population = BrushBristlePopulationConfig(
                    enabled = populationEnabled,
                    bristleDiameterPx = 1.1f,
                    packingFraction = 0.72f,
                    mechanicalBundleDiameterPx = 4f,
                    minMechanicalTufts = 3,
                    maxMechanicalTufts = 96,
                    previewCellDiameterPx = 2f,
                ),
            ),
        ),
    )

    private fun sample(
        x: Float,
        y: Float,
        time: Long,
        distance: Float,
        speed: Float,
        angle: Float,
        phase: BrushContactPhase = BrushContactPhase.CONTACT,
    ) = BrushSample(
        x = x,
        y = y,
        uptimeMillis = time,
        pressure = 0.8f,
        distancePx = distance,
        speedPxPerMs = speed,
        drawingAngleDeg = angle,
        telemetry = BrushTelemetryMetadata(contactPhase = phase),
    )

    @Test
    fun largerBrushAddsBundlesWithoutEnlargingEachBundle() {
        val brush = physicalFlatBrush()
        val tap = listOf(sample(20f, 20f, 0L, 0f, 0f, 0f, BrushContactPhase.TOUCHDOWN))

        val small = BrushStamps.dynamicDabs(tap, 20f, brush, 17L)
        val large = BrushStamps.dynamicDabs(tap, 60f, brush, 17L)

        assertTrue(small.isNotEmpty())
        assertTrue(large.size > small.size * 4, "larger physical tip should add population by area")
        assertEquals(2f, small.first().radius, 1e-5f)
        assertTrue(small.all { abs(it.radius - 2f) < 1e-5f })
        assertTrue(large.all { abs(it.radius - 2f) < 1e-5f })
    }

    @Test
    fun physicalPopulationCreatesTrueTwoDimensionalRoots() {
        val resolved = physicalFlatBrush().contact.resolvedForBrushDiameter(48f, 0.7f)
        val roots = BrushTuftTopology.layout(resolved.tufts)

        assertTrue(roots.size > 3)
        assertTrue(roots.map { (it.rootLateralFraction * 1000f).toInt() }.distinct().size > 1)
        assertTrue(roots.map { (it.rootLongitudinalFraction * 1000f).toInt() }.distinct().size > 1)
    }

    @Test
    fun selectedBrushSizeFreezesPopulationForWholeStroke() {
        val brush = physicalFlatBrush()
        val resolved = brush.contact.resolvedForBrushDiameter(42f, brush.tipRatio)
        val firstCount = resolved.tufts.count
        val tinyDynamicDiameter = BrushTipTopology.resolvedTuftConfig(
            config = brush.contact.tufts,
            diameterPx = 42f,
            geometry = brush.contact.tipGeometry,
            legacyTipRatio = brush.tipRatio,
        ).count

        assertEquals(firstCount, tinyDynamicDiameter)
        assertTrue(firstCount > brush.contact.tufts.count)
    }

    @Test
    fun physicalPopulationItselfActivatesMechanicalRouting() {
        val physical = physicalFlatBrush(populationEnabled = true)
        val legacy = physicalFlatBrush(populationEnabled = false)

        assertTrue(physical.contact.isActive())
        assertTrue(physical.contact.requiresDynamicContact())
        assertFalse(legacy.contact.isActive())
        assertFalse(legacy.contact.requiresDynamicContact())
    }

    @Test
    fun physicalPopulationMatchesIncrementalAndCanonicalReplay() {
        val brush = physicalFlatBrush()
        val samples = listOf(
            sample(0f, 0f, 0L, 0f, 0f, 0f, BrushContactPhase.TOUCHDOWN),
            sample(14f, 0f, 16L, 14f, 0.875f, 0f),
            sample(28f, 8f, 32L, 30.1f, 1.0f, 29.7f),
            sample(38f, 12f, 48L, 40.9f, 0.67f, 21.8f),
            sample(38f, 12f, 64L, 40.9f, 0f, 21.8f, BrushContactPhase.LIFT_OFF),
        )
        val diameter = 36f
        val seed = 7331L
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
            assertEquals(canonical.tipRatio, live.tipRatio, 1e-4f, "tipRatio[$index]")
            assertEquals(canonical.angleDeg, live.angleDeg, 1e-4f, "angle[$index]")
            assertEquals(canonical.alpha, live.alpha, 1e-4f, "alpha[$index]")
        }
    }
}
