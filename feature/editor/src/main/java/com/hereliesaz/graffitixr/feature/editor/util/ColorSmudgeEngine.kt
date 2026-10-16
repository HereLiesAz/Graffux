package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushReservoirModel
import com.hereliesaz.graffitixr.common.azphalt.BrushReservoirState
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorEngine
import com.hereliesaz.graffitixr.common.azphalt.MaterialColor
import com.hereliesaz.graffitixr.common.azphalt.MaterialMixingModel
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Settings and renderer-neutral dab planning for Graffux's wet-paint paint-op. The pixels are
 * painted on the GPU ([com.hereliesaz.graffitixr.feature.editor.GpuColorSmudge] /
 * [com.hereliesaz.graffitixr.feature.editor.GpuWetSmudge]); this object resolves the resampling and
 * sensor curves into [ResolvedPlan]s that the GPU consumes. There is no CPU pixel path.
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
 * - [Settings.pickupRate] is material-reservoir pickup: after a dab samples its contact colour, a
 *   fraction of currently empty reservoir capacity is refilled from that sampled material. Pickup
 *   happens after the dab writes, so contamination affects subsequent paint rather than the dab
 *   that discovered it. Default 0 keeps the historical path byte-compatible.
 * - [Settings.mixingModel] selects the colour interaction math. [MaterialMixingModel.LEGACY_RGB]
 *   is the byte-compatible default. [MaterialMixingModel.PIGMENT_RYB] uses the material-aware
 *   pigment model so carried/deposited colour behaves more like artist pigments while preserving
 *   the exact historical path for every existing preset.
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
        /**
         * Fraction of currently empty reservoir capacity refilled from sampled material per dab.
         * 0 is the compatibility/default path. Pickup only has room after Charge depletion has
         * created capacity; transparent contact contributes no material.
         */
        val pickupRate: Float = 0f,
        /** Material colour interaction. Legacy RGB is the compatibility/default path. */
        val mixingModel: MaterialMixingModel = MaterialMixingModel.LEGACY_RGB,
        /** Overall dab coverage multiplier. */
        val opacity: Float = 1f,
        /** Radius of the brush footprint in bitmap pixels; supplied by DrawingEngine at replay. */
        val radiusPx: Float = 1f,
        /** Dulling sample radius relative to [radiusPx]. */
        val smudgeRadius: Float = 1f,
        val feathering: Float = 0f,
        /** Whether smudging transports alpha along with RGB. */
        val smearAlpha: Boolean = true,
        /** Initial foreground/reservoir colour used when [colorRate] > 0. */
        val paintColor: Int = Color.BLACK,
        /** Optional Krita-style sensor routes. */
        val dynamics: List<BrushSensorBinding> = emptyList(),
        /**
         * Krita's Sample Merged: when true and the renderer is given a merged composite, Smear's
         * pickup and Dulling's weighted average read from that composite instead of the active
         * layer's own pixels — painting always still writes to the active layer. Recorded on the
         * stroke like every other setting, so replay is deterministic.
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
        /** Sensor-only Color Rate multiplier, before reservoir load is applied. */
        val colorRateMultiplier: Float = 1f,
        /** Arc-length increment from the preceding resolved dab. */
        val distanceDeltaPx: Float = 0f,
    )

    /** One ordered carrier lifetime. */
    data class ResolvedPlan(val dabs: List<ResolvedDab>)

    private data class Resolved(
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
        val colorRateMultiplier: Float,
    )

    /**
     * Resolves resampling and sensor curves into renderer-neutral dabs. The GPU consumes this plan
     * rather than reimplementing input/sensor semantics. Canvas-dependent reservoir pickup is
     * resolved natively from the same per-dab sensor multiplier and distance increment.
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
                    colorRateMultiplier = r.colorRateMultiplier,
                    distanceDeltaPx = if (index == 0) 0f else step,
                )
            })
        }
        return listOf(one(stroke, samples))
    }

    /** True when pickup makes the brush carry mutable material state across dabs. */
    internal fun usesStatefulReservoir(settings: Settings): Boolean =
        settings.pickupRate.coerceIn(0f, 1f) > 0f

    /**
     * Phase 4 uses Dilution as the vehicle fraction: zero is the exact historical dry/legacy path;
     * positive dilution opts the stroke into persistent canvas wetness and wetness-driven mobility.
     */
    internal fun usesPersistentWetness(settings: Settings): Boolean =
        settings.dilution.coerceIn(0f, 1f) > 0f

    private fun initialReservoir(settings: Settings): BrushReservoirState = BrushReservoirState(
        load = 1f,
        wetness = settings.dilution.coerceIn(0f, 1f),
        carriedColor = MaterialColor.fromArgb(settings.paintColor),
    )

    /** @param distancePx cumulative arc length travelled so far, in bitmap pixels — Procreate's `t`. */
    private fun resolve(
        settings: Settings,
        sample: BrushSample?,
        strokeStartUptimeMillis: Long,
        strokeSeed: Long,
        dabIndex: Int,
        distancePx: Float,
    ): Resolved {
        // Analytic Charge depletion. Pickup (canvas-dependent refill) is resolved natively from the
        // per-dab colorRateMultiplier and distance increment this plan carries.
        val reservoir = BrushReservoirModel.stateAtDistance(
            initial = initialReservoir(settings),
            depletionRatePerPx = settings.chargeDecayRate,
            distancePx = distancePx,
        )
        val charge = BrushReservoirModel.effectiveDeposition(settings.colorRate, reservoir)
        if (sample == null || settings.dynamics.isEmpty()) {
            return Resolved(
                settings.smudgeRate.coerceIn(0f, 1f),
                charge,
                settings.opacity.coerceIn(0f, 1f),
                settings.smudgeRadius.coerceAtLeast(0.05f),
                1f,
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
            dynamic.colorRateMultiplier.coerceAtLeast(0f),
        )
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
}
