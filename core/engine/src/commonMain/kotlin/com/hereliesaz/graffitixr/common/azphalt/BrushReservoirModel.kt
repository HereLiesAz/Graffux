package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.exp
import kotlin.math.min

/**
 * Deterministic transition model for a stroke-local brush reservoir.
 *
 * This object owns reservoir bookkeeping only. It deliberately does not know about bitmap pixels,
 * brush footprints, Vulkan tiles, or editor state. Higher layers decide how much contact happened
 * and express deposition/pickup as normalized fractions of a full reservoir capacity.
 *
 * Keeping the transition model renderer-independent gives CPU and GPU paths one behavioral contract
 * and keeps canonical replay derivable from stroke input, media settings, seed, and starting canvas
 * state rather than wall-clock state.
 */
object BrushReservoirModel {

    /** Result of one bounded reservoir interaction. */
    data class Transfer(
        val state: BrushReservoirState,
        /** Normalized load actually removed from the reservoir. */
        val depositedLoad: Float,
        /** Normalized load actually accepted back into the reservoir. */
        val pickedUpLoad: Float,
    )

    /**
     * Analytic depletion envelope used by the first integration tranche.
     *
     * This exactly matches Graffux's existing Color Smudge Charge rule when the initial load is 1:
     * `load(t) = exp(-depletionRatePerPx * distancePx)`. Moving that rule here lets the existing
     * visible behavior become an explicit reservoir state without changing its output.
     */
    fun stateAtDistance(
        initial: BrushReservoirState,
        depletionRatePerPx: Float,
        distancePx: Float,
    ): BrushReservoirState {
        val start = initial.sanitized()
        val rate = depletionRatePerPx.coerceAtLeast(0f)
        val distance = distancePx.coerceAtLeast(0f)
        if (rate == 0f || distance == 0f || start.load == 0f) return start
        return start.copy(load = (start.load * exp(-rate * distance)).coerceIn(0f, 1f))
    }

    /** Base deposition/Color Rate modulated only by currently available reservoir load. */
    fun effectiveDeposition(baseRate: Float, state: BrushReservoirState): Float =
        (baseRate.coerceIn(0f, 1f) * state.sanitized().load).coerceIn(0f, 1f)

    /**
     * Applies one normalized deposition/pickup transaction.
     *
     * [depositRequest] and [pickupRequest] are fractions of a full reservoir capacity, not pixel
     * counts. The caller/contact model is responsible for deriving them from area, pressure, flow,
     * medium settings, etc. This function only guarantees conservation bounds and deterministic
     * mixing.
     *
     * Pickup first sees the space left after deposition. Accepted pickup contaminates the carried
     * colour and wetness by load-weighted mass, so a nearly empty brush adopts sampled material much
     * faster than a full one. No request can overdraw below 0 or overfill above 1.
     */
    fun transfer(
        state: BrushReservoirState,
        sampledColor: MaterialColor,
        sampledWetness: Float = 0f,
        depositRequest: Float = 0f,
        pickupRequest: Float = 0f,
        mixingModel: MaterialMixingModel = MaterialMixingModel.LEGACY_RGB,
    ): Transfer {
        val before = state.sanitized()
        val deposit = min(before.load, depositRequest.coerceAtLeast(0f))
        val retainedLoad = (before.load - deposit).coerceIn(0f, 1f)
        val capacity = 1f - retainedLoad
        val pickup = min(capacity, pickupRequest.coerceAtLeast(0f))
        val finalLoad = (retainedLoad + pickup).coerceIn(0f, 1f)

        val nextColor: MaterialColor
        val nextWetness: Float
        if (pickup > 0f && finalLoad > 0f) {
            val pickupRatio = (pickup / finalLoad).coerceIn(0f, 1f)
            nextColor = MaterialColorMixer.mix(
                before.carriedColor,
                sampledColor,
                pickupRatio,
                mixingModel,
                includeAlpha = true,
            )
            nextWetness = (
                before.wetness * (retainedLoad / finalLoad) +
                    sampledWetness.coerceIn(0f, 1f) * pickupRatio
                ).coerceIn(0f, 1f)
        } else {
            nextColor = before.carriedColor
            nextWetness = before.wetness
        }

        return Transfer(
            state = BrushReservoirState(
                load = finalLoad,
                wetness = nextWetness,
                carriedColor = nextColor,
            ).sanitized(),
            depositedLoad = deposit,
            pickedUpLoad = pickup,
        )
    }
}
