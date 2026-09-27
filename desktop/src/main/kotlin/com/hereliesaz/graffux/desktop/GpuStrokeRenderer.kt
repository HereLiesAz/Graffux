package com.hereliesaz.graffux.desktop

import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuDabs
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuLibrary
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuStampEngine

/**
 * The desktop canvas's GPU path: the wgpu stamp engine (core/wgpu-engine, the same engine the
 * Android app offers as "wgpu") compositing a stroke's dabs with the same max-combine rule as the
 * CPU's [compositeTileParallel]/`RoundStampCompositor`, then read back into a straight-ARGB pixel
 * array for a `BufferedImage`/Skia bitmap. Zero-copy display is out of scope for now.
 *
 * One engine per canvas size, created lazily. [renderStroke] returns null whenever the GPU path is
 * unavailable -- the library did not load, no adapter with compute support exists (e.g. a machine
 * with no GPU driver at all), a call failed, or `-Dgraffux.gpu=false` -- and the caller then keeps
 * using the CPU compositor. A failed size is not retried, so a GPU-less machine pays for the probe
 * once, not per frame.
 *
 * Not thread-safe; the canvas calls it from its single gesture coroutine.
 */
class GpuStrokeRenderer {
    private var engine: WgpuStampEngine? = null
    private var failedSize: Pair<Int, Int>? = null
    private var readbackBuffer = ByteArray(0)

    /** What the canvas is painting with, for logs and the UI: adapter or reason for the CPU path. */
    var description: String = "CPU (GPU not probed yet)"
        private set

    /** Premultiplied RGBA of [base], the pre-stroke canvas; reuse it for every frame of a stroke. */
    fun strokeBase(baseArgb: IntArray): ByteArray = WgpuDabs.premultipliedRgba(baseArgb)

    /**
     * Composites [dabs] over [baseRgba] (from [strokeBase]) and returns the frame as straight ARGB,
     * or null to fall back to the CPU path.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun renderStroke(
        baseRgba: ByteArray,
        width: Int,
        height: Int,
        dabs: List<Dab>,
        colorArgb: Int,
        secondaryColorArgb: Int,
        colorSource: BrushColorSource,
        flow: Float,
    ): IntArray? {
        val e = engineFor(width, height) ?: return null
        if (!e.upload(baseRgba)) return fail("upload failed")
        if (dabs.isNotEmpty()) {
            val packed = WgpuDabs.resolvedRound(dabs, colorArgb, secondaryColorArgb, colorSource, flow)
            if (!e.stampDabs(packed, colorArgb, hardness = 1f)) return fail("stampDabs failed")
        }
        if (readbackBuffer.size != width * height * 4) readbackBuffer = ByteArray(width * height * 4)
        if (!e.readback(readbackBuffer)) return fail("readback failed")
        return WgpuDabs.straightArgb(readbackBuffer)
    }

    private fun engineFor(width: Int, height: Int): WgpuStampEngine? {
        if (!enabled) {
            description = "CPU (-Dgraffux.gpu=false)"
            return null
        }
        engine?.let { if (it.width == width && it.height == height) return it }
        if (failedSize == width to height) return null
        close()
        val created = WgpuStampEngine.create(width, height)
        if (created == null) {
            failedSize = width to height
            description = "CPU (${WgpuLibrary.failure ?: "no wgpu adapter with compute support"})"
            println("Graffux canvas: $description")
            return null
        }
        engine = created
        description = "GPU wgpu -- ${created.adapterDescription}"
        println("Graffux canvas: $description")
        return created
    }

    private fun fail(what: String): IntArray? {
        description = "CPU (wgpu $what)"
        println("Graffux canvas: $description")
        engine?.let { failedSize = it.width to it.height }
        close()
        return null
    }

    fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        val enabled: Boolean = System.getProperty("graffux.gpu")?.toBooleanStrictOrNull() ?: true
    }
}
