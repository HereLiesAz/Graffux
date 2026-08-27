package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

private const val RAD_TO_DEG = 57.29578f

/**
 * Canonical, renderer-independent input sample for the brush engine.
 *
 * This is deliberately shaped after Krita's paint-information/sensor split rather than Android's
 * MotionEvent API: device input is normalized once at the edge of the editor, then every brush
 * parameter consumes the same immutable sample. That keeps live painting, commit/replay, imported
 * brushes and future native/GPU paths deterministic and prevents pressure/tilt/speed logic from
 * leaking into individual renderers.
 *
 * [distancePx], [speedPxPerMs] and [drawingAngleDeg] are derived values. Use [BrushSampleBuilder]
 * when consuming a live point stream so they are computed consistently.
 */
@Serializable
data class BrushSample(
    val x: Float,
    val y: Float,
    val uptimeMillis: Long = 0L,
    val pressure: Float = 1f,
    /** Android stylus tilt in radians: 0 = perpendicular, PI/2 = flat. */
    val tiltRadians: Float = 0f,
    /** Android stylus orientation/azimuth in radians, normally -PI..PI. */
    val orientationRadians: Float = 0f,
    /** Cumulative travelled distance from the start of this stroke. */
    val distancePx: Float = 0f,
    /** Instantaneous segment speed in pixels per millisecond. */
    val speedPxPerMs: Float = 0f,
    /** Heading of the most recent non-zero segment in degrees. */
    val drawingAngleDeg: Float = 0f,
    /** Presentation-only predicted samples must never be committed into authoritative history. */
    val predicted: Boolean = false,
)

/** Incrementally derives distance, speed and drawing angle for a raw input stream. */
class BrushSampleBuilder {
    private var previous: BrushSample? = null

    fun add(
        x: Float,
        y: Float,
        uptimeMillis: Long,
        pressure: Float = 1f,
        tiltRadians: Float = 0f,
        orientationRadians: Float = 0f,
        predicted: Boolean = false,
    ): BrushSample {
        val prev = previous
        val dx = if (prev == null) 0f else x - prev.x
        val dy = if (prev == null) 0f else y - prev.y
        val segment = if (prev == null) 0f else hypot(dx, dy)
        val dt = if (prev == null) 0L else (uptimeMillis - prev.uptimeMillis).coerceAtLeast(1L)
        val angle = if (segment > 0f) atan2(dy, dx) * RAD_TO_DEG else prev?.drawingAngleDeg ?: 0f
        val sample = BrushSample(
            x = x,
            y = y,
            uptimeMillis = uptimeMillis,
            pressure = pressure.coerceIn(0f, 1f),
            tiltRadians = tiltRadians.coerceIn(0f, (PI / 2.0).toFloat()),
            orientationRadians = orientationRadians.coerceIn((-PI).toFloat(), PI.toFloat()),
            distancePx = (prev?.distancePx ?: 0f) + segment,
            speedPxPerMs = if (prev == null) 0f else segment / dt.toFloat(),
            drawingAngleDeg = angle,
            predicted = predicted,
        )
        // A predicted sample is disposable presentation state. Do not let it become the basis for
        // the next real sample's speed/distance; the next genuine point must derive from genuine
        // history or a bad prediction would contaminate the authoritative stroke.
        if (!predicted) previous = sample
        return sample
    }

    fun reset() {
        previous = null
    }
}

/** Input signals exposed to a brush. Mirrors the useful subset of Krita's sensor vocabulary. */
@Serializable
enum class BrushSensor {
    @SerialName("pressure") PRESSURE,
    @SerialName("speed") SPEED,
    @SerialName("tilt") TILT,
    @SerialName("orientation") ORIENTATION,
    @SerialName("distance") DISTANCE,
    @SerialName("time") TIME,
    @SerialName("drawingAngle") DRAWING_ANGLE,
    @SerialName("randomDab") RANDOM_DAB,
    @SerialName("randomStroke") RANDOM_STROKE,
}

