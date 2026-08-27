package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorEngine
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CPU correctness/reference implementation for Graffux's Color Smudge paint-op.
 *
 * Smear compatibility is deliberately pinned: with no [Settings.dynamics], this uses the same
 * resampling, carrier, falloff and channel rounding as the historical Graffux Smudge implementation.
 * Dulling and Color Rate build on that reference instead of replacing it with a blur approximation.
 *
 * Sensor routing follows the same Krita-shaped [BrushSensorEngine] used by stamp brushes. Physical
 * telemetry is supplied separately from bitmap-space positions: callers remap only x/y, while
 * pressure/speed/tilt/time/distance keep the values recorded under the hand. That makes replay
 * deterministic and prevents zoom or layer scale from changing how a sensor curve feels.
 */
object ColorSmudgeEngine {

    enum class Mode { SMEAR, DULLING }

    data class Settings(
        val mode: Mode = Mode.SMEAR,
        /** Base amount of carried/sampled colour, 0..1. */
        val smudgeRate: Float = 0.65f,
        /** Independent foreground-paint deposition, 0 = pure smudge. */
        val colorRate: Float = 0f,
        /** Overall dab coverage multiplier. */
        val opacity: Float = 1f,
        /** Radius of the brush footprint in bitmap pixels; supplied by DrawingEngine at replay. */
        val radiusPx: Float = 1f,
        /** Dulling sample radius relative to [radiusPx]. */
        val smudgeRadius: Float = 1f,
        val feathering: Float = 0f,
        val wrapAround: Boolean = false,
        /** Whether smudging transports alpha along with RGB. */
        val smearAlpha: Boolean = true,
        /** Foreground colour used only when [colorRate] > 0. */
        val paintColor: Int = Color.BLACK,
        val symmetryMode: SymmetryMode = SymmetryMode.NONE,
        /** Optional Krita-style sensor routes. */
        val dynamics: List<BrushSensorBinding> = emptyList(),
    )

    private data class DabPoint(val position: Offset, val sample: BrushSample?)

