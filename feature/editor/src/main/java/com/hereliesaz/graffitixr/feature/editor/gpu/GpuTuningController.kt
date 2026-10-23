package com.hereliesaz.graffitixr.feature.editor.gpu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.core.content.pm.PackageInfoCompat
import com.hereliesaz.graffitixr.feature.editor.BrushTipMaskCache
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Process-wide owner of GPU tuning: calibration ([coordinator]), the thermal budget
 * ([budgetProvider], the multipass scheduler's hook), telemetry ([telemetry]) and the ADPF hint
 * session. Applies each [GpuTuning] to the native engines (workgroup size, timestamps) and each
 * [GpuBudget] to the wgpu resident-layer budget. One instance per process, from [get].
 */
class GpuTuningController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val framePeriodNanos: Long = framePeriodNanos(appContext)

    val budgetProvider = ThermalGpuBudgetProvider()
    val telemetry = GpuTelemetry { framePeriodNanos }
    private val thermal = ThermalMonitor(appContext) { snapshot ->
        budgetProvider.onThermal(snapshot)
        telemetry.onThermal(snapshot)
    }
    private val hints = RenderThreadHints(appContext, framePeriodNanos)
    // Built on the main thread (MainActivity.onStart): the prefs file and the package lookup open on
    // first use, which is calibration's, off the main thread.
    private val store = GpuTierStore(LazyKeyValueStore { SharedPreferencesKeyValueStore(appContext) }) {
        appVersion(appContext)
    }

    private val _tuning = MutableStateFlow(GpuTuning.INITIAL)

    /** What Settings shows: the tuning in force now. */
    val tuning: StateFlow<GpuTuning> = _tuning.asStateFlow()

    @Volatile var gpuInfo: GpuInfo = GpuInfo()
        private set

    val coordinator = CalibrationCoordinator(
        scope = scope,
        probe = EngineCalibrationProbe(),
        store = store,
        onTuning = ::applyTuning,
        soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER to Build.SOC_MODEL
        } else {
            "" to ""
        },
    )

    init {
        GpuStampEngine.passTimingSink = telemetry
        // Off the main thread: the first thermal reading is two PowerManager binder calls, and the
        // pipeline-cache setup loads the native libraries and touches the cache directory.
        scope.launch {
            telemetry.onThermal(thermal.sample())
            GpuStampEngine.setPipelineCacheDir(java.io.File(appContext.cacheDir, PIPELINE_CACHE_DIR))
        }
        scope.launch {
            // Collected, not read once: the tier can land or change, and thermal state moves,
            // mid-session. The multipass scheduler follows every change.
            budgetProvider.budget.collect { budget ->
                GpuStampEngine.setResidentBudget(budget.residentBudgetBytes)
                GpuStampEngine.multipassBudget = budget.toMultipassBudget()
            }
        }
        scope.launch { coordinator.state.collect { telemetry.paused = it == CalibrationState.RUNNING } }
        thermal.start(scope)
    }

    /**
     * `onTrimMemory`: the resident budget shrinks with the pressure ([MemoryPressureScaler]); off
     * screen the pooled engines (pure caches) go, and near this process's eviction the document
     * engine too (the next stroke reopens it and uploads its layer).
     */
    fun onTrimMemory(level: Int) {
        budgetProvider.onTrimMemory(level)
        // Brush tips and grain tiles are rebuilt on demand; under pressure they go first.
        if (level >= MemoryPressureScaler.TRIM_UI_HIDDEN) BrushTipMaskCache.clear()
        when {
            level >= MemoryPressureScaler.TRIM_MODERATE ->
                scope.launch { GpuStampEngine.trimPool(closeDocument = true) }
            level >= MemoryPressureScaler.TRIM_UI_HIDDEN -> GpuStampEngine.trimPool()
        }
    }

    /** The app is on screen again: resume calibration, thermal polling and the hint session. */
    fun onAppVisible() {
        budgetProvider.onMemoryRelieved()
        coordinator.onAppVisible()
        thermal.start(scope)
        scope.launch { hints.start() }
    }

    /** The app left the screen (Home, the system file picker): pause what can be paused. */
    fun onAppHidden() {
        coordinator.onAppHidden()
        thermal.stop()
        hints.stop()
    }

    /** Report block appended to the prediction-ranking "feel" section. */
    fun report(): String {
        val info = GpuInfo.parse(GpuStampEngine.lastGpuInfo).takeIf { it.isKnown } ?: gpuInfo
        // The build line lets a report be tied to a commit without guessing from timestamps.
        return "  build: $buildLabel\n" + telemetry.report(info, _tuning.value, thermal.sample())
    }

    // Lazy: a package-manager call, needed only when a report is written.
    private val buildLabel: String by lazy {
        runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")
    }

    private fun applyTuning(next: GpuTuning, info: GpuInfo) {
        gpuInfo = info
        GpuStampEngine.applyTuning(next.stampTile, next.timestamps)
        budgetProvider.setTier(next.tier)
        _tuning.value = next
    }

    companion object {
        @Volatile private var instance: GpuTuningController? = null

        /** Whether calibration is running now (the canvas pre-warm leaves it alone if so). */
        fun isCalibrating(): Boolean = instance?.coordinator?.isRunning == true

        /**
         * Before a canvas GPU engine is created (a stroke's engine, Smudge's): if calibration is
         * running, stop it and wait until its engine is gone (CalibrationCoordinator.stopForCanvas).
         * Blocking, for the non-suspending engine factories; they run on stroke workers, and it
         * returns at once when no calibration runs (the normal case), so it costs nothing there.
         * Never blocks the main thread: called there, it returns without waiting. An interrupt
         * while waiting is kept on the thread rather than swallowed.
         */
        fun stopCalibrationForCanvas() {
            val coordinator = instance?.coordinator ?: return
            if (!coordinator.isRunning) return
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                android.util.Log.w("GpuTuningController", "stopCalibrationForCanvas on the main thread; not waiting")
                return
            }
            try {
                runBlocking { coordinator.stopForCanvas() }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Calibration failing to stop must not fail the stroke; its engine still yields.
                android.util.Log.w("GpuTuningController", "Calibration did not stop cleanly", e)
            }
        }

        fun get(context: Context): GpuTuningController =
            instance ?: synchronized(this) {
                instance ?: GpuTuningController(context).also { instance = it }
            }

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val DEFAULT_HZ = 60f
        private const val PIPELINE_CACHE_DIR = "wgpu-pipelines"

        @Suppress("DEPRECATION")
        private fun framePeriodNanos(context: Context): Long {
            val hz = runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.defaultDisplay.refreshRate
            }.getOrNull()?.takeIf { it > 1f } ?: DEFAULT_HZ
            return (NANOS_PER_SECOND / hz).toLong()
        }

        private fun appVersion(context: Context): Long = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault(0L)

        /**
         * Whether the device advertises the hardware floor the manifest requires (Vulkan 1.1).
         * A sideloaded install can land below it; it then cannot draw (there is no CPU renderer).
         */
        fun meetsHardwareFloor(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return runCatching {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)
            }.getOrDefault(false)
        }

        /** `android.hardware.vulkan.version` value for Vulkan 1.1.0 (major << 22 | minor << 12). */
        const val VULKAN_1_1 = 0x401000
    }
}
