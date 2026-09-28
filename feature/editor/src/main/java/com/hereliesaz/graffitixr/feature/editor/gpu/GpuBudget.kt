package com.hereliesaz.graffitixr.feature.editor.gpu

import com.hereliesaz.graffitixr.common.azphalt.wgpu.MultipassBudget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** PowerManager thermal status and forecast headroom at one moment. */
data class ThermalSnapshot(
    /** `PowerManager.THERMAL_STATUS_*` (0 none .. 6 shutdown); -1 = unavailable (API < 29). */
    val status: Int = STATUS_UNKNOWN,
    /**
     * `PowerManager.getThermalHeadroom(forecast)`: 1.0 = the device reaches SEVERE throttling;
     * NaN = unavailable (API < 30, not supported, or polled too often).
     */
    val headroom: Float = Float.NaN,
) {
    val statusLabel: String
        get() = STATUS_LABELS.getOrNull(status) ?: "unknown"

    fun describe(): String =
        if (headroom.isNaN()) statusLabel else "%s (headroom %.2f)".format(statusLabel, headroom)

    companion object {
        const val STATUS_UNKNOWN = -1
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4
        private val STATUS_LABELS =
            listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
    }
}

/**
 * How much extra GPU work the device can afford right now: the calibrated [GpuTier] scaled by
 * thermal state. [scale] = 1 is the tier as calibrated, 0 = draft only / no optional work.
 */
data class GpuBudget(
    val tier: GpuTier,
    val thermal: ThermalSnapshot,
    val scale: Float,
) {
    /** Resident-layer budget for the wgpu engine, never below [MIN_RESIDENT_MIB]. */
    val residentBudgetBytes: Long
        get() = (tier.residentBudgetMiB * scale).toLong().coerceAtLeast(MIN_RESIDENT_MIB) * MIB

    /** Dispatch batch size the scheduler may use, at least 1. */
    val dispatchBatchSize: Int
        get() = (tier.dispatchBatchSize * scale).toInt().coerceAtLeast(1)

    /** Quality levels allowed now: the draft always, refinements scaled down. */
    val qualityLevels: Int
        get() = 1 + ((tier.qualityLevels - 1) * scale).toInt()

    /** Fraction of the per-frame refinement/extra-work budget the scheduler may spend. */
    val refinementFraction: Float get() = scale

    /**
     * What the wgpu multipass scheduler takes: the tier's draft resolution as a divisor (0.5 -> 2,
     * 0.25 -> 4, clamped to 1..8), the thermally scaled quality levels and refinement fraction, and
     * the tier's refinement tile size as the largest refinement chunk.
     */
    fun toMultipassBudget(): MultipassBudget = MultipassBudget(
        draftScale = draftDivisor(tier.draftResolutionScale),
        qualityLevels = qualityLevels,
        refinementFraction = refinementFraction,
        tileSizePx = tier.tileSizePx,
    )

    companion object {
        /** 1 / scale rounded to the engine's divisors 1, 2, 4, 8. */
        fun draftDivisor(scale: Float): Int {
            if (!(scale > 0f)) return MAX_DRAFT_DIVISOR
            val d = (1f / scale).coerceIn(1f, MAX_DRAFT_DIVISOR.toFloat())
            return DRAFT_DIVISORS.minBy { kotlin.math.abs(it - d) }
        }

        private const val MAX_DRAFT_DIVISOR = 8

        /** The draft resolution divisors the wgpu engine supports. */
        private val DRAFT_DIVISORS = listOf(1, 2, 4, MAX_DRAFT_DIVISOR)
        const val MIB = 1024L * 1024L
        const val MIN_RESIDENT_MIB = 32L
    }
}

/**
 * The hook the multipass draft/clarity scheduler consumes: the current [GpuBudget], updated as the
 * calibrated tier lands or thermal headroom moves. Today only the wgpu resident-layer budget reads
 * it (`GpuTuningController`); the scheduler (branch claude/multipass-aging) is expected to collect
 * [budget] and size its refinement passes from [GpuBudget.qualityLevels],
 * [GpuBudget.dispatchBatchSize], [GpuBudget.refinementFraction] and the tier's draft scale and tile
 * size. It must not assume a fixed tier: the tier can change mid-session when calibration lands.
 */
interface GpuBudgetProvider {
    val budget: StateFlow<GpuBudget>
}

/**
 * Thermal scaling, pure so it is testable: the smaller of a cap from the thermal status and a ramp
 * on forecast headroom. Headroom below [HEADROOM_RAMP_START] costs nothing; from there to 1.0
 * (SEVERE forecast) the budget falls linearly to [HEADROOM_FLOOR]. Unknown headroom leaves the
 * status as the only signal.
 */
object ThermalBudgetScaler {
    const val HEADROOM_RAMP_START = 0.7f
    const val HEADROOM_FLOOR = 0.25f
    private const val MODERATE_CAP = 0.6f
    private const val SEVERE_CAP = 0.3f

    fun scale(thermal: ThermalSnapshot): Float = minOf(statusCap(thermal.status), headroomScale(thermal.headroom))

    fun statusCap(status: Int): Float = when {
        status <= ThermalSnapshot.STATUS_LIGHT -> 1f
        status == ThermalSnapshot.STATUS_MODERATE -> MODERATE_CAP
        status == ThermalSnapshot.STATUS_SEVERE -> SEVERE_CAP
        else -> 0f
    }

    fun headroomScale(headroom: Float): Float = when {
        headroom.isNaN() || headroom <= HEADROOM_RAMP_START -> 1f
        headroom >= 1f -> HEADROOM_FLOOR
        else -> {
            val t = (headroom - HEADROOM_RAMP_START) / (1f - HEADROOM_RAMP_START)
            1f - t * (1f - HEADROOM_FLOOR)
        }
    }
}

/** [GpuBudgetProvider] from a tier and thermal snapshots pushed in by [ThermalMonitor]. */
class ThermalGpuBudgetProvider(initialTier: GpuTier = GpuTierTable.default) : GpuBudgetProvider {
    private val state = MutableStateFlow(GpuBudget(initialTier, ThermalSnapshot(), 1f))
    override val budget: StateFlow<GpuBudget> = state.asStateFlow()

    // Read-modify-write through update {} so a concurrent setTier/onThermal cannot drop the other's field.
    fun setTier(tier: GpuTier) = state.update { budgetOf(tier, it.thermal) }

    fun onThermal(thermal: ThermalSnapshot) = state.update { budgetOf(it.tier, thermal) }

    private fun budgetOf(tier: GpuTier, thermal: ThermalSnapshot) =
        GpuBudget(tier, thermal, ThermalBudgetScaler.scale(thermal))
}
