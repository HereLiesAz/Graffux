package com.hereliesaz.graffitixr.common.util

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object NativeLibLoader {
    private val isLoaded = AtomicBoolean(false)

    @Synchronized
    fun loadAll() {
        if (isLoaded.get()) return

        try {
            System.loadLibrary("graffitixr")
            Log.i("NativeLibLoader", "libgraffitixr.so loaded successfully.")
            isLoaded.set(true)
        } catch (e: UnsatisfiedLinkError) {
            val errorMsg = "CRITICAL: Native libraries could not be loaded!"
            Log.e("NativeLibLoader", errorMsg, e)
            throw RuntimeException("$errorMsg ${e.message}", e)
        }
    }
}
