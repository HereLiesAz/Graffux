package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushDeviceAttitudeTest {
    @Test
    fun historicalDefaultHasNoDeviceAuthority() {
        val telemetry = BrushTelemetryMetadata()
        assertEquals(BrushDeviceAttitudeSource.UNAVAILABLE, telemetry.deviceAttitude.source)
        assertEquals(0f, telemetry.deviceAttitude.tiltConfidence, 0f)
        assertEquals(0f, telemetry.deviceAttitude.yawConfidence, 0f)
        assertFalse(telemetry.deviceAttitude.isAvailable())
    }

    @Test
    fun attitudeInterpolationUsesShortestYawPath() {
        val almostPositivePi = BrushDeviceAttitude(
            yawRadians = Math.toRadians(179.0).toFloat(),
            yawConfidence = 1f,
            source = BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR,
        )
        val almostNegativePi = almostPositivePi.copy(
            yawRadians = Math.toRadians(-179.0).toFloat(),
        )

        val midpoint = almostPositivePi.blendTo(almostNegativePi, 0.5f)
        assertTrue(abs(abs(midpoint.yawRadians) - PI.toFloat()) < 0.03f)
    }

    @Test
    fun telemetryBlendCarriesDeviceAttitudeAndConfidence() {
        val first = BrushTelemetryMetadata(
            deviceAttitude = BrushDeviceAttitude(
                pitchRadians = 0.1f,
                rollRadians = -0.2f,
                yawRadians = 0.3f,
                tiltConfidence = 0.9f,
                yawConfidence = 0.6f,
                source = BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR,
            )
        )
        val second = first.copy(
            deviceAttitude = first.deviceAttitude.copy(
                pitchRadians = 0.5f,
                rollRadians = 0.2f,
                yawRadians = 0.7f,
                tiltConfidence = 0.7f,
                yawConfidence = 0.8f,
            )
        )

        val blended = first.blendTo(second, 0.5f).deviceAttitude
        assertEquals(0.3f, blended.pitchRadians, 1e-5f)
        assertEquals(0f, blended.rollRadians, 1e-5f)
        assertEquals(0.5f, blended.yawRadians, 1e-5f)
        assertEquals(0.8f, blended.tiltConfidence, 1e-5f)
        assertEquals(0.7f, blended.yawConfidence, 1e-5f)
    }

    @Test
    fun firstSampleBecomesNeutralAndManualPresentationStillApplies() {
        val attitude = BrushDeviceAttitude(
            pitchRadians = 0.3f,
            rollRadians = -0.2f,
            yawRadians = 1.1f,
            tiltConfidence = 1f,
            yawConfidence = 1f,
            source = BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR,
        )
        val config = BrushDevicePresentationConfig(
            enabled = true,
            manualLateralBias = 0.25f,
            manualLongitudinalBias = -0.3f,
            manualRotationDeg = 18f,
            rollCoupling = 1f,
            pitchCoupling = 1f,
            yawCoupling = 1f,
        )

        val resolved = BrushDevicePresentationModel.resolve(
            attitude,
            BrushDevicePresentationState(),
            config,
        )

        assertTrue(resolved.initialized)
        assertEquals(attitude.sanitized(), resolved.neutralAttitude)
        assertEquals(0.25f, resolved.lateralBias, 1e-6f)
        assertEquals(-0.3f, resolved.longitudinalBias, 1e-6f)
        assertEquals(18f, resolved.rotationDeg, 1e-6f)
    }

    @Test
    fun relativeRollPitchAndYawModifyIndependentPresentationAxes() {
        val neutral = BrushDeviceAttitude(
            pitchRadians = 0.1f,
            rollRadians = 0.2f,
            yawRadians = -0.4f,
            tiltConfidence = 1f,
            yawConfidence = 1f,
            source = BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR,
        )
        val config = BrushDevicePresentationConfig(
            enabled = true,
            rollCoupling = 1f,
            pitchCoupling = 1f,
            yawCoupling = 1f,
            fullPitchRadians = 0.5f,
            fullRollRadians = 0.5f,
            fullYawRadians = 0.5f,
            maxYawRotationDeg = 90f,
        )
        val first = BrushDevicePresentationModel.resolve(
            neutral,
            BrushDevicePresentationState(),
            config,
        )
        val moved = BrushDevicePresentationModel.resolve(
            neutral.copy(
                pitchRadians = 0.35f,
                rollRadians = -0.05f,
                yawRadians = -0.15f,
            ),
            first,
            config,
        )

        assertEquals(-0.5f, moved.lateralBias, 1e-5f)
        assertEquals(0.5f, moved.longitudinalBias, 1e-5f)
        assertEquals(45f, moved.rotationDeg, 1e-4f)
    }

    @Test
    fun lowConfidenceAttitudeHasLessMechanicalAuthority() {
        val neutral = BrushDeviceAttitude(
            source = BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR,
            tiltConfidence = 1f,
            yawConfidence = 1f,
        )
        val config = BrushDevicePresentationConfig(
            enabled = true,
            rollCoupling = 1f,
            pitchCoupling = 1f,
            yawCoupling = 1f,
            fullPitchRadians = 0.5f,
            fullRollRadians = 0.5f,
            fullYawRadians = 0.5f,
            maxYawRotationDeg = 100f,
        )
        val baseline = BrushDevicePresentationModel.resolve(
            neutral,
            BrushDevicePresentationState(),
            config,
        )
        val full = BrushDevicePresentationModel.resolve(
            neutral.copy(pitchRadians = 0.5f, rollRadians = 0.5f, yawRadians = 0.5f),
            baseline,
            config,
        )
        val weak = BrushDevicePresentationModel.resolve(
            neutral.copy(
                pitchRadians = 0.5f,
                rollRadians = 0.5f,
                yawRadians = 0.5f,
                tiltConfidence = 0.25f,
                yawConfidence = 0.25f,
            ),
            baseline,
            config,
        )

        assertTrue(abs(full.lateralBias) > abs(weak.lateralBias))
        assertTrue(abs(full.longitudinalBias) > abs(weak.longitudinalBias))
        assertTrue(abs(full.rotationDeg) > abs(weak.rotationDeg))
    }

    @Test
    fun disabledPresentationCannotChangeLegacyMechanics() {
        val sample = BrushSample(
            x = 0f,
            y = 0f,
            pressure = 0.7f,
            telemetry = BrushTelemetryMetadata(
                deviceAttitude = BrushDeviceAttitude(
                    pitchRadians = 0.8f,
                    rollRadians = -0.8f,
                    yawRadians = 1.2f,
                    tiltConfidence = 1f,
                    yawConfidence = 1f,
                    source = BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR,
                )
            ),
        )
        val step = BrushContactModel.step(
            sample,
            BrushMechanicalState(),
            BrushContactConfig(enabled = true),
        )

        assertFalse(step.state.presentation.initialized)
        assertEquals(0f, step.contact.presentationLateralBias, 0f)
        assertEquals(0f, step.contact.presentationLongitudinalBias, 0f)
        assertEquals(0f, step.contact.presentationRotationDeg, 0f)
    }
}
