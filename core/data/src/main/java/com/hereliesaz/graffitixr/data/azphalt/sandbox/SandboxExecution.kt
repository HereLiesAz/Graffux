package com.hereliesaz.graffitixr.data.azphalt.sandbox

import java.util.concurrent.TimeoutException

/**
 * How long a single sandboxed extension invocation ([JsSandbox.eval] / [WasmSandbox.run]) is
 * allowed to run before it's forcibly cut off. Neither sandbox bounded guest execution time at
 * all before this: an extension whose code was `(loop (br 0))` — or the JS equivalent — pinned a
 * thread at 100% forever, with no way to cancel it (a blocking, non-suspending call inside a
 * coroutine can't be cancelled by cancelling the coroutine). Generous enough for legitimate heavy
 * work (a filter over a large canvas), short enough that a runaway extension can't hang the app.
 */
internal const val SANDBOX_EXECUTION_TIMEOUT_MS = 15_000L

/**
 * Runs [block] on a dedicated worker thread and interrupts *that thread* — never the caller's —
 * if it hasn't finished within [timeoutMs]. Chicory's interpreter checks
 * `Thread.currentThread().isInterrupted()` while executing guest WASM/JS bytecode and throws a
 * clean `ChicoryException("Thread interrupted")` when it sees it (see
 * `InterpreterMachine`/Chicory's own `InterruptionTest`), so this is the runtime's own supported
 * cancellation mechanism, not a hack. Using a throwaway [Thread] rather than interrupting the
 * calling coroutine's own thread is deliberate: `dispatchers.io`'s pool reuses threads, and
 * interrupting one out from under a coroutine dispatcher risks corrupting unrelated work sharing
 * that pool. Rethrows whatever [block] threw, or [TimeoutException] if it had to be cut off.
 */
internal fun <T> runSandboxBounded(timeoutMs: Long = SANDBOX_EXECUTION_TIMEOUT_MS, block: () -> T): T {
    var result: T? = null
    var thrown: Throwable? = null
    val worker = Thread({
        try {
            result = block()
        } catch (t: Throwable) {
            thrown = t
        }
    }, "azphalt-sandbox-exec")
    worker.isDaemon = true
    worker.start()
    worker.join(timeoutMs)
    if (worker.isAlive) {
        worker.interrupt()
        // A short grace period for Chicory to actually unwind after the interrupt lands — the
        // check happens between bytecode steps, not instantly, and a host call the guest is
        // blocked in (e.g. one of the capability functions) needs to return first too.
        worker.join(1_000L)
        throw TimeoutException("Extension execution exceeded ${timeoutMs}ms and was interrupted")
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
