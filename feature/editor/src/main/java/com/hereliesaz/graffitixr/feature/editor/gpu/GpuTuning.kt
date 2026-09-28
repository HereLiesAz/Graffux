package com.hereliesaz.graffitixr.feature.editor.gpu

/**
 * Everything the tuner decided for this device: the [tier] (calibrated or the conservative
 * default), the GPU family, and the per-family/per-driver knobs derived from them.
 */
data class GpuTuning(
    val tier: GpuTier,
    /** False = [GpuTierTable.default] standing in until (or because no) calibration landed. */
    val calibrated: Boolean,
    val detected: DetectedGpu,
    /** Stamp workgroup edge to request (Vulkan and wgpu honour it). */
    val stampTile: Int,
    /** fp16 path on: requires the tier, device support and no workaround against it. Off today. */
    val fp16: Boolean,
    /** Request GPU timestamp queries. */
    val timestamps: Boolean,
    /** Ids of [DriverWorkarounds] entries that matched. */
    val workarounds: List<String>,
    val result: CalibrationResult?,
) {
    fun describe(): String = buildString {
        append(tier.name)
        append(if (calibrated) " (calibrated" else " (default")
        result?.let {
            append(
                ": stamp %.1f dabs/ms, readback %.0f MB/s, composite %.1fms on %s".format(
                    it.stampDabsPerMs, it.readbackMBps, it.compositeMs, it.backend,
                ),
            )
        }
        append("), family ${detected.label}, workgroup ${stampTile}x$stampTile")
        append(", fp16 ${if (fp16) "on" else "off"}")
        if (workarounds.isNotEmpty()) append(", workarounds ${workarounds.joinToString("+")}")
    }

    companion object {
        /** Before anything is known: conservative tier, engine defaults. */
        val INITIAL = GpuTuning(
            tier = GpuTierTable.default,
            calibrated = false,
            detected = DetectedGpu(GpuFamily.UNKNOWN, tensor = false),
            stampTile = WorkgroupPolicy.DEFAULT_TILE,
            fp16 = false,
            timestamps = true,
            workarounds = emptyList(),
            result = null,
        )

        fun resolve(
            info: GpuInfo,
            detected: DetectedGpu,
            tier: GpuTier,
            /** The calibration behind [tier]; null = [tier] is the uncalibrated default. */
            result: CalibrationResult?,
            workarounds: List<DriverWorkaround> = DriverWorkarounds.table,
        ): GpuTuning {
            val matched = DriverWorkarounds.matching(info, workarounds)
            val effect = DriverWorkarounds.effectFor(info, matched)
            return GpuTuning(
                tier = tier,
                calibrated = result != null,
                detected = detected,
                stampTile = effect.forceStampTile ?: WorkgroupPolicy.stampTile(detected.family),
                fp16 = tier.fp16 && info.shaderF16 && !effect.disableFp16,
                timestamps = !effect.disableTimestamps,
                workarounds = matched.map { it.id },
                result = result,
            )
        }
    }
}
