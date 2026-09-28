package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionRankingReporter.Companion.ENGINE_AZPHALT
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionRankingReporter.Companion.ENGINE_JETPACK_INK
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
    private val inkTouchToPaint = Series()
    private val inkFirstDab = Series()
    private val inkStabilizerLag = Series()
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
     * Jetpack Ink path: one input's touch-to-paint, from Ink's own `LatencyData` — the OS event
     * timestamp to Ink's estimated pixel-presentation time for the frame that drew it. [strokeStart]
     * marks the stroke's first input (ACTION_DOWN), whose latency is also the Ink first dab, so it
     * measures what [onFirstDabPresented] does for the azphalt engine: pen lands -> ink visible.
     * Kept apart from the azphalt series so a session that flips the toggle never mixes the two.
     */
    @Synchronized
    fun onInkPresented(latencyMs: Double, strokeStart: Boolean) {
        if (latencyMs < 0.0 || latencyMs > MAX_PLAUSIBLE_MS) return
        inkTouchToPaint.add(latencyMs)
        if (strokeStart) inkFirstDab.add(latencyMs)
    }

    /**
     * Jetpack Ink path with a stabilizer level set: how far the editor's stabilizer moved a sample
     * before it reached Ink. Its own series for the same reason as [onInkPresented].
     */
    @Synchronized
    fun onInkStabilized(lagPx: Float) = inkStabilizerLag.add(lagPx.toDouble())

    /**
     * Report lines. [touchToPaint] is the tracker snapshot; [context] describes the canvas/brush
     * load the numbers were measured under. [engine] picks whose numbers are reported: the azphalt
     * engine's tracker/first-dab/stabilizer series, or the Jetpack Ink series — and is printed on
     * the first line either way, so every report says which engine drew its strokes.
     */
    @Synchronized
    fun report(
        touchToPaint: AzphaltLatencyTracker.Snapshot,
        context: String,
        engine: String = ENGINE_AZPHALT,
    ): String = if (engine == ENGINE_JETPACK_INK) inkReport(context) else buildString {
        appendLine("feel ($context)")
        appendLine("  engine: $engine")
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

    private fun inkReport(context: String): String = buildString {
        appendLine("feel ($context)")
        appendLine("  engine: $ENGINE_JETPACK_INK")
        appendLine(
            "  touch->paint: ${inkTouchToPaint.describe("ms")} " +
                "(Ink LatencyData: OS event, or view receipt when stabilized, -> estimated pixel presentation)",
        )
        appendLine("  input delivery: ${delivery.describe("ms")}")
        appendLine("  first dab: ${inkFirstDab.describe("ms")} (ACTION_DOWN -> first Ink frame presented)")
        // "no data" here means every Ink stroke so far ran at stabilizer level 0, which hands Ink the
        // raw MotionEvents; with a level set, the editor's stabilizer runs before Ink sees a sample.
        append("  stabilizer lag: ${inkStabilizerLag.describe("px")}")
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
        // Ink's presentation time is an estimate; anything past a second is a clock mismatch or a
        // stroke parked in the background, not a latency, and would swamp the mean.
        const val MAX_PLAUSIBLE_MS = 1000.0
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
