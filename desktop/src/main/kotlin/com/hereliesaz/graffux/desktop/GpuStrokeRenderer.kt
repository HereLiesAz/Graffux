package com.hereliesaz.graffux.desktop

import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuDabs
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuLibrary
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuStampEngine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The desktop canvas's GPU path: the wgpu stamp engine (core/wgpu-engine, the same engine the
 * Android app offers as "wgpu") compositing a stroke's dabs with the same max-combine rule as the
 * CPU's [compositeTileParallel]/`RoundStampCompositor`, then read back into a straight-ARGB pixel
 * array for a `BufferedImage`/Skia bitmap. Zero-copy display is out of scope for now.
 *
 * A stroke is re-rendered from its pre-stroke base every frame (the dab list is not prefix-stable:
 * taper depends on the stroke's total length), but only the rows the previous frame touched are
 * restored, read back and converted, so a frame costs the stroke's extent, not the canvas's.
 *
 * One engine per canvas size, created lazily. [beginStroke]/[renderStroke] return false/null
 * whenever the GPU path is unavailable -- the library did not load, no adapter with compute support
 * exists (e.g. no GPU driver at all), a call failed, or `-Dgraffux.gpu=false` -- and the caller then
 * keeps using the CPU compositor. A failed size is not retried, so a GPU-less machine pays for the
 * probe once, not per frame. Not thread-safe; the canvas calls it from its one gesture coroutine.
 */
class GpuStrokeRenderer {
    private var engine: WgpuStampEngine? = null
    private var failedSize: Pair<Int, Int>? = null

    private var baseRgba = ByteArray(0)
    private var readback = ByteArray(0)
    private var frame = IntArray(0)
    private var firstFrame = true
    private var touchedRows: IntRange = IntRange.EMPTY

    /** What the canvas is painting with, for logs: the adapter, or why it is on the CPU. */
    var description: String = "CPU (GPU not probed yet)"
        private set

    /** Starts a stroke over [baseArgb] (straight ARGB, the pre-stroke canvas). False = use the CPU. */
    fun beginStroke(baseArgb: IntArray, width: Int, height: Int): Boolean {
        if (engineFor(width, height) == null) return false
        baseRgba = WgpuDabs.premultipliedRgba(baseArgb, if (baseRgba.size == baseArgb.size * 4) baseRgba else ByteArray(baseArgb.size * 4))
        if (readback.size != baseRgba.size) readback = ByteArray(baseRgba.size)
        frame = baseArgb.copyOf()
        firstFrame = true
        touchedRows = IntRange.EMPTY
        return true
    }

    /**
     * The whole stroke so far ([dabs]) over the base from [beginStroke], as straight ARGB -- the
     * renderer's own buffer, valid until the next call. Null = fall back to the CPU path.
     */
    @Suppress("ReturnCount")
    fun renderStroke(
        dabs: List<Dab>,
        colorArgb: Int,
        secondaryColorArgb: Int,
        colorSource: BrushColorSource,
        flow: Float,
    ): IntArray? {
        val e = engine ?: return null
        val restore = touchedRows
        val restored = if (firstFrame) {
            e.upload(baseRgba)
        } else {
            restore.isEmpty() || e.uploadRows(baseRgba, restore.first, restore.last - restore.first + 1)
        }
        if (!restored) return fail("upload failed")
        val stamped = rowsOf(dabs, e.height)
        if (!stamped.isEmpty()) {
            val packed = WgpuDabs.resolvedRound(dabs, colorArgb, secondaryColorArgb, colorSource, flow)
            if (!e.stampDabs(packed, colorArgb, hardness = 1f)) return fail("stampDabs failed")
        }
        if (!e.readback(readback)) return fail("readback failed")
        val convert = if (firstFrame) 0 until e.height else union(restore, stamped)
        if (!convert.isEmpty()) {
            WgpuDabs.straightArgb(readback, frame, convert.first * e.width, (convert.last + 1) * e.width)
        }
        firstFrame = false
        touchedRows = stamped
        return frame
    }

    private fun rowsOf(dabs: List<Dab>, height: Int): IntRange {
        if (dabs.isEmpty()) return IntRange.EMPTY
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (d in dabs) {
            val r = max(d.radius, 0.5f)
            top = minOf(top, d.y - r)
            bottom = maxOf(bottom, d.y + r)
        }
        // Same bounds as the engine's dispatch region (floor / ceil + 1), clamped to the layer.
        val first = floor(top).toInt().coerceAtLeast(0)
        val last = (ceil(bottom).toInt()).coerceAtMost(height - 1)
        return if (first > last) IntRange.EMPTY else first..last
    }

    private fun union(a: IntRange, b: IntRange): IntRange = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> minOf(a.first, b.first)..maxOf(a.last, b.last)
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
