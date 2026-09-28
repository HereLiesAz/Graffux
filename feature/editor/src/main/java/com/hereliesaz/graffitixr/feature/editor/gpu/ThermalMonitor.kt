package com.hereliesaz.graffitixr.feature.editor.gpu

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.hereliesaz.graffitixr.nativebridge.GpuRenderThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * PowerManager thermal status (API 29+, pushed by a listener) and forecast headroom (API 30+,
 * polled every [POLL_MS]; Android documents that polling more than about once a second may
 * return NaN). Each change goes to [onChange]. Below API 29 nothing is reported and the budget
 * stays unscaled. Every platform call is guarded: a failure means "unknown", never a crash.
 */
class ThermalMonitor(
    context: Context,
    private val onChange: (ThermalSnapshot) -> Unit,
) {
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: Any? = null
    private var poller: Job? = null

    @Volatile var latest: ThermalSnapshot = ThermalSnapshot()
        private set

    /** A fresh reading now (status and headroom). */
    fun sample(): ThermalSnapshot {
        val pm = power ?: return ThermalSnapshot()
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm.currentThermalStatus }.getOrDefault(ThermalSnapshot.STATUS_UNKNOWN)
        } else {
            ThermalSnapshot.STATUS_UNKNOWN
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm.getThermalHeadroom(FORECAST_SECONDS) }.getOrDefault(Float.NaN)
        } else {
            Float.NaN
        }
        return ThermalSnapshot(status, headroom).also { publish(it) }
    }

    fun start(scope: CoroutineScope) {
        val pm = power
        if (poller != null || pm == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) addStatusListener(pm)
        poller = scope.launch {
            while (isActive) {
                sample()
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        poller?.cancel()
        poller = null
        val pm = power ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) removeStatusListener(pm)
    }

    @Synchronized
    private fun publish(snapshot: ThermalSnapshot) {
        if (snapshot == latest) return
        latest = snapshot
        runCatching { onChange(snapshot) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun addStatusListener(pm: PowerManager) {
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            publish(latest.copy(status = status))
        }
        val direct = Executor { it.run() }
        if (runCatching { pm.addThermalStatusListener(direct, l) }.isSuccess) listener = l
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun removeStatusListener(pm: PowerManager) {
        val l = listener as? PowerManager.OnThermalStatusChangedListener ?: return
        runCatching { pm.removeThermalStatusListener(l) }
        listener = null
    }

    private companion object {
        const val FORECAST_SECONDS = 10
        const val POLL_MS = 10_000L
    }
}

/**
 * ADPF performance hints for the GPU render thread ([GpuRenderThread], where every wgpu call runs):
 * a `PerformanceHintManager` session (API 31+) targeting one display frame, fed the duration of
 * each unit of render-thread work. Lets the system raise clocks before a stroke misses frames
 * rather than after. Vulkan/GLES strokes run on the editor's coroutine workers, not this thread,
 * so they get no session. No-op below API 31, when the thread has not started yet, or when the
 * platform returns no session.
 */
class RenderThreadHints(context: Context, private val targetFrameNanos: Long) {
    private val appContext = context.applicationContext
    private var session: Any? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || session != null) return
        // The render thread records its tid when it starts; make sure it has.
        runCatching { GpuRenderThread.flush() }
        val created = GpuRenderThread.tid.takeIf { it > 0 }?.let { createSession(it) } ?: return
        session = created
        GpuRenderThread.workObserver = { nanos -> report(created, nanos) }
    }

    fun stop() {
        GpuRenderThread.workObserver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) closeSession()
        session = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createSession(tid: Int): PerformanceHintManager.Session? {
        val manager = appContext.getSystemService(PerformanceHintManager::class.java) ?: return null
        return runCatching { manager.createHintSession(intArrayOf(tid), targetFrameNanos) }.getOrNull()
    }

    private fun report(session: Any, nanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && nanos > 0) {
            (session as? PerformanceHintManager.Session)?.let { s ->
                runCatching { s.reportActualWorkDuration(nanos) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun closeSession() {
        (session as? PerformanceHintManager.Session)?.let { runCatching { it.close() } }
    }
}
