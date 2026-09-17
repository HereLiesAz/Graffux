package com.hereliesaz.graffitixr.feature.editor

import android.app.ActivityManager
import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.BrushPerformanceTier

/**
 * Small, deliberately boring device-capability policy for physical bristle cost.
 *
 * It uses Android's stable low-RAM signal and application memory class instead of GPU-vendor names:
 * vendor-specific thresholds belong in physical-device calibration, while this policy must remain
 * predictable on unknown hardware. The selected tier is frozen into each stroke's brush snapshot
 * before any dabs are generated, so later replay never re-queries the device.
 */
internal object BrushPerformanceTierResolver {
    fun resolve(context: Context): BrushPerformanceTier {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return BrushPerformanceTier.FULL
        return resolve(
            isLowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
        )
    }

    internal fun resolve(isLowRamDevice: Boolean, memoryClassMb: Int): BrushPerformanceTier = when {
        isLowRamDevice || memoryClassMb <= 256 -> BrushPerformanceTier.CONSTRAINED
        memoryClassMb <= 512 -> BrushPerformanceTier.BALANCED
        else -> BrushPerformanceTier.FULL
    }
}
