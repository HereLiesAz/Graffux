package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val FINGER_CONTACT_FULL_PRESSURE_PX = 60f

/** Physical input family before any interpretation of artist intent. */
@Serializable
enum class BrushInputTool {
    @SerialName("unknown") UNKNOWN,
    @SerialName("stylus") STYLUS,
    @SerialName("finger") FINGER,
}

/** Canonical contact-phase evidence carried with replayable telemetry. */
@Serializable
enum class BrushContactPhase {
    @SerialName("contact") CONTACT,
    @SerialName("touchdown") TOUCHDOWN,
    @SerialName("liftOff") LIFT_OFF,
}

/**
 * Telemetry quality profile selected from the capabilities actually available during a stroke.
 *
 * A pressure-only pen remains STYLUS_BASIC even when its pressure signal is excellent; that signal
 * simply earns more pressure confidence. STYLUS_HIGH_QUALITY means the device exposes additional
 * expressive stylus axes such as tilt/orientation. This keeps the pipelines distinct without a
 * brittle device-name allowlist.
 */
@Serializable
enum class BrushTelemetryProfile {
    @SerialName("legacy") LEGACY,
    @SerialName("stylusBasic") STYLUS_BASIC,
    @SerialName("stylusHighQuality") STYLUS_HIGH_QUALITY,
    @SerialName("finger") FINGER,
}

@Serializable
enum class BrushSignalSource {
    @SerialName("legacy") LEGACY,
    @SerialName("stylusSensor") STYLUS_SENSOR,
    @SerialName("touchPressure") TOUCH_PRESSURE,
    @SerialName("touchContact") TOUCH_CONTACT,
    @SerialName("unavailable") UNAVAILABLE,
}

/**
 * Provenance/confidence that survives serialization with each [BrushSample]. Signal values remain
 * on BrushSample itself for source compatibility; this metadata says how much the mechanics should
 * trust them and where they came from.
 */
