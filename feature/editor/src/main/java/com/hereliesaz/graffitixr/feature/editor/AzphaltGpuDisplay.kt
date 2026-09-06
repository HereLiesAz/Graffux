package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import com.hereliesaz.graffitixr.nativebridge.VulkanStampEngine

/** Owns the Java-side reference used to display the Vulkan layer image without readback. */
internal class AzphaltGpuDisplay private constructor(
    val bitmap: Bitmap,
    private val hardwareBuffer: HardwareBuffer,
) : AutoCloseable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
        hardwareBuffer.close()
    }

    companion object {
        fun tryCreate(engine: VulkanStampEngine): AzphaltGpuDisplay? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val buffer = engine.getHardwareBuffer() ?: return null
            val bitmap = try {
                Bitmap.wrapHardwareBuffer(
                    buffer,
                    ColorSpace.get(ColorSpace.Named.SRGB),
                )
            } catch (_: Throwable) {
                null
            }
            if (bitmap == null) {
                buffer.close()
                return null
            }
            return AzphaltGpuDisplay(bitmap, buffer)
        }
    }
}
