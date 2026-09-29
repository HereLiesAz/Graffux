package com.hereliesaz.graffux

import android.content.SharedPreferences

/**
 * Drops the GPU-engine preference keys that no longer mean anything, once per launch before any
 * engine is created. Same idea as core/data's `RetiredSettingsMigration`, for the `gpu_engine`
 * SharedPreferences file ([com.hereliesaz.graffitixr.nativebridge.GpuStampEngine.PREFS]).
 *
 * `backend` held Settings > GPU engine's choice of Vulkan, OpenGL ES or wgpu. The Vulkan and GLES
 * stamp engines were retired and wgpu is the only GPU backend, so the selector is gone. The stored
 * value is deliberately **not** read: there is nothing left to choose, and a device where wgpu
 * cannot start already draws on the CPU. Removing the key is idempotent (a second run finds nothing
 * and does not write) and leaves every other key -- multipass, direct display -- untouched.
 */
internal object RetiredGpuBackendMigration {

    /** Retired keys, by name. Only ever append: a key listed here is wiped on every launch. */
    val RETIRED_KEYS: List<String> = listOf("backend")

    /** True when [prefs] still holds a retired key. */
    fun shouldMigrate(prefs: SharedPreferences): Boolean = RETIRED_KEYS.any(prefs::contains)

    /** Removes every retired key from [prefs]; returns whether anything was removed. */
    fun migrate(prefs: SharedPreferences): Boolean {
        if (!shouldMigrate(prefs)) return false
        val editor = prefs.edit()
        RETIRED_KEYS.forEach { editor.remove(it) }
        editor.apply()
        return true
    }
}
