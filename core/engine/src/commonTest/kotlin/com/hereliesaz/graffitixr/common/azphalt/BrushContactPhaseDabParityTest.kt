package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushContactPhaseDabParityTest {
    private val baseTelemetry = BrushTelemetryMetadata(
        profile = BrushTelemetryProfile.STYLUS_HIGH_QUALITY,
        pressureConfidence = 0.98f,
        pressureSource = BrushSignalSource.STYLUS_SENSOR,
        tiltConfidence = 0.95f,
        tiltSource = BrushSignalSource.STYLUS_SENSOR,
        orientationConfidence = 0.9f,
        orientationSource = BrushSignalSource.STYLUS_SENSOR,
    )

    private val brush = AzphaltBrush(
        name = "Phase Tufts",
        spacing = 0.45f,
        tipRatio = 0.8f,
        contact = BrushContactConfig(
            enabled = true,
            stiffness = 0.85f,
            drag = 1f,
            hysteresis = 0.25f,
            maxDragOffset = 0.18f,
            dragSplay = 0.4f,
            pressureCoupling = 0.8f,
            pressureSplay = 0.25f,
            tufts = BrushTuftConfig(
                enabled = true,
                count = 5,
                rootSpan = 0.82f,
                cohesion = 0.15f,
                splayResponse = 1.3f,
                bendDifferential = 0.22f,
                deformationResponse = 0.85f,
                hysteresis = 0.65f,
                recovery = 0.9f,
                splitThreshold = 0.28f,
                rejoinThreshold = 0.12f,
                splitResponse = 1f,
                splitSeparation = 0.2f,
                touchdownCompression = 0.3f,
                liftReleaseResponse = 1f,
                reversalThresholdDeg = 90f,
                reversalPersistence = 0.9f,
                reversalMaxLagDeg = 150f,
                emitTuftDabs = true,
            ),
        ),
    )

    private fun telemetry(phase: BrushContactPhase) = baseTelemetry.copy(contactPhase = phase)

    private val samples = listOf(
        BrushSample(
            x = 0f, y = 0f, uptimeMillis = 0L,
            pressure = 0.9f, distancePx = 0f, speedPxPerMs = 0f, drawingAngleDeg = 0f,
            telemetry = telemetry(BrushContactPhase.TOUCHDOWN),
        ),
        BrushSample(
            x = 20f, y = 0f, uptimeMillis = 20L,
            pressure = 0.9f, distancePx = 20f, speedPxPerMs = 1f, drawingAngleDeg = 0f,
            telemetry = telemetry(BrushContactPhase.CONTACT),
        ),
        BrushSample(
            x = 40f, y = 0f, uptimeMillis = 40L,
            pressure = 0.9f, distancePx = 40f, speedPxPerMs = 1f, drawingAngleDeg = 0f,
            telemetry = telemetry(BrushContactPhase.CONTACT),
        ),
        BrushSample(
            x = 20f, y = 0f, uptimeMillis = 60L,
            pressure = 0.8f, distancePx = 60f, speedPxPerMs = 1f, drawingAngleDeg = 180f,
            telemetry = telemetry(BrushContactPhase.CONTACT),
        ),
        BrushSample(
            x = 0f, y = 0f, uptimeMillis = 80L,
            pressure = 0f, distancePx = 80f, speedPxPerMs = 0.5f, drawingAngleDeg = 180f,
            telemetry = telemetry(BrushContactPhase.LIFT_OFF),
        ),
    )

    @Test
    fun contactPhaseSurvivesTelemetryInterpolation() {
        val blended = telemetry(BrushContactPhase.CONTACT)
            .blendTo(telemetry(BrushContactPhase.LIFT_OFF), 0.75f)
        assertEquals(BrushContactPhase.LIFT_OFF, blended.contactPhase)
    }

    @Test
    fun touchdownReversalAndLiftStayIdenticalLiveAndCanonical() {
        val expected = BrushStamps.dynamicDabs(samples, 10f, brush, 4242L)
        val generator = IncrementalDynamicDabGenerator(10f, brush, 4242L)
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
