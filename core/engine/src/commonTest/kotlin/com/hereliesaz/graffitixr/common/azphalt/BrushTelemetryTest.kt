package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushTelemetryTest {
    @Test
    fun fingerContactUsesItsOwnTelemetryProfile() {
        val builder = BrushSampleBuilder()
        val sample = builder.add(
            x = 0f,
            y = 0f,
            uptimeMillis = 0L,
            pressure = 1f,
            touchMajorPx = 30f,
            touchMinorPx = 20f,
            tool = BrushInputTool.FINGER,
            pressureAvailable = true,
        )

        assertEquals(BrushTelemetryProfile.FINGER, sample.telemetry.profile)
        assertEquals(BrushSignalSource.TOUCH_CONTACT, sample.telemetry.pressureSource)
        assertEquals(0.5f, sample.pressure, 1e-6f)
        assertEquals(0.9f, sample.telemetry.contactSizeConfidence, 1e-6f)
        assertEquals(0f, sample.telemetry.tiltConfidence, 0f)
        assertEquals(0f, sample.telemetry.orientationConfidence, 0f)
    }

    @Test
    fun basicStylusStaysBasicWithoutExpressiveEvidence() {
        val builder = BrushSampleBuilder()
        val first = builder.add(
            0f, 0f, 0L,
            pressure = 0.50f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )
        val second = builder.add(
            10f, 0f, 10L,
            pressure = 0.53f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )

        assertEquals(BrushTelemetryProfile.STYLUS_BASIC, first.telemetry.profile)
        assertEquals(BrushTelemetryProfile.STYLUS_BASIC, second.telemetry.profile)
        assertTrue(second.telemetry.pressureConfidence < 0.9f)
    }

    @Test
    fun advertisedTiltPromotesStylusImmediately() {
        val sample = BrushSampleBuilder().add(
            0f, 0f, 0L,
            pressure = 0.4f,
            tiltRadians = 0.5f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
            tiltAvailable = true,
        )

        assertEquals(BrushTelemetryProfile.STYLUS_HIGH_QUALITY, sample.telemetry.profile)
        assertEquals(BrushSignalSource.STYLUS_SENSOR, sample.telemetry.tiltSource)
        assertTrue(sample.telemetry.tiltConfidence > 0.9f)
    }

    @Test
    fun pressureOnlyStylusCanPromoteFromObservedVariation() {
        val builder = BrushSampleBuilder()
        val first = builder.add(
            0f, 0f, 0L,
            pressure = 0.2f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )
        val second = builder.add(
            10f, 0f, 10L,
            pressure = 0.45f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )

        assertEquals(BrushTelemetryProfile.STYLUS_BASIC, first.telemetry.profile)
        assertEquals(BrushTelemetryProfile.STYLUS_HIGH_QUALITY, second.telemetry.profile)
    }

    @Test
    fun predictedTelemetryCannotPromoteAuthoritativeClassifier() {
        val builder = BrushSampleBuilder()
        builder.add(
            0f, 0f, 0L,
            pressure = 0.2f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )
        val prediction = builder.add(
            10f, 0f, 10L,
            pressure = 0.9f,
            predicted = true,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )
        val real = builder.add(
            2f, 0f, 10L,
            pressure = 0.22f,
            tool = BrushInputTool.STYLUS,
            pressureAvailable = true,
        )

        assertEquals(BrushTelemetryProfile.STYLUS_BASIC, prediction.telemetry.profile)
        assertEquals(BrushTelemetryProfile.STYLUS_BASIC, real.telemetry.profile)
    }

    @Test
    fun highConfidenceStylusEvidenceHasMoreMechanicalAuthority() {
        val config = BrushContactConfig(enabled = true, pressureCoupling = 1f, pressureSplay = 1f)
        val basic = BrushSample(
            x = 0f,
            y = 0f,
            pressure = 0.8f,
            telemetry = BrushTelemetryMetadata(
                profile = BrushTelemetryProfile.STYLUS_BASIC,
                pressureConfidence = 0.55f,
                pressureSource = BrushSignalSource.STYLUS_SENSOR,
                tiltConfidence = 0f,
                orientationConfidence = 0f,
            ),
        )
        val high = basic.copy(
            telemetry = basic.telemetry.copy(
                profile = BrushTelemetryProfile.STYLUS_HIGH_QUALITY,
                pressureConfidence = 0.98f,
            )
        )

        val basicStep = BrushContactModel.step(basic, BrushMechanicalState(), config)
        val highStep = BrushContactModel.step(high, BrushMechanicalState(), config)

        assertTrue(highStep.contact.compression > basicStep.contact.compression)
        assertTrue(highStep.contact.widthMultiplier > basicStep.contact.widthMultiplier)
    }

    @Test
    fun historicalSamplesDefaultToLegacyTrustContract() {
        val sample = BrushSample(0f, 0f, pressure = 0.7f)
        assertEquals(BrushTelemetryProfile.LEGACY, sample.telemetry.profile)
        assertEquals(1f, sample.telemetry.pressureConfidence, 0f)
    }
}