    private data class Resolved(
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
    )

    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
        samples: List<BrushSample> = emptyList(),
        strokeSeed: Long = 0L,
    ) {
        if (stroke.isEmpty() || width <= 0 || height <= 0 || pixels.size < width * height) return
        applyOne(pixels, width, height, stroke, settings, samples, strokeSeed)
        for (transform in symmetryTransforms(settings.symmetryMode, width.toFloat(), height.toFloat())) {
            // Only x/y are mirrored. Sensor values describe the real hand movement and must not be
            // transformed just because a synthetic symmetry twin is being rendered.
            val transformedSamples = if (samples.size == stroke.size) {
                samples.map { sample ->
                    val p = transform(Offset(sample.x, sample.y))
                    sample.copy(x = p.x, y = p.y, predicted = false)
                }
            } else emptyList()
            applyOne(
                pixels,
                width,
                height,
                stroke.map(transform),
                settings.copy(symmetryMode = SymmetryMode.NONE),
                transformedSamples,
                strokeSeed,
            )
        }
    }

    private fun applyOne(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
        samples: List<BrushSample>,
        strokeSeed: Long,
    ) {
        val radius = settings.radiusPx.coerceAtLeast(1f)
        val path = resampleWithTelemetry(stroke, samples, (radius / 2f).coerceAtLeast(1f))
        if (path.isEmpty()) return
        val kernel = BrushKernel(radius, settings.feathering)
        val startTime = samples.firstOrNull()?.uptimeMillis ?: 0L
        when (settings.mode) {
            Mode.SMEAR -> smear(pixels, width, height, path, kernel, settings, startTime, strokeSeed)
            Mode.DULLING -> dull(pixels, width, height, path, kernel, settings, startTime, strokeSeed)
        }
    }

    private fun resolve(
        settings: Settings,
        sample: BrushSample?,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        dabIndex: Int,
    ): Resolved {
        if (sample == null || settings.dynamics.isEmpty()) {
            return Resolved(
                settings.smudgeRate.coerceIn(0f, 1f),
                settings.colorRate.coerceIn(0f, 1f),
                settings.opacity.coerceIn(0f, 1f),
                settings.smudgeRadius.coerceAtLeast(0.05f),
            )
        }
        val dynamic = BrushSensorEngine.resolve(
            sample,
            settings.dynamics,
            strokeStartUptimeMillis,
            strokeSeed,
            dabIndex,
        )
        return Resolved(
            (settings.smudgeRate * dynamic.smudgeRateMultiplier).coerceIn(0f, 1f),
            (settings.colorRate * dynamic.colorRateMultiplier).coerceIn(0f, 1f),
            (settings.opacity * dynamic.opacityMultiplier).coerceIn(0f, 1f),
            (settings.smudgeRadius * dynamic.smudgeRadiusMultiplier).coerceAtLeast(0.05f),
        )
    }

    /** Spatial carrier. This remains Graffux's original directional smudge when dynamics are empty. */
    private fun smear(
        pixels: IntArray,
        width: Int,
        height: Int,
        path: List<DabPoint>,
        kernel: BrushKernel,
        settings: Settings,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
    ) {
        val carrier = IntArray(kernel.size)
        val start = path.first().position

        // Edge extension prevents a stroke started at a layer border from seeding the carrier with
        // transparent black and dragging a translucent band into otherwise opaque artwork.
        forEachKernel(kernel) { dx, dy, k, _ ->
            val sx = (start.x.toInt() + dx).coerceIn(0, width - 1)
            val sy = (start.y.toInt() + dy).coerceIn(0, height - 1)
            carrier[k] = pixels[sy * width + sx]
        }

        for (i in 1 until path.size) {
            val dab = path[i]
            val resolved = resolve(settings, dab.sample, strokeStartUptimeMillis, strokeSeed, i)
            val cx = dab.position.x.toInt()
            val cy = dab.position.y.toInt()
            forEachKernel(kernel) { dx, dy, k, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel

                val under = pixels[idx]
                carrier[k] = lerpArgb(
                    under, carrier[k], resolved.smudgeRate, includeAlpha = settings.smearAlpha,
                )
                var out = lerpArgb(
                    under,
                    carrier[k],
                    mask * resolved.opacity,
                    includeAlpha = settings.smearAlpha,
                )
                if (resolved.colorRate > 0f) {
                    out = lerpArgb(
                        out,
                        settings.paintColor,
                        mask * resolved.opacity * resolved.colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    /** Local colour mixing followed by brush-footprint deposition. */
    private fun dull(
        pixels: IntArray,
        width: Int,
        height: Int,
        path: List<DabPoint>,
        kernel: BrushKernel,
        settings: Settings,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
    ) {
        for (i in 1 until path.size) {
            val dab = path[i]
            val resolved = resolve(settings, dab.sample, strokeStartUptimeMillis, strokeSeed, i)
            val cx = dab.position.x.toInt()
            val cy = dab.position.y.toInt()
            val sampleRadius = (settings.radiusPx * resolved.smudgeRadius).roundToInt().coerceAtLeast(1)
            val sampled = weightedAverage(
                pixels, width, height, cx, cy, sampleRadius, settings.wrapAround,
            )

            forEachKernel(kernel) { dx, dy, _, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel
                val under = pixels[idx]
                var out = lerpArgb(
                    under,
                    sampled,
                    mask * resolved.opacity * resolved.smudgeRate,
                    includeAlpha = settings.smearAlpha,
                )
                if (resolved.colorRate > 0f) {
                    out = lerpArgb(
                        out,
                        settings.paintColor,
                        mask * resolved.opacity * resolved.colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    private class BrushKernel(radius: Float, feathering: Float) {
        val r: Int = radius.toInt().coerceAtLeast(1)
        val diameter: Int = r * 2 + 1
        val mask: FloatArray = FloatArray(diameter * diameter)
        val size: Int get() = mask.size

        init {
            // Keep the historical falloff byte-for-byte in shape: solid core + smoothstep rim.
            val soft = 0.25f + feathering.coerceIn(0f, 1f) * 0.7f
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val t = hypot(dx.toFloat(), dy.toFloat()) / r
                    mask[(dy + r) * diameter + (dx + r)] = when {
                        t >= 1f -> 0f
                        t <= 1f - soft -> 1f
                        else -> {
                            val u = (1f - t) / soft
                            u * u * (3f - 2f * u)
                        }
                    }
                }
            }
        }
    }

    private inline fun forEachKernel(
        kernel: BrushKernel,
        block: (dx: Int, dy: Int, index: Int, mask: Float) -> Unit,
    ) {
        for (dy in -kernel.r..kernel.r) {
            for (dx in -kernel.r..kernel.r) {
                val k = (dy + kernel.r) * kernel.diameter + (dx + kernel.r)
                block(dx, dy, k, kernel.mask[k])
            }
        }
    }

    private fun indexOf(x: Int, y: Int, width: Int, height: Int, wrapAround: Boolean): Int {
        val ix: Int
        val iy: Int
        if (wrapAround) {
            ix = ((x % width) + width) % width
            iy = ((y % height) + height) % height
        } else {
            if (x !in 0 until width || y !in 0 until height) return -1
            ix = x
            iy = y
        }
        return iy * width + ix
    }

    private fun weightedAverage(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
        wrapAround: Boolean,
    ): Int {
        var sumA = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumW = 0.0
        val rr = radius.toFloat()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val distance = hypot(dx.toFloat(), dy.toFloat())
                if (distance > rr) continue
                val idx = indexOf(cx + dx, cy + dy, width, height, wrapAround)
                if (idx < 0) continue
                val weight = (1f - distance / rr).coerceIn(0f, 1f)
                if (weight <= 0f) continue
                val p = pixels[idx]
                sumA += (p ushr 24 and 0xFF) * weight
                sumR += (p shr 16 and 0xFF) * weight
                sumG += (p shr 8 and 0xFF) * weight
                sumB += (p and 0xFF) * weight
                sumW += weight
            }
        }
        if (sumW <= 0.0) {
            val idx = indexOf(cx, cy, width, height, wrapAround)
            return if (idx >= 0) pixels[idx] else Color.TRANSPARENT
        }
        return Color.argb(
            (sumA / sumW).roundToInt().coerceIn(0, 255),
            (sumR / sumW).roundToInt().coerceIn(0, 255),
            (sumG / sumW).roundToInt().coerceIn(0, 255),
            (sumB / sumW).roundToInt().coerceIn(0, 255),
        )
    }

    /** `a` moved towards `b` by `t`, rounded per channel so repeated pickup cannot bias dark. */
    private fun lerpArgb(a: Int, b: Int, t: Float, includeAlpha: Boolean): Int {
        val f = t.coerceIn(0f, 1f)
        val aa = a ushr 24 and 0xFF
        val ba = b ushr 24 and 0xFF
        val ia = if (includeAlpha) aa + (ba - aa) * f else aa.toFloat()
        val ir = (a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * f
        val ig = (a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * f
        val ib = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * f
        return (ia.roundToInt().coerceIn(0, 255) shl 24) or
            (ir.roundToInt().coerceIn(0, 255) shl 16) or
            (ig.roundToInt().coerceIn(0, 255) shl 8) or
            ib.roundToInt().coerceIn(0, 255)
    }

    /**
     * Same positions as the historical resample() loop. When telemetry is aligned 1:1 with the
     * original stroke, interpolate its physical values at those exact generated positions.
     */
    private fun resampleWithTelemetry(
        stroke: List<Offset>,
        samples: List<BrushSample>,
        step: Float,
    ): List<DabPoint> {
        if (stroke.isEmpty()) return emptyList()
        val aligned = samples.size == stroke.size
        if (stroke.size < 2) return listOf(DabPoint(stroke.first(), samples.firstOrNull()))
        val out = ArrayList<DabPoint>(stroke.size * 2)
        out.add(DabPoint(stroke.first(), if (aligned) samples.first().copy(predicted = false) else null))
        for (i in 1 until stroke.size) {
            val a = stroke[i - 1]
            val b = stroke[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            val n = ceil(len / step).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toFloat() / n
                val p = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                val sample = if (aligned) interpolateSample(samples[i - 1], samples[i], p, t) else null
                out.add(DabPoint(p, sample))
            }
        }
        return out
    }

    private fun interpolateSample(a: BrushSample, b: BrushSample, p: Offset, t: Float): BrushSample =
        BrushSample(
            x = p.x,
            y = p.y,
            uptimeMillis = (a.uptimeMillis + (b.uptimeMillis - a.uptimeMillis) * t).toLong(),
            pressure = lerp(a.pressure, b.pressure, t),
            tiltRadians = lerp(a.tiltRadians, b.tiltRadians, t),
            orientationRadians = lerp(a.orientationRadians, b.orientationRadians, t),
            distancePx = lerp(a.distancePx, b.distancePx, t),
            speedPxPerMs = lerp(a.speedPxPerMs, b.speedPxPerMs, t),
            drawingAngleDeg = lerp(a.drawingAngleDeg, b.drawingAngleDeg, t),
            predicted = false,
        )

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun symmetryTransforms(mode: SymmetryMode, w: Float, h: Float): List<(Offset) -> Offset> {
        val cx = w / 2f
        val cy = h / 2f
        return when (mode) {
            SymmetryMode.NONE -> emptyList()
            SymmetryMode.VERTICAL -> listOf({ p: Offset -> Offset(w - p.x, p.y) })
            SymmetryMode.HORIZONTAL -> listOf({ p: Offset -> Offset(p.x, h - p.y) })
            SymmetryMode.QUADRANT -> listOf(
                { p: Offset -> Offset(w - p.x, p.y) },
                { p: Offset -> Offset(p.x, h - p.y) },
                { p: Offset -> Offset(w - p.x, h - p.y) },
            )
            SymmetryMode.RADIAL_6 -> (1..5).map { k ->
                val rad = Math.toRadians(60.0 * k)
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val transform: (Offset) -> Offset = { p: Offset ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    Offset(cx + dx * c - dy * s, cy + dx * s + dy * c)
                }
                transform
            }
        }
    }
}
