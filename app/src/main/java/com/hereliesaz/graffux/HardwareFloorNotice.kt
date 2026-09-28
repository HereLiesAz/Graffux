package com.hereliesaz.graffux

import android.content.Context
import android.widget.Toast
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController

/**
 * The one-time notice for a device below the hardware floor (Android 10 + Vulkan 1.1, the
 * manifest's `<uses-feature>`). Play filters such devices out, so this only reaches a sideloaded
 * install. Nothing is disabled: the GPU stamp engine's init already fails cleanly there and the
 * stroke takes the existing CPU path. The notice just says why drawing may be slower.
 */
object HardwareFloorNotice {
    private const val PREFS = "hardware_floor"
    private const val KEY_SHOWN = "notice_shown"

    fun showOnce(context: Context) {
        if (GpuTuningController.meetsHardwareFloor(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        runCatching {
            Toast.makeText(
                context,
                "This device doesn't report Vulkan 1.1, so Graffux draws with its slower fallback path.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
