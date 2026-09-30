package com.hereliesaz.graffitixr.feature.editor.gpu

import com.hereliesaz.graffitixr.common.crash.Breadcrumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** The three calibration measurements, run in this order. */
enum class CalibrationStep { STAMP, READBACK, COMPOSITE }

/** What one attempt at a [CalibrationStep] produced. */
sealed interface StepOutcome {
    /** Stamp: dabs/ms. Readback: MB/s. Composite: ms. */
    data class Measured(val value: Double) : StepOutcome

    /** The GPU device/context went away mid-step (engine init or a call failed): redo the step. */
    data object ContextLost : StepOutcome
}

/**
 * The offscreen benchmark itself. The Android implementation ([EngineCalibrationProbe]) drives a
 * real [com.hereliesaz.graffitixr.nativebridge.GpuStampEngine]; tests use a fake. Implementations
 * must do their GPU work off the main thread and create a fresh engine per step, so a lost
 * context is recovered by simply running the step again.
 */
interface CalibrationProbe {
    /** The active engine's GPU description, or null when no GPU engine can start at all. */
    suspend fun identity(): GpuInfo?

    suspend fun measure(step: CalibrationStep): StepOutcome
}

/**
 * Pauses calibration while the app is not visible. [awaitRunning] suspends while paused; [epoch]
 * advances on every pause, so a step that overlapped one can tell and throw its number away (the
 * GPU context may have been lost or throttled meanwhile; never keep a number measured across it).
 */
class PauseGate {
    private val paused = MutableStateFlow(false)
    private val pauses = AtomicLong(0)

    val isPaused: Boolean get() = paused.value
    val epoch: Long get() = pauses.get()

    fun pause() {
        if (!paused.value) pauses.incrementAndGet()
        paused.value = true
    }

    fun resume() {
        paused.value = false
    }

    suspend fun awaitRunning() {
        paused.first { !it }
    }
}

/**
 * Runs the steps, skipping those already in [partial] (valid results kept from an interrupted
 * run), retrying a step on [StepOutcome.ContextLost] or when it overlapped a pause. Returns null
 * when a step keeps failing; a partial result is never turned into a tier.
 */
class CalibrationRunner(
    private val probe: CalibrationProbe,
    private val gate: PauseGate,
    private val maxAttemptsPerStep: Int = DEFAULT_ATTEMPTS,
) {
    suspend fun run(partial: MutableMap<CalibrationStep, Double>, backend: String): CalibrationResult? {
        for (step in CalibrationStep.entries) {
            if (step in partial) continue
            partial[step] = measureStep(step) ?: return null
        }
        return CalibrationResult(
            stampDabsPerMs = partial.getValue(CalibrationStep.STAMP),
            readbackMBps = partial.getValue(CalibrationStep.READBACK),
            compositeMs = partial.getValue(CalibrationStep.COMPOSITE),
            backend = backend,
        ).takeIf { it.isValid }
    }

    private suspend fun measureStep(step: CalibrationStep): Double? {
        var failures = 0
        while (failures < maxAttemptsPerStep) {
            gate.awaitRunning()
            val epoch = gate.epoch
            val outcome = probe.measure(step)
            if (gate.epoch != epoch || gate.isPaused) continue // overlapped a pause: redo, not a failure
            val value = (outcome as? StepOutcome.Measured)?.value
            if (value != null && value.isFinite() && value >= 0) return value
            failures += 1
        }
        return null
    }

    companion object {
        const val DEFAULT_ATTEMPTS = 3
    }
}

/** Where calibration stands, for Settings and tests. */
enum class CalibrationState { IDLE, RUNNING, DONE, FAILED }

/**
 * When calibration runs, and what the project dialog waits for.
 *
 * * [onProjectDialogShown]: start in the background unless a tier is stored for this GPU+driver
 *   and app version (then that tier is applied immediately and nothing runs). Showing the dialog
 *   again while a run is in flight does not restart it.
 * * [onAppHidden] / [onAppVisible]: the app left the screen (the system file picker, Home). The
 *   run pauses and resumes; a step that overlapped the pause is redone.
 * * [awaitForCreate] (Save) and [alongsideLoad] (Load): wait for the run at most [capMillis]
 *   (after the load, for Load). If it has not finished, the conservative default is applied and
 *   the run carries on; its tier is applied when it lands.
 * * [onUserDrawing]: a background re-run (Settings) is cancelled so it never competes with a stroke.
 *
 * Nothing is stored until a run completes, so process death simply means the next dialog retries.
 * Valid per-step numbers from an interrupted run are kept in memory for the next attempt.
 */
