package com.hereliesaz.graffitixr.feature.editor.gpu

import com.hereliesaz.graffitixr.nativebridge.PassKind
import com.hereliesaz.graffitixr.nativebridge.PassTimingSink

/**
 * TEMPORARY, like the rest of the prediction-ranking report: session-long GPU numbers appended to
 * the "feel" block (StrokeFeelMeter). Receives every pass [com.hereliesaz.graffitixr.nativebridge.GpuStampEngine]
 * times through [PassTimingSink] and reports, per pass kind, GPU-timestamp percentiles when the
 * engine had them and CPU wall time around the native call otherwise, labelled as such.
 *
 * Frame-budget misses: a stroke worker's stamp calls since its last readback, plus that readback,
 * are one presented batch; a batch whose CPU wall time exceeds the display frame is a miss. CPU
 * wall time is used on purpose: it is what the batch actually blocked for.
 */
class GpuTelemetry(private val frameBudgetNanos: () -> Long) : PassTimingSink {
    private val gpu = PassKind.entries.associateWith { Reservoir() }
    private val cpu = PassKind.entries.associateWith { Reservoir() }
    private var batchNanos = 0L
    private var batches = 0
    private var misses = 0
    private var thermalStart: ThermalSnapshot? = null
    private var thermalEnd: ThermalSnapshot? = null

    /** True while calibration runs: its synthetic passes are not the user's strokes. */
    @Volatile var paused: Boolean = false

    @Synchronized
    override fun onPass(kind: Int, nanos: Long, gpu: Boolean) {
        val k = PassKind.entries.getOrNull(kind)
        if (k == null || nanos < 0 || paused) return
        (if (gpu) this.gpu else cpu).getValue(k).add(nanos)
        if (!gpu) onCpuPass(k, nanos)
    }

    private fun onCpuPass(k: PassKind, nanos: Long) {
        batchNanos += nanos
        if (k == PassKind.READBACK) {
            batches += 1
            if (batchNanos > frameBudgetNanos()) misses += 1
            batchNanos = 0L
        }
    }

    /** CPU wall time of a composite done outside the stamp engine (the editor's layer composite). */
    fun onComposite(nanos: Long) = onPass(PassKind.COMPOSITE.ordinal, nanos, false)

    @Synchronized
    fun onThermal(snapshot: ThermalSnapshot) = onThermalLocked(snapshot)

    /**
     * Report lines, in StrokeFeelMeter's style (two-space indent, no trailing newline).
     * [info]/[tuning] describe the device and what the tuner chose; [thermalNow] is sampled at
     * report time so "end" is current.
     */
    @Synchronized
    fun report(info: GpuInfo, tuning: GpuTuning?, thermalNow: ThermalSnapshot?): String = buildString {
        thermalNow?.let { onThermalLocked(it) }
        appendLine("  gpu: ${describe(info)}")
        tuning?.let { appendLine("  gpu tier: ${it.describe()}") }
        appendLine("  gpu passes: " + PassKind.entries.joinToString(", ") { passLine(it) })
        val budgetMs = frameBudgetNanos() / NANOS_PER_MS
        appendLine(
            if (batches == 0) {
                "  frame budget: no data"
            } else {
                "  frame budget: %d of %d batches over %.1fms".format(misses, batches, budgetMs)
            },
        )
        append(
            "  thermal: start ${thermalStart?.describe() ?: "?"}, end ${thermalEnd?.describe() ?: "?"}",
        )
    }

    private fun onThermalLocked(snapshot: ThermalSnapshot) {
        if (thermalStart == null) thermalStart = snapshot
        thermalEnd = snapshot
    }

    private fun passLine(kind: PassKind): String {
        val g = gpu.getValue(kind)
        val c = cpu.getValue(kind)
        return when {
            g.count > 0 -> "${kind.label} ${g.describe()} gpu"
            c.count > 0 -> "${kind.label} ${c.describe()} cpu"
            else -> "${kind.label} n/a"
        }
    }

    private fun describe(info: GpuInfo): String {
        if (!info.isKnown) return "unknown (no engine initialized yet)"
        val parts = mutableListOf(info.renderer, "vendor ${info.vendorLabel}")
        if (info.driver.isNotEmpty()) parts += "driver ${info.driver}"
        if (info.driverInfo.isNotEmpty()) parts += info.driverInfo
        parts += "vulkan ${info.apiVersion.ifEmpty { "n/a" }}"
        parts += "backend ${info.engine}/${info.backend}"
        parts += "timestamps ${if (info.timestamps) "yes" else "no"}"
        return parts.joinToString(", ")
    }

    /** Count plus p50/p95 over a bounded reservoir of the most recent samples. */
    private class Reservoir {
        private val recent = ArrayDeque<Long>()
        var count = 0
            private set

        fun add(nanos: Long) {
            count += 1
            recent.addLast(nanos)
            while (recent.size > CAPACITY) recent.removeFirst()
        }

        fun describe(): String {
            val sorted = recent.sorted()
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / NANOS_PER_MS
            return "p50 %.2fms p95 %.2fms (n=%d)".format(pct(P50), pct(P95), count)
        }
    }

    private companion object {
        const val CAPACITY = 512
        const val P50 = 0.5
        const val P95 = 0.95
        const val NANOS_PER_MS = 1_000_000.0
    }
}
