package com.hereliesaz.graffux

import android.app.Application
import com.hereliesaz.graffitixr.common.crash.CrashReporter
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Graffux's Application. Triggers Hilt code generation and loads the native libraries the shared
 * editor relies on (OpenCV + the graffitixr native bridge — the editor's Liquify tool bakes through
 * SlamManager). Deliberately lean: no AR/SLAM session bring-up, no co-op, no upload of anything
 * CrashReporter captures — its report stays local, in this app's own cache directory.
 */
@HiltAndroidApp
class GraffuxApplication : Application() {

    @Inject lateinit var securityProviderManager: SecurityProviderManager

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // The shared editor's Liquify tool bakes through the native bridge, so load before any edit.
        // Idempotent and safe on every process start.
        NativeLibLoader.loadAll()
        // Extension installs and trust-store refreshes go out over plain HttpURLConnection
        // (ExtensionRepository, EditorViewModel.installExtensionFromUrl) — this was built to patch an
        // outdated device TLS provider ahead of exactly that traffic, but nothing ever called it, so
        // every such request ran on whatever provider the device happened to ship. Async and
        // non-blocking; a failure here degrades to the device's own provider rather than crashing.
        securityProviderManager.installAsync(this)
        // Fully built and unit-tested, but never instantiated anywhere — a real crash left nothing
        // to diagnose why. Local only: writes cacheDir/last_crash.txt (PII-redacted) and otherwise
        // defers to the platform's default handler; nothing here uploads or transmits it anywhere.
        CrashReporter(this).initialize()
    }
}
