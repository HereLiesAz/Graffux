package com.hereliesaz.graffux

import android.app.Application
import com.hereliesaz.graffitixr.common.crash.CrashReporter
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.data.prediction.PredictionReportRepository
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.LiveStrokeOverlay
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
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // The shared editor's Liquify tool bakes through the native bridge, so load before any edit.
        // Idempotent and safe on every process start.
        NativeLibLoader.loadAll()
        // GPU backend chosen in Settings (Vulkan / OpenGL ES) for every stamp engine created from now on.
        val gpuPrefs = getSharedPreferences(GpuStampEngine.Backend.PREFS, MODE_PRIVATE)
        GpuStampEngine.Backend.preferred =
            GpuStampEngine.Backend.fromLabel(gpuPrefs.getString(GpuStampEngine.Backend.KEY, null))
        // Direct display of the live stroke (Settings), off unless turned on.
        LiveStrokeOverlay.enabled = gpuPrefs.getBoolean(LiveStrokeOverlay.ENABLED_KEY, false)
        // Extension installs and trust-store refreshes go out over plain HttpURLConnection
        // (ExtensionRepository, EditorViewModel.installExtensionFromUrl) — this was built to patch an
        // outdated device TLS provider ahead of exactly that traffic, but nothing ever called it, so
        // every such request ran on whatever provider the device happened to ship. Async and
        // non-blocking; a failure here degrades to the device's own provider rather than crashing.
        securityProviderManager.installAsync(this)
        // Fully built and unit-tested, but never instantiated anywhere — a real crash left nothing
        // to diagnose why. Writes cacheDir/last_crash.txt (PII-redacted) and otherwise defers to the
        // platform's default handler. Uploaded only by CrashIssueUploader, only with a token.
        CrashReporter(this).initialize()
        // Signal-level (SIGSEGV/SIGABRT) backtraces the JVM handler above can't see -- the Vulkan
        // stamp engine and Ink predictor run native code on the drawing path. Needs loadAll() above.
        NativeCrashHandler.install(File(cacheDir, CrashIssueUploader.NATIVE_CRASH_FILE).path)
        // TEMPORARY: with a GitHub token pasted in Settings, file what the last run left behind.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { CrashIssueUploader(this@GraffuxApplication, predictionReports).uploadPending() }
            // Stroke-model training data from earlier sessions (Settings → Record strokes).
            runCatching { StrokeDataUploader(this@GraffuxApplication, predictionReports).uploadPending() }
        }
    }
}