@Serializable
data class BrushTelemetryMetadata(
    val profile: BrushTelemetryProfile = BrushTelemetryProfile.LEGACY,
    val pressureConfidence: Float = 1f,
    val pressureSource: BrushSignalSource = BrushSignalSource.LEGACY,
    val tiltConfidence: Float = 1f,
    val tiltSource: BrushSignalSource = BrushSignalSource.LEGACY,
    val orientationConfidence: Float = 1f,
    val orientationSource: BrushSignalSource = BrushSignalSource.LEGACY,
    val contactSizeConfidence: Float = 0f,
    val contactSource: BrushSignalSource = BrushSignalSource.UNAVAILABLE,
    /** Discrete stroke-contact phase; CONTACT is the historical/replay-compatible default. */
    val contactPhase: BrushContactPhase = BrushContactPhase.CONTACT,
    /** Screen-remapped phone/tablet attitude captured independently from pointer/stylus telemetry. */
    val deviceAttitude: BrushDeviceAttitude = BrushDeviceAttitude(),
) {
    fun sanitized(): BrushTelemetryMetadata = copy(
        pressureConfidence = pressureConfidence.coerceIn(0f, 1f),
        tiltConfidence = tiltConfidence.coerceIn(0f, 1f),
        orientationConfidence = orientationConfidence.coerceIn(0f, 1f),
        contactSizeConfidence = contactSizeConfidence.coerceIn(0f, 1f),
        deviceAttitude = deviceAttitude.sanitized(),
    )

    /** Preserve confidence through arc-length interpolation without inventing a new tool class. */
    fun blendTo(other: BrushTelemetryMetadata, t: Float): BrushTelemetryMetadata {
        val clamped = t.coerceIn(0f, 1f)
        val discrete = if (clamped < 0.5f) this else other
        return BrushTelemetryMetadata(
            profile = discrete.profile,
            pressureConfidence = lerp(pressureConfidence, other.pressureConfidence, clamped),
            pressureSource = discrete.pressureSource,
            tiltConfidence = lerp(tiltConfidence, other.tiltConfidence, clamped),
            tiltSource = discrete.tiltSource,
            orientationConfidence = lerp(orientationConfidence, other.orientationConfidence, clamped),
            orientationSource = discrete.orientationSource,
            contactSizeConfidence = lerp(contactSizeConfidence, other.contactSizeConfidence, clamped),
            contactSource = discrete.contactSource,
            contactPhase = discrete.contactPhase,
            deviceAttitude = deviceAttitude.blendTo(other.deviceAttitude, clamped),
        ).sanitized()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/** Device-edge observation before filtering/intent interpretation. */
data class RawBrushTelemetry(
    val tool: BrushInputTool = BrushInputTool.UNKNOWN,
    val reportedPressure: Float = 1f,
    val pressureAvailable: Boolean = true,
    val tiltRadians: Float = 0f,
    val tiltAvailable: Boolean = false,
    val orientationRadians: Float = 0f,
    val orientationAvailable: Boolean = false,
    val touchMajorPx: Float = 0f,
    val touchMinorPx: Float = 0f,
)

data class BrushTelemetryInterpretation(
    val effectivePressure: Float,
    val metadata: BrushTelemetryMetadata,
)

/**
 * Stateful per-stroke classifier. It keeps stylus classes separate rather than flattening all
 * pointers into one generic stream, while still producing a common mechanics-facing contract.
 */
class BrushTelemetryInterpreter {
    private var stylusPressureMin = 1f
    private var stylusPressureMax = 0f
    private var stylusPressureSamples = 0
    private var highQualityStylusSeen = false

    fun interpret(raw: RawBrushTelemetry, commit: Boolean = true): BrushTelemetryInterpretation {
        val pressure = raw.reportedPressure.coerceIn(0f, 1f)

        if (raw.tool == BrushInputTool.STYLUS && commit) {
            if (raw.pressureAvailable) {
                stylusPressureMin = minOf(stylusPressureMin, pressure)
                stylusPressureMax = maxOf(stylusPressureMax, pressure)
                stylusPressureSamples++
            }
            if (raw.tiltAvailable || raw.orientationAvailable) {
                highQualityStylusSeen = true
            }
        }

        val profile = when (raw.tool) {
            BrushInputTool.FINGER -> BrushTelemetryProfile.FINGER
            BrushInputTool.STYLUS -> if (
                highQualityStylusSeen || raw.tiltAvailable || raw.orientationAvailable
            ) BrushTelemetryProfile.STYLUS_HIGH_QUALITY else BrushTelemetryProfile.STYLUS_BASIC
            BrushInputTool.UNKNOWN -> BrushTelemetryProfile.LEGACY
        }

        val useContactPressure = raw.touchMajorPx > 0f &&
            (raw.tool == BrushInputTool.FINGER || raw.tool == BrushInputTool.UNKNOWN) &&
            (!raw.pressureAvailable || pressure >= 0.99f)
        val contactPressure = (raw.touchMajorPx / FINGER_CONTACT_FULL_PRESSURE_PX).coerceIn(0f, 1f)
        val effectivePressure = if (useContactPressure) contactPressure else pressure

        return BrushTelemetryInterpretation(
            effectivePressure = effectivePressure,
            metadata = metadataFor(raw, profile, useContactPressure).sanitized(),
        )
    }

    fun reset() {
        stylusPressureMin = 1f
        stylusPressureMax = 0f
        stylusPressureSamples = 0
        highQualityStylusSeen = false
    }

    private fun observedStylusPressureSpan(): Float =
        if (stylusPressureSamples >= 2) (stylusPressureMax - stylusPressureMin).coerceAtLeast(0f) else 0f

    private fun metadataFor(
        raw: RawBrushTelemetry,
        profile: BrushTelemetryProfile,
        useContactPressure: Boolean,
    ): BrushTelemetryMetadata = when (profile) {
        BrushTelemetryProfile.LEGACY -> BrushTelemetryMetadata(
            profile = profile,
            contactSizeConfidence = if (raw.touchMajorPx > 0f) 0.5f else 0f,
            contactSource = if (raw.touchMajorPx > 0f) BrushSignalSource.TOUCH_CONTACT else BrushSignalSource.UNAVAILABLE,
        )

        BrushTelemetryProfile.STYLUS_HIGH_QUALITY -> BrushTelemetryMetadata(
            profile = profile,
            pressureConfidence = if (raw.pressureAvailable) 0.98f else 0f,
            pressureSource = if (raw.pressureAvailable) BrushSignalSource.STYLUS_SENSOR else BrushSignalSource.UNAVAILABLE,
            tiltConfidence = if (raw.tiltAvailable) 0.95f else 0f,
            tiltSource = if (raw.tiltAvailable) BrushSignalSource.STYLUS_SENSOR else BrushSignalSource.UNAVAILABLE,
            orientationConfidence = if (raw.orientationAvailable) 0.9f else 0f,
            orientationSource = if (raw.orientationAvailable) BrushSignalSource.STYLUS_SENSOR else BrushSignalSource.UNAVAILABLE,
            contactSizeConfidence = 0f,
            contactSource = BrushSignalSource.UNAVAILABLE,
        )

        BrushTelemetryProfile.STYLUS_BASIC -> {
            val pressureConfidence = when {
                !raw.pressureAvailable -> 0f
                observedStylusPressureSpan() >= STRONG_BASIC_PRESSURE_SPAN -> 0.9f
                observedStylusPressureSpan() >= BASIC_PRESSURE_USEFUL_SPAN -> 0.75f
                else -> 0.55f
            }
            BrushTelemetryMetadata(
                profile = profile,
                pressureConfidence = pressureConfidence,
                pressureSource = if (raw.pressureAvailable) BrushSignalSource.STYLUS_SENSOR else BrushSignalSource.UNAVAILABLE,
                tiltConfidence = 0f,
                tiltSource = BrushSignalSource.UNAVAILABLE,
                orientationConfidence = 0f,
                orientationSource = BrushSignalSource.UNAVAILABLE,
                contactSizeConfidence = 0f,
                contactSource = BrushSignalSource.UNAVAILABLE,
            )
        }

        BrushTelemetryProfile.FINGER -> BrushTelemetryMetadata(
            profile = profile,
            pressureConfidence = when {
                useContactPressure -> 0.85f
                raw.pressureAvailable -> 0.35f
                else -> 0f
            },
            pressureSource = when {
                useContactPressure -> BrushSignalSource.TOUCH_CONTACT
                raw.pressureAvailable -> BrushSignalSource.TOUCH_PRESSURE
                else -> BrushSignalSource.UNAVAILABLE
            },
            tiltConfidence = 0f,
            tiltSource = BrushSignalSource.UNAVAILABLE,
            // On touchscreen hardware AXIS_ORIENTATION describes the contact ellipse, not pen tilt.
            // Preserve it as touch-contact evidence for future finger-intent inference. The current
            // brush solver still gates rake steering through stylus tilt, so this does not masquerade
            // as stylus azimuth today.
            orientationConfidence = if (raw.orientationAvailable) 0.65f else 0f,
            orientationSource = if (raw.orientationAvailable) BrushSignalSource.TOUCH_CONTACT else BrushSignalSource.UNAVAILABLE,
            contactSizeConfidence = if (raw.touchMajorPx > 0f) 0.9f else 0f,
            contactSource = if (raw.touchMajorPx > 0f) BrushSignalSource.TOUCH_CONTACT else BrushSignalSource.UNAVAILABLE,
        )
    }

    companion object {
        private const val STRONG_BASIC_PRESSURE_SPAN = 0.08f
        private const val BASIC_PRESSURE_USEFUL_SPAN = 0.02f
    }
}

/** Shared mechanics-facing intent frame, independent of Android/Compose input classes. */
data class BrushIntentTelemetry(
    val profile: BrushTelemetryProfile,
    val pressure: Float,
    val pressureConfidence: Float,
    val tiltRadians: Float,
    val tiltConfidence: Float,
    val orientationRadians: Float,
    val orientationConfidence: Float,
    val contactMajorPx: Float,
    val contactMinorPx: Float,
    val contactConfidence: Float,
    val contactPhase: BrushContactPhase,
    val deviceAttitude: BrushDeviceAttitude = BrushDeviceAttitude(),
)

fun BrushSample.intentTelemetry(): BrushIntentTelemetry {
    val meta = telemetry.sanitized()
    return BrushIntentTelemetry(
        profile = meta.profile,
        pressure = pressure.coerceIn(0f, 1f),
        pressureConfidence = meta.pressureConfidence,
        tiltRadians = tiltRadians,
        tiltConfidence = meta.tiltConfidence,
        orientationRadians = orientationRadians,
        orientationConfidence = meta.orientationConfidence,
        contactMajorPx = touchMajorPx.coerceAtLeast(0f),
        contactMinorPx = touchMinorPx.coerceAtLeast(0f),
        contactConfidence = meta.contactSizeConfidence,
        contactPhase = meta.contactPhase,
        deviceAttitude = meta.deviceAttitude,
    )
}

internal fun pressureSmoothingAlpha(profile: BrushTelemetryProfile): Float = when (profile) {
    BrushTelemetryProfile.STYLUS_HIGH_QUALITY -> 0.65f
    BrushTelemetryProfile.STYLUS_BASIC -> 0.45f
    BrushTelemetryProfile.FINGER -> 0.30f
    BrushTelemetryProfile.LEGACY -> 0.45f
}

internal fun speedSmoothingAlpha(profile: BrushTelemetryProfile): Float = when (profile) {
    BrushTelemetryProfile.STYLUS_HIGH_QUALITY -> 0.45f
    BrushTelemetryProfile.STYLUS_BASIC -> 0.35f
    BrushTelemetryProfile.FINGER -> 0.30f
    BrushTelemetryProfile.LEGACY -> 0.35f
}
