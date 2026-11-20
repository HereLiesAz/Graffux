package com.hereliesaz.graffitixr.nativebridge

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Runs a blocking teardown without ever blocking the main thread. [GpuStampEngine.destroy] needs the engine's
 * monitor, which a stroke worker holds across a readback wait (up to the first-use allowance on a cold device), so a
 * main-thread `destroy()` used to freeze input behind it. From the main thread the block is handed to [executor]
 * (a FIFO of one, so teardowns keep their order); from any other thread it runs inline, as before.
 */
internal class MainSafeRunner(
    private val isMain: () -> Boolean = ::onMainThread,
    private val executor: Executor = TEARDOWN_EXECUTOR,
) {
    /** True when [block] was handed off instead of run here. */
    fun run(block: () -> Unit): Boolean {
        if (!isMain()) {
            block()
            return false
        }
        executor.execute(block)
        return true
    }

    private companion object {
        val TEARDOWN_EXECUTOR: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "graffux-gpu-teardown").apply { isDaemon = true }
        }
    }
}

private fun onMainThread(): Boolean =
    runCatching { android.os.Looper.getMainLooper() === android.os.Looper.myLooper() }.getOrDefault(false)
