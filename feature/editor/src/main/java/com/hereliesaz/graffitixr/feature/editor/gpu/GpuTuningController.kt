package com.hereliesaz.graffitixr.feature.editor.gpu

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.core.content.pm.PackageInfoCompat
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
    private val store = GpuTierStore(SharedPreferencesKeyValueStore(appContext), appVersion(appContext))

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
        telemetry.onThermal(thermal.sample())
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

    /** The app is on screen again: resume calibration, thermal polling and the hint session. */
    fun onAppVisible() {
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

    private val buildLabel: String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("?")

    private fun applyTuning(next: GpuTuning, info: GpuInfo) {
        gpuInfo = info
        GpuStampEngine.applyTuning(next.stampTile, next.timestamps)
        budgetProvider.setTier(next.tier)
        _tuning.value = next
    }

    companion object {
        @Volatile private var instance: GpuTuningController? = null

        /**
         * Before a canvas GPU engine is created (a stroke's engine, Smudge's): if calibration is
         * running, stop it and wait until its engine is gone (CalibrationCoordinator.stopForCanvas).
         * Blocking, for the non-suspending engine factories; they run on stroke workers, and it
         * returns at once when no calibration runs (the normal case), so it costs nothing there.
         */
        fun stopCalibrationForCanvas() {
            val coordinator = instance?.coordinator ?: return
            if (!coordinator.isRunning) return
            runCatching { runBlocking { coordinator.stopForCanvas() } }
        }

        fun get(context: Context): GpuTuningController =
            instance ?: synchronized(this) {
                instance ?: GpuTuningController(context).also { instance = it }
            }

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val DEFAULT_HZ = 60f

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
         * A sideloaded install can land below it; the app then runs on the existing fallbacks.
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
