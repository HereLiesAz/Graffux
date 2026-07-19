package com.hereliesaz.graffixr

import android.app.Application
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * GraffiXR's Application. Triggers Hilt code generation and loads the native libraries the shared
 * editor relies on (OpenCV + the graffitixr native bridge — the editor's Liquify tool bakes through
 * SlamManager). Deliberately lean: no AR/SLAM session bring-up, no co-op, no crash-upload worker.
 */
@HiltAndroidApp
class GraffixrApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // The shared editor's Liquify tool bakes through the native bridge, so load before any edit.
        // Idempotent and safe on every process start.
        NativeLibLoader.loadAll()
    }
}
