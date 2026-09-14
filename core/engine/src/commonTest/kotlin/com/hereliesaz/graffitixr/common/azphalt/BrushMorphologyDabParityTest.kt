package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushMorphologyDabParityTest {
    private val telemetry = BrushTelemetryMetadata(
        profile = BrushTelemetryProfile.STYLUS_HIGH_QUALITY,
        pressureConfidence = 0.98f,
        pressureSource = BrushSignalSource.STYLUS_SENSOR,
        tiltConfidence = 0.95f,
        tiltSource = BrushSignalSource.STYLUS_SENSOR,
        orientationConfidence = 0.9f,
        orientationSource = BrushSignalSource.STYLUS_SENSOR,
    )

    private fun phase(value: BrushContactPhase) = telemetry.copy(contactPhase = value)

    private val samples = listOf(
        BrushSample(
            x = 0f, y = 0f, uptimeMillis = 0L,
            pressure = 0.75f, distancePx = 0f, speedPxPerMs = 0f, drawingAngleDeg = 0f,
            telemetry = phase(BrushContactPhase.TOUCHDOWN),
        ),
        BrushSample(
            x = 18f, y = 0f, uptimeMillis = 18L,
            pressure = 0.85f, distancePx = 18f, speedPxPerMs = 1f, drawingAngleDeg = 0f,
            telemetry = phase(BrushContactPhase.CONTACT),
        ),
        BrushSample(
            x = 32f, y = 12f, uptimeMillis = 36L,
            pressure = 0.9f, distancePx = 36f, speedPxPerMs = 1f, drawingAngleDeg = 40f,
            telemetry = phase(BrushContactPhase.CONTACT),
        ),
        BrushSample(
            x = 12f, y = 10f, uptimeMillis = 54L,
            pressure = 0.8f, distancePx = 56f, speedPxPerMs = 1f, drawingAngleDeg = 186f,
            telemetry = phase(BrushContactPhase.CONTACT),
        ),
        BrushSample(
            x = 0f, y = 8f, uptimeMillis = 72L,
            pressure = 0f, distancePx = 68f, speedPxPerMs = 0.4f, drawingAngleDeg = 190f,
            telemetry = phase(BrushContactPhase.LIFT_OFF),
        ),
    )

    private fun brush(morphology: BrushMorphology) = AzphaltBrush(
        name = "Morphology ${morphology.name}",
        spacing = 0.42f,
        tipRatio = 0.78f,
        contact = BrushContactConfig(
            enabled = true,
            stiffness = 0.78f,
            drag = 1f,
            hysteresis = 0.3f,
            maxDragOffset = 0.18f,
            dragSplay = 0.42f,
            pressureCoupling = 0.75f,
            pressureSplay = 0.28f,
            tufts = BrushTuftConfig(
                enabled = true,
                count = 7,
                rootSpan = 0.82f,
                cohesion = 0.4f,
                splayResponse = 1.1f,
                bendDifferential = 0.2f,
                deformationResponse = 0.85f,
                hysteresis = 0.6f,
                recovery = 0.9f,
                splitThreshold = 0.32f,
                rejoinThreshold = 0.14f,
                splitResponse = 1f,
                splitSeparation = 0.2f,
                touchdownCompression = 0.28f,
                liftReleaseResponse = 1f,
                reversalThresholdDeg = 95f,
                reversalPersistence = 0.85f,
                reversalMaxLagDeg = 145f,
                emitTuftDabs = true,
                morphology = morphology,
            ),
        ),
    )

    @Test
    fun everyPhysicalMorphologyMatchesLiveAndCanonicalReplay() {
        BrushMorphology.entries.forEach { morphology ->
            val brush = brush(morphology)
            val expected = BrushStamps.dynamicDabs(samples, 10f, brush, 9917L)
            val generator = IncrementalDynamicDabGenerator(10f, brush, 9917L)
            val total = samples.last().distancePx
            val actual = samples.flatMap { generator.append(it, predictedTotal = total) }

            assertTrue(expected.isNotEmpty(), morphology.name)
            assertEquals(expected.size, actual.size, morphology.name)
            expected.zip(actual).forEachIndexed { index, (canonical, live) ->
                assertEquals(canonical.x, live.x, 1e-4f, "${morphology.name} x[$index]")
                assertEquals(canonical.y, live.y, 1e-4f, "${morphology.name} y[$index]")
                assertEquals(canonical.radius, live.radius, 1e-4f, "${morphology.name} radius[$index]")
                assertEquals(canonical.tipRatio, live.tipRatio, 1e-4f, "${morphology.name} tipRatio[$index]")
                assertEquals(canonical.angleDeg, live.angleDeg, 1e-4f, "${morphology.name} angle[$index]")
                assertEquals(canonical.alpha, live.alpha, 1e-4f, "${morphology.name} alpha[$index]")
            }
        }
    }

    @Test
    fun morphologyChangesResolvedStrokeGeometry() {
        fun signature(morphology: BrushMorphology): List<Pair<Int, Int>> =
            BrushStamps.dynamicDabs(samples, 10f, brush(morphology), 9917L)
                .take(28)
                .map { dab ->
                    (dab.x * 1000f).toInt() to (dab.y * 1000f).toInt()
                }

        val flat = signature(BrushMorphology.FLAT)
        val filbert = signature(BrushMorphology.FILBERT)
        val rigger = signature(BrushMorphology.RIGGER)
        val fan = signature(BrushMorphology.FAN)
        val rake = signature(BrushMorphology.RAKE)

        assertTrue(flat != filbert)
        assertTrue(flat != rigger)
        assertTrue(flat != fan)
        assertTrue(flat != rake)
    }
}
