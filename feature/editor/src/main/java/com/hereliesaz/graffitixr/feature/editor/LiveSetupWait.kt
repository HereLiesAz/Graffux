package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The longest a commit waits for a superseded live setup to hand over its engine. Unmeasured: the
 * document engine's own lease wait is 2 s ([com.hereliesaz.graffitixr.nativebridge.GpuStampEngine]),
 * and this is that plus a margin. The wait exists only because the engine cannot be had before the
 * setup finishes; past it the commit paints on an engine of its own, as it did before, and the late
 * setup destroys the engine it built ([offer] answers false).
 */
internal const val SUPERSEDED_SETUP_WAIT_MS = 2_500L

/**
 * Where a live engine setup that finished AFTER its stroke ended leaves the engine it built, for the
 * stroke's commit to paint on, instead of destroying it and making the commit build a second one.
 *
 * Every call that names a stroke names it by its [seed]. All state is under one lock; the engine is
 * never touched here except to [release] it, which the owner makes safe to call from any thread.
 * A parked engine holds the document engine's lease (the next stroke's setup waits for it) until its
 * commit has run: the same as a live engine, which is also held until its commit finishes.
 *
 * * [expect] (main thread, at the stroke's end): a commit will [claim] this stroke's engine.
 * * [offer] (main thread, when the setup finishes late): true = parked, the commit owns it now;
 *   false = nobody wants it (cancelled stroke, commit already gone or timed out), destroy it.
 * * [claim] (commit worker, or its completion handler): the parked engine, if any, and the
 *   expectation is dropped, so a setup that finishes later is told false. Idempotent.
 */
internal class SupersededEngines<E : Any>(private val release: (E) -> Unit) {
    private val lock = Any()
    private val expected = HashSet<Long>()
    private val parked = HashMap<Long, E>()

    fun expect(seed: Long) {
        synchronized(lock) { expected.add(seed) }
    }

    fun offer(seed: Long, engine: E): Boolean = synchronized(lock) {
        (seed in expected).also { if (it) parked[seed] = engine }
    }

    fun claim(seed: Long): E? = synchronized(lock) {
        expected.remove(seed)
        parked.remove(seed)
    }

    /** The editor is going away: nothing will claim what is parked. */
    fun discardAll() {
        val orphans = synchronized(lock) {
            expected.clear()
            parked.values.toList().also { parked.clear() }
        }
        orphans.forEach(release)
    }
}

/**
 * The engine a superseded live setup of [seed]'s stroke built, once [setup] has finished (waited for
 * at most [maxWaitMs]); null when none was parked, or the setup is still running at the bound.
 * Always ends the expectation, so a late setup destroys its engine. Suspends the commit worker
 * only: never the main thread, never the touch path (#864). Logs what happened, not just that the
 * setup finished.
 */
internal suspend fun <E : Any> awaitSupersededEngine(
    engines: SupersededEngines<E>,
    setup: Job?,
    seed: Long,
    maxWaitMs: Long = SUPERSEDED_SETUP_WAIT_MS,
    log: (String) -> Unit = {},
): E? {
    var timedOut = false
    var waitedMs = 0L
    if (setup != null && !setup.isCompleted) {
        val t0 = System.nanoTime()
        timedOut = withTimeoutOrNull(maxWaitMs) { setup.join() } == null
        waitedMs = (System.nanoTime() - t0) / NANOS_PER_MS_WAIT
    }
    val parked = engines.claim(seed)
    if (setup != null) {
        val outcome = when {
            timedOut -> "setup still running at the bound (timeout); the commit uses an engine of its own"
            parked != null -> "engine parked"
            else -> "no engine parked (setup failed, was refused or had none)"
        }
        log("commit: waited ${waitedMs}ms for the superseded live setup: $outcome")
    }
    return parked
}

/**
 * Paints a stroke's commit on the engine a superseded live setup parked, when it can: [paint]
 * receives that engine (the one used), or null (a fresh engine is built, as without any setup), and
 * a `done` it must call before it builds any engine of its own (a failed attempt on the adopted
 * engine is retried on a fresh one: two devices must not coexist). `done` invalidates the engine's
 * resident GPU copy of the layer (it holds the stroke under the old generation; a later bind must
 * not trust it: the commit does not retag it as a live commit does, and the next stroke binds the
 * new bitmap, which has no copy, so this costs nothing extra) and then releases the engine; it runs
 * once, whichever of the paint's failure path, its normal exit, an exception or a cancellation
 * comes first. A parked engine that is not used is released the same way, with the reason logged:
 * [refusal] (the caller's: selection), a resize since the stroke started, or a [reseed] that failed
 * or threw.
 *
 * [rebased]: the layer changed since the stroke started (a commit that finished meanwhile, which is
 * the back-to-back case), so the engine's layer is stale; [reseed] uploads the layer the stroke is
 * painted onto, into the same engine (no second device), and [sizeChanged] (the layer was resized)
 * refuses, since that upload would not fit.
 */
@Suppress("LongParameterList")
internal suspend fun <E : Any, T> paintOnSupersededEngine(
    engines: SupersededEngines<E>,
    setup: Job?,
    seed: Long,
    rebased: Boolean,
    sizeChanged: Boolean,
    refusal: String?,
    reseed: (E) -> Boolean,
    invalidate: () -> Unit,
    release: (E) -> Unit,
    log: (String) -> Unit,
    paint: suspend (E?, done: () -> Unit) -> T,
): T {
    val parked = awaitSupersededEngine(engines, setup, seed, log = log)
    val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    val done = {
        if (parked != null && finished.compareAndSet(false, true)) {
            try {
                invalidate()
            } finally {
                release(parked)
            }
        }
    }
    try {
        val reason = parked?.let { unusableReason(it, rebased, sizeChanged, refusal, reseed) }
        when {
            parked == null -> Unit
            reason != null -> {
                log("commit: adopted engine discarded (reason: $reason)")
                done()
            }
            else -> log("commit: adopted engine used" + if (rebased) " (rebased: layer re-seeded into it)" else "")
        }
        return paint(parked.takeIf { reason == null }, done)
    } finally {
        done()
    }
}

/** Why [parked] cannot paint the stroke, or null (re-seeding it first when [rebased]); a throw is a failure. */
private fun <E : Any> unusableReason(
    parked: E,
    rebased: Boolean,
    sizeChanged: Boolean,
    refusal: String?,
    reseed: (E) -> Boolean,
): String? = when {
    refusal != null -> refusal
    sizeChanged -> "resized"
    rebased && !runCatching { reseed(parked) }.getOrDefault(false) -> "reseed failed"
    else -> null
}

private const val NANOS_PER_MS_WAIT = 1_000_000L
