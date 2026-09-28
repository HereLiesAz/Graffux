package com.hereliesaz.graffitixr.feature.editor.gpu

/**
 * One row of [GpuTierTable]: how much draft/refinement work this device gets, and the minimum
 * calibration numbers a device must reach to be given it.
 */
data class GpuTier(
    val name: String,
    /** Resolution of the multipass draft pass relative to the layer (1.0 = full). */
    val draftResolutionScale: Float,
    /** Draft plus refinement levels the multipass scheduler may run. */
    val qualityLevels: Int,
    /** Edge of one refinement tile in layer pixels. */
    val tileSizePx: Int,
    /** wgpu resident-layer memory budget before thermal scaling. */
    val residentBudgetMiB: Int,
    /** Dabs per GPU dispatch the scheduler may batch before thermal scaling. */
    val dispatchBatchSize: Int,
    /** fp16 shader path allowed. Off in every row until an fp16 path is verified on devices. */
    val fp16: Boolean,
    // ---- Thresholds (all must hold) ------------------------------------------------------------
    val minStampDabsPerMs: Double,
    val minReadbackMBps: Double,
    val maxCompositeMs: Double,
)

/**
 * THE tier table: every per-tier value lives here and nowhere else. Rows go from most
 * conservative to most capable; [GpuTierMapper] picks the last row whose thresholds all hold.
 *
 * Status: the thresholds and values are starting points, not measurements. Nothing here has run on
 * a phone; they were chosen so that (a) [CONSERVATIVE] keeps today's behaviour safe on any device,
 * (b) [HIGH]'s resident budget equals the wgpu engine's existing 256 MiB default, and (c) each step
 * roughly doubles the calibration numbers of the one below. Retune from the calibration numbers the
 * prediction-ranking reports now carry (`gpu tier` line) once real devices report them.
 */
object GpuTierTable {
    val CONSERVATIVE = GpuTier(
        name = "conservative",
        draftResolutionScale = 0.5f,
        qualityLevels = 2,
        tileSizePx = 256,
        residentBudgetMiB = 128,
        dispatchBatchSize = 64,
        fp16 = false,
        minStampDabsPerMs = 0.0,
        minReadbackMBps = 0.0,
        maxCompositeMs = Double.MAX_VALUE,
    )

    val STANDARD = GpuTier(
        name = "standard",
        draftResolutionScale = 0.75f,
        qualityLevels = 3,
        tileSizePx = 256,
        residentBudgetMiB = 192,
        dispatchBatchSize = 128,
        fp16 = false,
        minStampDabsPerMs = 20.0,
        minReadbackMBps = 1_000.0,
        maxCompositeMs = 12.0,
    )

    val HIGH = GpuTier(
        name = "high",
        draftResolutionScale = 1.0f,
        qualityLevels = 4,
        tileSizePx = 512,
        residentBudgetMiB = 256,
        dispatchBatchSize = 256,
        fp16 = false,
        minStampDabsPerMs = 60.0,
        minReadbackMBps = 3_000.0,
        maxCompositeMs = 6.0,
    )

    /** Ascending capability. */
    val rows: List<GpuTier> = listOf(CONSERVATIVE, STANDARD, HIGH)

    /** Used before calibration finishes, when it fails, and when it is skipped. */
    val default: GpuTier get() = CONSERVATIVE

    fun byName(name: String?): GpuTier? = rows.firstOrNull { it.name == name }
}

/** What the calibration measured on the active backend. */
data class CalibrationResult(
    /** Dabs composited per millisecond of stamp time. */
    val stampDabsPerMs: Double,
    /** GPU-to-CPU readback bandwidth, MB/s. */
    val readbackMBps: Double,
    /** One composite of the calibration layer stack, ms. */
    val compositeMs: Double,
    /** Which engine ran it (`vulkan`, `gles`, `wgpu`). */
    val backend: String,
) {
    val isValid: Boolean
        get() = stampDabsPerMs.isFinite() && stampDabsPerMs > 0 &&
            readbackMBps.isFinite() && readbackMBps > 0 &&
            compositeMs.isFinite() && compositeMs >= 0
}

object GpuTierMapper {
    /** The most capable row of [rows] whose thresholds [result] meets; the first row otherwise. */
    fun map(result: CalibrationResult?, rows: List<GpuTier> = GpuTierTable.rows): GpuTier {
        if (result == null || !result.isValid) return rows.first()
        return rows.lastOrNull { tier ->
            result.stampDabsPerMs >= tier.minStampDabsPerMs &&
                result.readbackMBps >= tier.minReadbackMBps &&
                result.compositeMs <= tier.maxCompositeMs
        } ?: rows.first()
    }
}
