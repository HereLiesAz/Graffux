package com.hereliesaz.graffitixr.feature.editor

import kotlin.math.max

/**
 * TEMPORARY session-long measurements of how drawing *feels*, appended to the stroke-prediction
 * reports so tuning is driven by the device, not by guesses about other apps. Covers the parts of
 * the input-to-screen path the prediction tail can't hide:
 * - input delivery: the hardware sample's own timestamp to the moment the editor accepts it;
 * - first dab: stroke-start sample accepted to its paint published (the "does ink appear when the
 *   pen lands" delay);
 * - stabilizer lag: distance between the raw sample and the stabilized point the brush paints.
 *
 * Touch-to-paint percentiles for every sample come from [AzphaltLatencyTracker] and dropped/late
 * UI frames from the canvas; this class formats those alongside its own numbers.
 */
class StrokeFeelMeter {
    // Every entry point is @Synchronized: input arrives on the main thread, but the first dab's
    // "presented" moment is observed on the live-render worker.
    private val delivery = Series()
    private val firstDab = Series()
    private val stabilizerLag = Series()
    private var firstDabPending = false

    /** A stroke started: the next [onFirstDabPresented] belongs to it. */
    @Synchronized
    fun onStrokeStart() {
        firstDabPending = true
    }

    /** [eventUptimeMs] = the sample's hardware timestamp; [acceptedUptimeMs] = now. */
    @Synchronized
    fun onSampleAccepted(eventUptimeMs: Long, acceptedUptimeMs: Long) {
        if (eventUptimeMs > 0L && acceptedUptimeMs >= eventUptimeMs) {
            delivery.add((acceptedUptimeMs - eventUptimeMs).toDouble())
        }
    }

    /** Paint published for the stroke's first sample, [latencyMs] after it was accepted. */
    @Synchronized
    fun onFirstDabPresented(latencyMs: Double) {
        if (!firstDabPending) return
        firstDabPending = false
        firstDab.add(latencyMs)
    }

    @Synchronized
    fun onStabilized(lagPx: Float) = stabilizerLag.add(lagPx.toDouble())

    /**
     * Report lines. [touchToPaint] is the tracker snapshot; [context] describes the canvas/brush
     * load the numbers were measured under.
     */
    @Synchronized
    fun report(touchToPaint: AzphaltLatencyTracker.Snapshot, context: String): String = buildString {
        appendLine("feel ($context)")
        val t = touchToPaint
        if (t.total.count > 0) {
            appendLine(
                "  touch->paint: median ${ms(t.total.medianMs)} p95 ${ms(t.total.p95Ms)} " +
                    "(generate ${ms(t.inputToGenerated.medianMs)}, " +
                    "queue ${ms(t.generatedToSubmitted.medianMs)}, " +
                    "render+publish ${ms(t.submittedToPresented.medianMs)}; n=${t.total.count})",
            )
        } else {
            appendLine("  touch->paint: no data")
        }
        appendLine("  input delivery: ${delivery.describe("ms")}")
        appendLine("  first dab: ${firstDab.describe("ms")}")
        append("  stabilizer lag: ${stabilizerLag.describe("px")}")
    }

    private fun ms(v: Double) = "%.1fms".format(v)

    /** Running mean and max plus a coarse p95 from a bounded reservoir. */
    private class Series {
        private var sum = 0.0
        private var count = 0
        private var maxValue = 0.0
        private val recent = ArrayDeque<Double>()

        fun add(v: Double) {
            sum += v
            count += 1
            maxValue = max(maxValue, v)
            recent.addLast(v)
            while (recent.size > RESERVOIR) recent.removeFirst()
        }

        fun describe(unit: String): String {
            if (count == 0) return "no data"
            val sorted = recent.sorted()
            val p95 = sorted[((sorted.size - 1) * P95).toInt()]
            val mean = sum / count
            return "mean %.1f%s p95 %.1f%s max %.1f%s (n=%d)".format(mean, unit, p95, unit, maxValue, unit, count)
        }
    }

    private companion object {
        const val RESERVOIR = 512
        const val P95 = 0.95
    }
}

/**
 * TEMPORARY. UI frame intervals while a Brush stroke is on screen: how many frames ran late (more
 * than 1.5 display periods, i.e. at least one refresh missed) and the worst one. Session-long.
 */
class FrameIntervalMeter(private val framePeriodNs: Long) {
    private var frames = 0
    private var late = 0
    private var worstNs = 0L

    @Synchronized
    fun add(intervalNs: Long) {
        if (intervalNs <= 0L) return
        frames += 1
        if (intervalNs > framePeriodNs * LATE_NUMERATOR / LATE_DENOMINATOR) late += 1
        worstNs = max(worstNs, intervalNs)
    }

    @Synchronized
    fun report(): String = if (frames == 0) {
        "  frames while drawing: no data"
    } else {
        "  frames while drawing: %d, late %d (%.1f%%), worst %.1fms".format(
            frames, late, late * PERCENT / frames, worstNs / NANOS_PER_MS,
        )
    }

    private companion object {
        // Late = more than 1.5 periods, i.e. at least one refresh was missed.
        const val LATE_NUMERATOR = 3L
        const val LATE_DENOMINATOR = 2L
        const val PERCENT = 100.0
        const val NANOS_PER_MS = 1_000_000.0
    }
}
