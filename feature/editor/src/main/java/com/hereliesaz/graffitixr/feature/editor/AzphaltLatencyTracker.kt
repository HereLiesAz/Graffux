package com.hereliesaz.graffitixr.feature.editor

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Low-overhead rolling latency telemetry for Azphalt Engine 2.
 *
 * Every sample moves through four monotonic timestamps: input accepted, dabs generated, render
 * submitted, and preview published. The tracker keeps only the newest fixed-size ring so drawing
 * never allocates an unbounded metrics history. It is deliberately independent of Logcat/UI; the
 * editor can snapshot it for benchmarks or diagnostics without putting logging in the hot path.
 */
class AzphaltLatencyTracker(private val capacity: Int = 256) {
    init { require(capacity > 0) }

    private val sequence = AtomicLong(0L)
    private val inputNs = AtomicLongArray(capacity)
    private val generatedNs = AtomicLongArray(capacity)
    private val submittedNs = AtomicLongArray(capacity)
    private val presentedNs = AtomicLongArray(capacity)

    fun beginInput(nowNs: Long = SystemClock.elapsedRealtimeNanos()): Long {
        val id = sequence.getAndIncrement()
        val slot = slot(id)
        inputNs.set(slot, nowNs)
        generatedNs.set(slot, 0L)
        submittedNs.set(slot, 0L)
        presentedNs.set(slot, 0L)
        return id
    }

    fun markGenerated(id: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        if (isRetained(id)) generatedNs.set(slot(id), nowNs)
    }

    fun markSubmitted(id: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        if (isRetained(id)) submittedNs.set(slot(id), nowNs)
    }

    fun markPresented(id: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()) {
        if (isRetained(id)) presentedNs.set(slot(id), nowNs)
    }

    fun snapshot(): Snapshot {
        val end = sequence.get()
        val start = (end - capacity).coerceAtLeast(0L)
        val totals = ArrayList<Long>((end - start).toInt())
        val inputToGenerated = ArrayList<Long>()
        val generatedToSubmitted = ArrayList<Long>()
        val submittedToPresented = ArrayList<Long>()
        for (id in start until end) {
            val slot = slot(id)
            val input = inputNs.get(slot)
            val generated = generatedNs.get(slot)
            val submitted = submittedNs.get(slot)
            val presented = presentedNs.get(slot)
            if (input <= 0L) continue
            if (generated >= input) inputToGenerated += generated - input
            if (submitted >= generated && generated > 0L) generatedToSubmitted += submitted - generated
            if (presented >= submitted && submitted > 0L) submittedToPresented += presented - submitted
            if (presented >= input && presented > 0L) totals += presented - input
        }
        return Snapshot(
            retainedSamples = (end - start).toInt(),
            completedSamples = totals.size,
            inputToGenerated = Stats.of(inputToGenerated),
            generatedToSubmitted = Stats.of(generatedToSubmitted),
            submittedToPresented = Stats.of(submittedToPresented),
            total = Stats.of(totals),
        )
    }

    private fun slot(id: Long): Int = (id % capacity).toInt()
    private fun isRetained(id: Long): Boolean {
        val end = sequence.get()
        return id >= (end - capacity).coerceAtLeast(0L) && id < end
    }

    data class Snapshot(
        val retainedSamples: Int,
        val completedSamples: Int,
        val inputToGenerated: Stats,
        val generatedToSubmitted: Stats,
        val submittedToPresented: Stats,
        val total: Stats,
    )

    data class Stats(
        val count: Int,
        val minNs: Long,
        val medianNs: Long,
        val p95Ns: Long,
        val maxNs: Long,
    ) {
        val medianMs: Double get() = medianNs / 1_000_000.0
        val p95Ms: Double get() = p95Ns / 1_000_000.0

        companion object {
            fun of(values: List<Long>): Stats {
                if (values.isEmpty()) return Stats(0, 0L, 0L, 0L, 0L)
                val sorted = values.sorted()
                fun percentile(p: Double): Long {
                    val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
                    return sorted[index]
                }
                return Stats(
                    count = sorted.size,
                    minNs = sorted.first(),
                    medianNs = percentile(0.50),
                    p95Ns = percentile(0.95),
                    maxNs = sorted.last(),
                )
            }
        }
    }
}
