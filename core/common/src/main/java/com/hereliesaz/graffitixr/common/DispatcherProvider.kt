package com.hereliesaz.graffitixr.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher

    /**
     * For work that blocks on the GPU render thread (a stamp batch, a commit, a pre-warm: each
     * waits in `GpuRenderThread.call`). A slow GPU then parks these threads instead of
     * [default]'s, which are sized to the core count and shared with CPU-bound work (thumbnails,
     * project load). Defaults to [default], so a test provider keeps its single test dispatcher.
     */
    val gpuWait: CoroutineDispatcher get() = default
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined

    // IO's elastic pool is meant for blocking calls; the limit keeps GPU waiters from growing it
    // without bound. Every wait is on one render thread, so a few waiters already keep it fed.
    override val gpuWait: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(GPU_WAIT_PARALLELISM)

    private companion object {
        const val GPU_WAIT_PARALLELISM = 4
    }
}