/** Brush parameters a sensor may drive. Additive targets are documented below. */
@Serializable
enum class BrushParameter {
    @SerialName("size") SIZE,
    @SerialName("opacity") OPACITY,
    @SerialName("flow") FLOW,
    @SerialName("spacing") SPACING,
    @SerialName("scatter") SCATTER,
    @SerialName("rotation") ROTATION,
    @SerialName("hue") HUE,
    @SerialName("saturation") SATURATION,
    @SerialName("value") VALUE,
    @SerialName("smudgeRate") SMUDGE_RATE,
    @SerialName("colorRate") COLOR_RATE,
    @SerialName("smudgeRadius") SMUDGE_RADIUS,
}

@Serializable
data class BrushCurvePoint(val x: Float, val y: Float)

/**
 * Piecewise-linear response curve. Brush Studio can later render this as a tiny editable graph;
 * keeping it as data rather than a hard-coded pressure formula is the important part.
 */
@Serializable
data class BrushResponseCurve(
    val points: List<BrushCurvePoint> = listOf(BrushCurvePoint(0f, 0f), BrushCurvePoint(1f, 1f)),
) {
    fun sanitized(): BrushResponseCurve {
        val cleaned = points
            .asSequence()
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { BrushCurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }
            .toMutableList()
        if (cleaned.isEmpty()) return LINEAR
        if (cleaned.first().x > 0f) cleaned.add(0, BrushCurvePoint(0f, cleaned.first().y))
        if (cleaned.last().x < 1f) cleaned.add(BrushCurvePoint(1f, cleaned.last().y))
        return BrushResponseCurve(cleaned)
    }

    fun evaluate(input: Float): Float {
        val p = sanitized().points
        val x = input.coerceIn(0f, 1f)
        if (x <= p.first().x) return p.first().y
        if (x >= p.last().x) return p.last().y
        for (i in 0 until p.lastIndex) {
            val a = p[i]
            val b = p[i + 1]
            if (x <= b.x) {
                val width = b.x - a.x
                if (width <= 1e-6f) return b.y
                val t = (x - a.x) / width
                return a.y + (b.y - a.y) * t
            }
        }
        return p.last().y
    }

    companion object {
        val LINEAR = BrushResponseCurve()
    }
}

/**
 * One sensor-to-parameter route.
 *
 * The raw sensor value is normalized from [inputMin]..[inputMax], transformed by [curve], then
 * remapped to [outputMin]..[outputMax]. Multiplicative parameters (size/opacity/flow/spacing/
 * scatter/saturation/value) multiply all bindings targeting them. Rotation and hue are additive.
 */
@Serializable
data class BrushSensorBinding(
    val sensor: BrushSensor,
    val parameter: BrushParameter,
    val inputMin: Float = 0f,
    val inputMax: Float = 1f,
    val outputMin: Float = 0f,
    val outputMax: Float = 1f,
    val curve: BrushResponseCurve = BrushResponseCurve(),
    val invert: Boolean = false,
) {
    fun sanitized(): BrushSensorBinding {
        val lo = if (inputMin.isFinite()) inputMin else 0f
        val hiRaw = if (inputMax.isFinite()) inputMax else 1f
        val hi = if (hiRaw == lo) lo + 1f else hiRaw
        return copy(
            inputMin = lo,
            inputMax = hi,
            outputMin = if (outputMin.isFinite()) outputMin else 0f,
            outputMax = if (outputMax.isFinite()) outputMax else 1f,
            curve = curve.sanitized(),
        )
    }

    fun map(raw: Float): Float {
        val b = sanitized()
        var t = ((raw - b.inputMin) / (b.inputMax - b.inputMin)).coerceIn(0f, 1f)
        if (b.invert) t = 1f - t
        val curved = b.curve.evaluate(t)
        return b.outputMin + (b.outputMax - b.outputMin) * curved
    }
}

