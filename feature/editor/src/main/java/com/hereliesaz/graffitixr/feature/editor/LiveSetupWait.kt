package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** The longest a commit waits for a superseded live setup (the document engine's own lease wait is 2 s). */
internal const val SUPERSEDED_SETUP_WAIT_MS = 2_500L

/**
 * Runs [replay] (a stroke's commit painted again from its recorded dabs) after the live engine
 * setup [setup] of the same stroke has finished.
 *
 * A stroke that ends before its live engine is ready leaves that setup running: it is building the
 * wgpu device (cold: 85-460 ms, and the document engine's lease is held until it is torn down). The
 * replay builds an engine of its own, so started at once it created a second device while the first
 * was still being created, and on some GPUs that attempt failed ("failed to paint (stamp)"), as did
 * the immediate retry, which met the same still-running setup. Waited for first, the replay starts
 * with the lease free and the device warm. Suspends the commit worker only: never the main thread,
 * and never the touch path, so it adds nothing to stroke start or teardown latency (#864).
 *
 * The wait is bounded by [maxWaitMs]; a setup that has not finished by then is logged through
 * [log] and the replay goes ahead as before. A null or finished [setup] adds no wait.
 */
internal suspend fun <T> replayAfterSetup(
    setup: Job?,
    maxWaitMs: Long = SUPERSEDED_SETUP_WAIT_MS,
    log: (String) -> Unit = {},
    replay: suspend () -> T,
): T {
    if (setup != null && !setup.isCompleted) {
        val t0 = System.nanoTime()
        val settled = withTimeoutOrNull(maxWaitMs) { setup.join() } != null
        val ms = (System.nanoTime() - t0) / NANOS_PER_MS_WAIT
        log(
            if (settled) "commit: waited ${ms}ms for the superseded live setup before replaying"
            else "commit: superseded live setup still running after ${ms}ms; replaying anyway",
        )
    }
    return replay()
}

private const val NANOS_PER_MS_WAIT = 1_000_000L
