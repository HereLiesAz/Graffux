package com.hereliesaz.graffux

import android.content.Context
import android.widget.Toast
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController

/**
 * The one-time notice for a device below the hardware floor (Android 10 + Vulkan 1.1, the
 * manifest's `<uses-feature>`). Play filters such devices out, so this only reaches a sideloaded
 * install. There is no CPU renderer: the GPU stamp engine's init fails cleanly there and every
 * stroke fails (StrokeRenderException), so the notice says plainly that drawing is unavailable.
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
                "Drawing isn't available on this device: it needs Vulkan 1.1",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