/** Resolved per-dab modifiers. Defaults are identities, so brushes without bindings are unchanged. */
data class ResolvedBrushDynamics(
    val sizeMultiplier: Float = 1f,
    val opacityMultiplier: Float = 1f,
    val flowMultiplier: Float = 1f,
    val spacingMultiplier: Float = 1f,
    val scatterMultiplier: Float = 1f,
    val rotationOffsetDeg: Float = 0f,
    val hueShiftDeg: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val valueMultiplier: Float = 1f,
    val smudgeRateMultiplier: Float = 1f,
    val colorRateMultiplier: Float = 1f,
    val smudgeRadiusMultiplier: Float = 1f,
)

/** Pure deterministic sensor resolver; no Android classes and no renderer dependencies. */
object BrushSensorEngine {
    fun resolve(
        sample: BrushSample,
        bindings: List<BrushSensorBinding>,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        dabIndex: Int,
    ): ResolvedBrushDynamics {
        if (bindings.isEmpty()) return ResolvedBrushDynamics()

        var size = 1f
        var opacity = 1f
        var flow = 1f
        var spacing = 1f
        var scatter = 1f
        var rotation = 0f
        var hue = 0f
        var saturation = 1f
        var value = 1f
        var smudgeRate = 1f
        var colorRate = 1f
        var smudgeRadius = 1f

        for (binding in bindings) {
            val raw = sensorValue(sample, binding.sensor, strokeStartUptimeMillis, strokeSeed, dabIndex)
            val mapped = binding.map(raw)
            when (binding.parameter) {
                BrushParameter.SIZE -> size *= mapped
                BrushParameter.OPACITY -> opacity *= mapped
                BrushParameter.FLOW -> flow *= mapped
                BrushParameter.SPACING -> spacing *= mapped
                BrushParameter.SCATTER -> scatter *= mapped
                BrushParameter.ROTATION -> rotation += mapped
                BrushParameter.HUE -> hue += mapped
                BrushParameter.SATURATION -> saturation *= mapped
                BrushParameter.VALUE -> value *= mapped
                BrushParameter.SMUDGE_RATE -> smudgeRate *= mapped
                BrushParameter.COLOR_RATE -> colorRate *= mapped
                BrushParameter.SMUDGE_RADIUS -> smudgeRadius *= mapped
            }
        }

        return ResolvedBrushDynamics(
            sizeMultiplier = size.coerceAtLeast(0f),
            opacityMultiplier = opacity.coerceAtLeast(0f),
            flowMultiplier = flow.coerceAtLeast(0f),
            spacingMultiplier = spacing.coerceAtLeast(0.01f),
            scatterMultiplier = scatter.coerceAtLeast(0f),
            rotationOffsetDeg = rotation,
            hueShiftDeg = hue,
            saturationMultiplier = saturation.coerceAtLeast(0f),
            valueMultiplier = value.coerceAtLeast(0f),
            smudgeRateMultiplier = smudgeRate.coerceAtLeast(0f),
            colorRateMultiplier = colorRate.coerceAtLeast(0f),
            smudgeRadiusMultiplier = smudgeRadius.coerceAtLeast(0.01f),
        )
    }

    private fun sensorValue(
        sample: BrushSample,
        sensor: BrushSensor,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        dabIndex: Int,
    ): Float = when (sensor) {
        BrushSensor.PRESSURE -> sample.pressure
        BrushSensor.SPEED -> sample.speedPxPerMs
        BrushSensor.TILT -> sample.tiltRadians
        BrushSensor.ORIENTATION -> sample.orientationRadians
        BrushSensor.DISTANCE -> sample.distancePx
        BrushSensor.TIME -> (sample.uptimeMillis - strokeStartUptimeMillis).coerceAtLeast(0L).toFloat()
        BrushSensor.DRAWING_ANGLE -> sample.drawingAngleDeg
        BrushSensor.RANDOM_DAB -> Random(mixSeed(strokeSeed, dabIndex.toLong())).nextFloat()
        BrushSensor.RANDOM_STROKE -> Random(mixSeed(strokeSeed, 0x51A7E5L)).nextFloat()
    }

    private fun mixSeed(seed: Long, salt: Long): Long {
        var z = seed xor (salt + -7046029254386353131L)
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}
