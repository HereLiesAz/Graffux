package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorEngine
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CPU correctness/reference implementation for Graffux's wet-paint paint-op.
 *
 * This is one engine, not two. Krita's Color Smudge (Smear/Dulling/Color Rate) and Procreate's
 * Wet Mix (Charge/Dilution/Pull/Attack) describe the same underlying operation — sample nearby
 * colour, carry or deposit it, blend into the canvas — from two different products' vocabularies.
 * Rather than building a second "Wet Mix engine" alongside this one, the Procreate-only concepts
 * are modelled as generalizations of the fields already here, defaulting to the historical Krita-
 * compatible behaviour:
 *
 * - [Settings.smudgeRate] *is* Procreate's Pull: how much sampled colour gets carried/displaced.
 * - [Settings.colorRate] *is* Procreate's Charge at the start of the stroke (Charge0); with
 *   [Settings.chargeDecayRate] at its default of 0 it behaves exactly like Krita's flat Color
 *   Rate. A positive decay rate makes it Procreate's actual Charge: the effective deposition rate
 *   decays exponentially with distance travelled, so a brush "runs out of paint" and settles into
 *   a pure Pull/smudge tool once depleted — Procreate's documented dry-brush end state falls out
 *   of this by construction, not as a separate case.
 * - [Settings.dilution] is new: how much the deposited pigment itself is pre-mixed with the
 *   colour already under the brush (Procreate's Dilution) before that pigment is blended into the
 *   canvas at [Settings.colorRate]/[Settings.chargeDecayRate]'s rate. Default 0 deposits pure
 *   [Settings.paintColor], matching historical Color Rate exactly.
 *
 * Attack, Grade, Blur, and Wetness Jitter — Procreate's remaining Wet Mix sliders — don't need new
 * fields: they already map onto [Settings.opacity]/[Settings.smudgeRate] (Attack), [Mode.DULLING]'s
 * sample radius and [Settings.dynamics] (Grade/Jitter via sensor routes), and [Settings.feathering]
 * (Blur).
 *
 * Smear compatibility is deliberately pinned: with no [Settings.dynamics] and the Procreate fields
 * at their defaults, this uses the same resampling, carrier, falloff and channel rounding as the
 * historical Graffux Smudge implementation. Dulling, Color Rate, and the Wet Mix generalizations
 * build on that reference instead of replacing it with a blur approximation.
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
        /** Base amount of carried/sampled colour, 0..1. Procreate calls this "Pull". */
        val smudgeRate: Float = 0.65f,
        /**
         * Independent foreground-paint deposition, 0 = pure smudge. Procreate's Charge at stroke
         * start ("Charge0") when [chargeDecayRate] > 0; a flat, non-decaying rate (Krita's Color
         * Rate) at the default [chargeDecayRate] of 0.
         */
        val colorRate: Float = 0f,
        /**
         * Procreate's Charge decay: when > 0, the effective [colorRate] decays exponentially with
         * distance travelled along the stroke (`charge(t) = colorRate * exp(-chargeDecayRate * t)`,
         * `t` in bitmap pixels), so a brush deposits progressively less new pigment and settles
         * into a pure [smudgeRate]-driven smudge once depleted — Procreate's "dry brush" end state.
         * 0 (the default) is a flat rate with no decay, i.e. historical Krita Color Rate.
         */
        val chargeDecayRate: Float = 0f,
        /**
         * Procreate's Dilution: how much the pigment about to be deposited (at [colorRate]) is
         * itself pre-mixed with the colour already under the brush before blending into the
         * canvas, 0..1. 0 (the default) deposits pure [paintColor], matching historical Color
         * Rate exactly; 1 deposits the sampled/carried colour unchanged (no new pigment reaches
         * the canvas, even though [colorRate]/[chargeDecayRate] are still "spent").
         */
        val dilution: Float = 0f,
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
        /**
         * Krita's Sample Merged: when true and a `sampleSource` is supplied to [apply], Smear's
         * pickup and Dulling's weighted average read from that separate composite buffer instead
         * of the active layer's own pixels — painting always still writes to the active layer.
         * Recorded on the stroke like every other setting, so replay is deterministic regardless
         * of whether a caller currently supplies a source (see [apply]'s `sampleSource` doc).
         */
        val sampleMerged: Boolean = false,
    )

    private data class DabPoint(val position: Offset, val sample: BrushSample?)

    /** Renderer-neutral, per-dab Color Smudge instruction. */
    data class ResolvedDab(
        val x: Float,
        val y: Float,
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
    )

    /** One ordered carrier lifetime. Symmetry twins are separate plans so Smear reseeds each one. */
    data class ResolvedPlan(val dabs: List<ResolvedDab>)

    private data class Resolved(
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
    )


    /**
     * Resolves the exact resampling and sensor curves the CPU implementation uses into renderer-
     * neutral dabs. Vulkan consumes this plan rather than reimplementing input/sensor semantics.
     */
    fun resolvePlans(
        stroke: List<Offset>,
        width: Int,
        height: Int,
        settings: Settings,
        samples: List<BrushSample> = emptyList(),
        strokeSeed: Long = 0L,
    ): List<ResolvedPlan> {
        if (stroke.isEmpty() || width <= 0 || height <= 0) return emptyList()
        fun one(points: List<Offset>, telemetry: List<BrushSample>): ResolvedPlan {
            val radius = settings.radiusPx.coerceAtLeast(1f)
            val step = (radius / 2f).coerceAtLeast(1f)
            val path = resampleWithTelemetry(points, telemetry, step)
            val startTime = telemetry.firstOrNull()?.uptimeMillis ?: 0L
            return ResolvedPlan(path.mapIndexed { index, dab ->
                val r = resolve(settings, dab.sample, startTime, strokeSeed, index, index * step)
                ResolvedDab(
                    x = dab.position.x,
                    y = dab.position.y,
                    smudgeRate = r.smudgeRate,
                    colorRate = r.colorRate,
                    opacity = r.opacity,
                    smudgeRadius = r.smudgeRadius,
                )
            })
        }

        val plans = ArrayList<ResolvedPlan>()
        plans += one(stroke, samples)
        for (transform in symmetryTransforms(settings.symmetryMode, width.toFloat(), height.toFloat())) {
            val transformedSamples = if (samples.size == stroke.size) {
                samples.map { sample ->
                    val pos = transform(Offset(sample.x, sample.y))
                    sample.copy(x = pos.x, y = pos.y, predicted = false)
                }
            } else emptyList()
            plans += one(stroke.map(transform), transformedSamples)
        }
        return plans
    }

    /**
     * @param sampleSource Optional pre-composited "what the artist can see" buffer, same
     *   dimensions as [pixels], used for colour pickup instead of the active layer's own pixels
     *   when [Settings.sampleMerged] is set. Ignored (falls back to sampling [pixels] itself,
     *   identical to `sampleMerged = false`) when null or mismatched in size — a caller with no
     *   merged composite available degrades to the historical single-layer behaviour rather than
     *   crashing or silently misbehaving. Painting always writes to [pixels] regardless.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
        samples: List<BrushSample> = emptyList(),
        strokeSeed: Long = 0L,
        sampleSource: IntArray? = null,
    ) {
        if (stroke.isEmpty() || width <= 0 || height <= 0 || pixels.size < width * height) return
        applyOne(pixels, width, height, stroke, settings, samples, strokeSeed, sampleSource)
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
                sampleSource,
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
        sampleSource: IntArray?,
    ) {
        val radius = settings.radiusPx.coerceAtLeast(1f)
        val step = (radius / 2f).coerceAtLeast(1f)
        val path = resampleWithTelemetry(stroke, samples, step)
        if (path.isEmpty()) return
        val kernel = BrushKernel(radius, settings.feathering)
        val startTime = samples.firstOrNull()?.uptimeMillis ?: 0L
        val readSource = if (settings.sampleMerged && sampleSource != null && sampleSource.size == pixels.size) {
            sampleSource
        } else {
            pixels
        }
        when (settings.mode) {
            Mode.SMEAR -> smear(pixels, readSource, width, height, path, kernel, settings, startTime, strokeSeed, step)
            Mode.DULLING -> dull(pixels, readSource, width, height, path, kernel, settings, startTime, strokeSeed, step)
        }
    }

    /** @param distancePx cumulative arc length travelled so far, in bitmap pixels — Procreate's `t`. */
    private fun resolve(
        settings: Settings,
        sample: BrushSample?,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        dabIndex: Int,
        distancePx: Float,
    ): Resolved {
        val charge = if (settings.chargeDecayRate > 0f) {
            settings.colorRate * exp(-settings.chargeDecayRate * distancePx)
        } else {
            settings.colorRate
        }
        if (sample == null || settings.dynamics.isEmpty()) {
            return Resolved(
                settings.smudgeRate.coerceIn(0f, 1f),
                charge.coerceIn(0f, 1f),
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
            (charge * dynamic.colorRateMultiplier).coerceIn(0f, 1f),
            (settings.opacity * dynamic.opacityMultiplier).coerceIn(0f, 1f),
            (settings.smudgeRadius * dynamic.smudgeRadiusMultiplier).coerceAtLeast(0.05f),
        )
    }

    /** Spatial carrier. This remains Graffux's original directional smudge when dynamics are empty. */
    private fun smear(
        pixels: IntArray,
        readSource: IntArray,
        width: Int,
        height: Int,
        path: List<DabPoint>,
        kernel: BrushKernel,
        settings: Settings,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        step: Float,
    ) {
        val carrier = IntArray(kernel.size)
        val start = path.first().position

        // Edge extension prevents a stroke started at a layer border from seeding the carrier with
        // transparent black and dragging a translucent band into otherwise opaque artwork.
        forEachKernel(kernel) { dx, dy, k, _ ->
            val sx = (start.x.toInt() + dx).coerceIn(0, width - 1)
            val sy = (start.y.toInt() + dy).coerceIn(0, height - 1)
            carrier[k] = readSource[sy * width + sx]
        }

        for (i in 1 until path.size) {
            val dab = path[i]
            val resolved = resolve(settings, dab.sample, strokeStartUptimeMillis, strokeSeed, i, i * step)
            val cx = dab.position.x.toInt()
            val cy = dab.position.y.toInt()
            forEachKernel(kernel) { dx, dy, k, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel

                // Pickup reads from readSource (the merged composite when Sample Merged is active);
                // the paint blend below always reads/writes the active layer's own pixels — Sample
                // Merged changes what colour gets carried, never which layer receives the stroke.
                val pickedUp = readSource[idx]
                val under = pixels[idx]
                carrier[k] = lerpArgb(
                    pickedUp, carrier[k], resolved.smudgeRate, includeAlpha = settings.smearAlpha,
                )
                var out = lerpArgb(
                    under,
                    carrier[k],
                    mask * resolved.opacity,
                    includeAlpha = settings.smearAlpha,
                )
                if (resolved.colorRate > 0f) {
                    val pigment = dilutedPigment(settings, pickedUp)
                    out = lerpArgb(
                        out,
                        pigment,
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
        readSource: IntArray,
        width: Int,
        height: Int,
        path: List<DabPoint>,
        kernel: BrushKernel,
        settings: Settings,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        step: Float,
    ) {
        for (i in 1 until path.size) {
            val dab = path[i]
            val resolved = resolve(settings, dab.sample, strokeStartUptimeMillis, strokeSeed, i, i * step)
            val cx = dab.position.x.toInt()
            val cy = dab.position.y.toInt()
            val sampleRadius = (settings.radiusPx * resolved.smudgeRadius).roundToInt().coerceAtLeast(1)
            val sampled = weightedAverage(
                readSource, width, height, cx, cy, sampleRadius, settings.wrapAround,
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
                    val pigment = dilutedPigment(settings, sampled)
                    out = lerpArgb(
                        out,
                        pigment,
                        mask * resolved.opacity * resolved.colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    /**
     * Procreate's Dilution: the pigment about to be deposited is itself pre-mixed with [under] —
     * the colour already at/near this pixel — before being blended into the canvas at the caller's
     * `colorRate`. [Settings.dilution] = 0 (the default) returns [Settings.paintColor] unchanged,
     * matching historical Color Rate deposition exactly.
     */
    private fun dilutedPigment(settings: Settings, under: Int): Int {
        val dilution = settings.dilution.coerceIn(0f, 1f)
        if (dilution <= 0f) return settings.paintColor
        return lerpArgb(under, settings.paintColor, 1f - dilution, includeAlpha = true)
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
