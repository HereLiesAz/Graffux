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
 * at most [maxWaitMs]); null when there is none, or the setup is still running at the bound (logged
 * through [log]). Always ends the expectation, so a late setup destroys its engine. Suspends the
 * commit worker only: never the main thread, never the touch path (#864).
 */
internal suspend fun <E : Any> awaitSupersededEngine(
    engines: SupersededEngines<E>,
    setup: Job?,
    seed: Long,
    maxWaitMs: Long = SUPERSEDED_SETUP_WAIT_MS,
    log: (String) -> Unit = {},
): E? {
    if (setup != null && !setup.isCompleted) {
        val t0 = System.nanoTime()
        val settled = withTimeoutOrNull(maxWaitMs) { setup.join() } != null
        val ms = (System.nanoTime() - t0) / NANOS_PER_MS_WAIT
        log(
            if (settled) "commit: waited ${ms}ms for the superseded live setup's engine"
            else "commit: superseded live setup still running after ${ms}ms; painting on an engine of its own",
        )
    }
    return engines.claim(seed)
}

private const val NANOS_PER_MS_WAIT = 1_000_000L
