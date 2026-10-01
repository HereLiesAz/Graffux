package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.wgpu.MultipassSettings
import android.app.Application
import com.hereliesaz.graffitixr.common.crash.Breadcrumbs
import com.hereliesaz.graffitixr.common.crash.CrashReporter
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.data.prediction.PredictionReportRepository
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.NativeCrashHandler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Graffux's Application. Triggers Hilt code generation and loads the native libraries the shared
 * editor relies on (OpenCV + the graffitixr native bridge — the editor's Liquify tool bakes through
 * SlamManager). Deliberately lean: no AR/SLAM session bring-up, no co-op. Crash reports stay local
 * in the cache directory unless a GitHub token was pasted in Settings (TEMPORARY, see
 * CrashIssueUploader).
 */
@HiltAndroidApp
class GraffuxApplication : Application() {

    @Inject lateinit var securityProviderManager: SecurityProviderManager
    @Inject lateinit var predictionReports: PredictionReportRepository

    override fun onCreate() {
        super.onCreate()
        // First, before anything can crash: the JVM handler (writes cacheDir/last_crash.txt with
        // the trace, thread and breadcrumbs synchronously, then chains to the platform handler),
        // and the breadcrumb mirror. The previous run's breadcrumbs are moved aside first, so a
        // native crash or ANR (no Kotlin runs on the way down) still has them on the next launch.
        CrashReporter(this).initialize()
        runCatching {
            val crumbs = File(cacheDir, CrashIssueUploader.BREADCRUMBS_FILE)
            val previous = File(cacheDir, CrashIssueUploader.PREVIOUS_BREADCRUMBS_FILE)
            if (crumbs.exists()) {
                previous.delete()
                crumbs.renameTo(previous)
            }
            Breadcrumbs.persistTo(crumbs)
        }
        Breadcrumbs.record("app start")
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // The shared editor's Liquify tool bakes through the native bridge, so load before any edit.
        // Idempotent and safe on every process start.
        NativeLibLoader.loadAll()
        // Graffux has no work without a project: when there is none, the editor asks for one with
        // the mandatory project dialog instead of silently creating "Untitled".
        EditorViewModel.projectGateEnabled = true
        // wgpu is the only GPU engine (the CPU draws where it can't start); the old backend
        // selector's stored choice is dropped once, before any engine is created.
        val gpuPrefs = getSharedPreferences(GpuStampEngine.PREFS, MODE_PRIVATE)
        RetiredGpuBackendMigration.migrate(gpuPrefs)
        // Direct display of the live stroke (Settings), off unless turned on.
        GpuStampEngine.DirectSurface.enabled = gpuPrefs.getBoolean(GpuStampEngine.DirectSurface.ENABLED_KEY, false)
        // Progressive rendering is the normal path. Its scheduler adapts to calibrated GPU/thermal
        // budget and pending work; no wall-clock "drying" duration is user-configurable.
        GpuStampEngine.multipass = MultipassSettings(
            enabled = gpuPrefs.getBoolean(GpuStampEngine.KEY_MULTIPASS, true),
            transitionMs = 0f,
        )
        // Extension installs and trust-store refreshes go out over plain HttpURLConnection
        // (ExtensionRepository, EditorViewModel.installExtensionFromUrl) — this was built to patch an
        // outdated device TLS provider ahead of exactly that traffic, but nothing ever called it, so
        // every such request ran on whatever provider the device happened to ship. Async and
        // non-blocking; a failure here degrades to the device's own provider rather than crashing.
        securityProviderManager.installAsync(this)
        // Signal-level (SIGSEGV/SIGABRT) backtraces the JVM handler above can't see -- the Vulkan
        // stamp engine and Ink predictor run native code on the drawing path. Needs loadAll() above.
        NativeCrashHandler.install(File(cacheDir, CrashIssueUploader.NATIVE_CRASH_FILE).path)
        // TEMPORARY: with a GitHub token pasted in Settings, file what the last run left behind.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { CrashIssueUploader(this@GraffuxApplication, predictionReports).uploadPending() }
            // Training data is strictly opt-in. Existing pending files stay local while consent is off.
            val trainingPrefs = getSharedPreferences(
                com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_PREFS,
                MODE_PRIVATE,
            )
            if (trainingPrefs.getBoolean(
                    com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_KEY,
                    false,
                )
            ) {
                runCatching { StrokeDataUploader(this@GraffuxApplication, predictionReports).uploadPending() }
            }
        }
    }
}
