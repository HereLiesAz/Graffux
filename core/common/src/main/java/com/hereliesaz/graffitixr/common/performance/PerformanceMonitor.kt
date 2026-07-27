package com.hereliesaz.graffitixr.common.performance

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Utility for monitoring application performance (FPS, memory, latency).
 */
object PerformanceMonitor {

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _frameTimeMs = MutableStateFlow(0f)
    val frameTimeMs: StateFlow<Float> = _frameTimeMs.asStateFlow()

    private val _memoryUsageMb = MutableStateFlow(0L)
    val memoryUsageMb: StateFlow<Long> = _memoryUsageMb.asStateFlow()

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L

    // lastFrameTime/frameCount/lastFpsUpdateTime are plain fields read-then-written across several
    // statements; if onFrame() is ever called from more than one thread (e.g. an AR render thread
    // and a Compose measure/recomposition pass) concurrently, those reads/writes can interleave and
    // produce garbled fps/frame-time readings. Not a crash, but silently wrong diagnostics —
    // @Synchronized is cheap at this call frequency (at most one call per rendered frame).
    @Synchronized
    fun onFrame() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastFrameTime > 0) {
            val frameDuration = (now - lastFrameTime) / 1_000_000f
            _frameTimeMs.value = frameDuration
        }
        // Start the FPS window on the first frame; otherwise lastFpsUpdateTime == 0 vs a large
        // since-boot `now` makes the first comparison true immediately and reports a bogus fps=1.
        if (lastFpsUpdateTime == 0L) lastFpsUpdateTime = now
        lastFrameTime = now

        frameCount++
        if (now - lastFpsUpdateTime >= 1_000_000_000L) { // Update every second
            _fps.value = frameCount.toFloat()
            frameCount = 0
            lastFpsUpdateTime = now
            updateMemoryUsage()
        }
    }

    private fun updateMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        _memoryUsageMb.value = usedMemory
    }
}
