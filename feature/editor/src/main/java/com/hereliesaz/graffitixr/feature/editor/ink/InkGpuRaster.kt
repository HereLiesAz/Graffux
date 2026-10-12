package com.hereliesaz.graffitixr.feature.editor.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Renders onto Android's hardware (GPU, HWUI) canvas and hands the result back as a bitmap: how an
 * Ink stroke commits. Ink's look comes from its own mesh shader, which only runs through HWUI, so
 * the commit draws the layer and the stroke on a hardware canvas -- the same renderer the live
 * InProgressStrokesView preview uses, so committed pixels match what was shown -- and reads the
 * frame back once. No step rasterizes on the CPU.
 *
 * HardwareRenderer is API 29; the app's minSdk is 29, so this always runs there.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal object InkGpuRaster {

    /**
     * A [width] x [height] ARGB_8888 bitmap of what [draw] records, rendered by HWUI onto a
     * transparent surface. Null when the hardware render fails.
     */
    fun render(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap? {
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        val node = RenderNode("ink-commit").apply { setPosition(0, 0, width, height) }
        val renderer = HardwareRenderer()
        return try {
            val canvas = node.beginRecording(width, height)
            try {
                draw(canvas)
            } finally {
                node.endRecording()
            }
            renderer.isOpaque = false
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireLatestImage()?.use { image -> image.hardwareBuffer?.use(::softwareCopy) }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }

    /** [buffer]'s pixels as a mutable software bitmap (one GPU readback). */
    private fun softwareCopy(buffer: HardwareBuffer): Bitmap? =
        Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))?.let { hardware ->
            hardware.copy(Bitmap.Config.ARGB_8888, true).also { hardware.recycle() }
        }
}
