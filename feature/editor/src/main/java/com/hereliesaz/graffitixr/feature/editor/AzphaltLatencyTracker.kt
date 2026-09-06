package com.hereliesaz.graffitixr.feature.editor

import android.os.SystemClock

/**
 * Bounded rolling latency telemetry for Azphalt Engine 2.
 *
 * Every physical brush sample moves through four monotonic timestamps: input accepted, dabs
 * generated, render submitted, and preview published. A small monitor protects each logical ring
 * entry as one unit. That is intentional: a lock-free collection of independent timestamp arrays
 * lets a wrapped slot be observed half-reinitialized (or written by a stale asynchronous stage),
 * fabricating latencies. At touch rates this fixed-size, constant-time critical section is much
 * cheaper than the rendering it measures and keeps the diagnostic trustworthy.
 */
class AzphaltLatencyTracker(private val capacity: Int = 256) {
    init { require(capacity > 0) }

    private data class Entry(
        var id: Long = -1L,
        var inputNs: Long = 0L,
        var generatedNs: Long = 0L,
        var submittedNs: Long = 0L,
        var presentedNs: Long = 0L,
    )

    private val lock = Any()
    private val entries = Array(capacity) { Entry() }
    private var nextId = 0L

    fun beginInput(nowNs: Long = SystemClock.elapsedRealtimeNanos()): Long = synchronized(lock) {
        val id = nextId++
        entries[slot(id)].apply {
            this.id = id
            inputNs = nowNs
            generatedNs = 0L
            submittedNs = 0L
            presentedNs = 0L
        }
        id
    }

    fun markGenerated(id: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()) = synchronized(lock) {
        entries[slot(id)].takeIf { it.id == id }?.generatedNs = nowNs
    }

    fun markSubmitted(id: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()) = synchronized(lock) {
        entries[slot(id)].takeIf { it.id == id }?.submittedNs = nowNs
    }

    fun markPresented(id: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()) = synchronized(lock) {
        entries[slot(id)].takeIf { it.id == id }?.presentedNs = nowNs
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val end = nextId
        val start = (end - capacity).coerceAtLeast(0L)
        val totals = ArrayList<Long>((end - start).toInt())
        val inputToGenerated = ArrayList<Long>()
        val generatedToSubmitted = ArrayList<Long>()
        val submittedToPresented = ArrayList<Long>()
        var retained = 0

        for (id in start until end) {
            val entry = entries[slot(id)]
            if (entry.id != id || entry.inputNs <= 0L) continue
            retained++
            val input = entry.inputNs
            val generated = entry.generatedNs
            val submitted = entry.submittedNs
            val presented = entry.presentedNs
            if (generated >= input) inputToGenerated += generated - input
            if (submitted >= generated && generated > 0L) generatedToSubmitted += submitted - generated
            if (presented >= submitted && submitted > 0L) submittedToPresented += presented - submitted
            if (presented >= input && presented > 0L) totals += presented - input
        }

        Snapshot(
            retainedSamples = retained,
            completedSamples = totals.size,
            inputToGenerated = Stats.of(inputToGenerated),
            generatedToSubmitted = Stats.of(generatedToSubmitted),
            submittedToPresented = Stats.of(submittedToPresented),
            total = Stats.of(totals),
        )
    }

    private fun slot(id: Long): Int = (id % capacity).toInt()

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