@Suppress("TooManyFunctions")
class CalibrationCoordinator(
    private val scope: CoroutineScope,
    private val probe: CalibrationProbe,
    private val store: GpuTierStore,
    private val onTuning: (GpuTuning, GpuInfo) -> Unit,
    /** `Build.SOC_MANUFACTURER` to `Build.SOC_MODEL` (API 31+), for the Tensor tag. */
    private val soc: Pair<String, String> = "" to "",
    private val capMillis: Long = DEFAULT_CAP_MS,
) {
    private val gate = PauseGate()
    private val partial = mutableMapOf<CalibrationStep, Double>()
    private var job: Job? = null
    private val _state = MutableStateFlow(CalibrationState.IDLE)
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    /** The tuning in force now. */
    @Volatile var tuning: GpuTuning = GpuTuning.INITIAL
        private set

    val isRunning: Boolean get() = job?.isActive == true

    fun onProjectDialogShown() = start(force = false)

    /** Settings > Re-run calibration: forget the stored tier and measure again now. */
    fun rerun() {
        store.clear()
        synchronized(partial) { partial.clear() }
        job?.cancel()
        start(force = true)
    }

    fun onAppHidden() = gate.pause()

    fun onAppVisible() = gate.resume()

    /** A stroke started: calibration must not compete with it. Retried at the next dialog. */
    fun onUserDrawing() {
        if (job?.isActive == true) {
            job?.cancel()
            _state.value = CalibrationState.IDLE
        }
    }

    /** Save/Create: returns once calibration is done or [capMillis] passed, whichever is first. */
    suspend fun awaitForCreate(): GpuTuning {
        val running = job
        if (running != null && running.isActive) {
            withTimeoutOrNull(capMillis) { running.join() }
        }
        return tuning
    }

    /** Load: runs [load] while calibration continues, then waits at most [capMillis] more. */
    suspend fun <T> alongsideLoad(load: suspend () -> T): T {
        val result = load()
        awaitForCreate()
        return result
    }

    /**
     * The canvas is about to get a GPU engine (project dialog > Save/Load, a stroke): stop any run
     * in flight and return only once its engine is destroyed, so calibration's wgpu engines and the
     * canvas's never exist at the same time. Cancellation cannot interrupt a native call, so this
     * waits for the step in progress (one short benchmark) to return and tear down in its `finally`;
     * it never waits for the whole run. Valid steps already measured are kept, and the next project
     * dialog resumes from them. No-op when nothing runs.
     */
    suspend fun stopForCanvas() {
        val running = job ?: return
        if (!running.isActive) return
        Breadcrumbs.record("calibration: stopped for the canvas")
        running.cancelAndJoin()
        if (_state.value == CalibrationState.RUNNING) _state.value = CalibrationState.IDLE
    }

    private fun start(force: Boolean) {
        if (job?.isActive == true) return
        _state.value = CalibrationState.RUNNING
        job = scope.launch {
            Breadcrumbs.record("calibration start")
            try {
                calibrate(force)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // A probe that throws is a failed run, never a crash: keep the default tier.
                runCatching { android.util.Log.w(TAG, "calibration failed", e) }
                _state.value = CalibrationState.FAILED
            } finally {
                Breadcrumbs.record("calibration end: ${_state.value}")
            }
        }
    }

    private suspend fun calibrate(force: Boolean) {
        val info = probe.identity()
        val stored = info?.takeIf { it.isKnown && !force }?.let { store.load(it.identityKey) }
        _state.value = when {
            // No GPU engine starts on this device: nothing to calibrate, the CPU path is used.
            info == null || !info.isKnown -> CalibrationState.FAILED
            stored != null -> {
                apply(GpuTuning.resolve(info, detect(info), stored.tier, stored.result), info)
                CalibrationState.DONE
            }
            else -> measure(info)
        }
    }

    private fun detect(info: GpuInfo) = GpuFamilyDetector.detect(info, soc.first, soc.second)

    private suspend fun measure(info: GpuInfo): CalibrationState {
        val detected = detect(info)
        // Until the run lands, apply what is known now (family knobs) on the conservative tier.
        if (!tuning.calibrated) {
            apply(GpuTuning.resolve(info, detected, GpuTierTable.default, result = null), info)
        }
        val snapshot = synchronized(partial) { partial.toMutableMap() }
        var result: CalibrationResult? = null
        try {
            result = CalibrationRunner(probe, gate).run(snapshot, info.engine)
        } finally {
            // Interrupted or failed: keep the steps that did complete (each is valid on its own)
            // for the next attempt. Completed: nothing left to keep.
            synchronized(partial) {
                partial.clear()
                if (result == null) partial.putAll(snapshot)
            }
        }
        val measured = result ?: return CalibrationState.FAILED
        val tier = GpuTierMapper.map(measured)
        store.save(info.identityKey, tier, measured)
        apply(GpuTuning.resolve(info, detected, tier, measured), info)
        return CalibrationState.DONE
    }

    private fun apply(next: GpuTuning, info: GpuInfo) {
        tuning = next
        onTuning(next, info)
    }

    companion object {
        /** How long Save/Load may wait for calibration beyond their own work. */
        const val DEFAULT_CAP_MS = 2_000L
        private const val TAG = "GpuCalibration"
    }
}
