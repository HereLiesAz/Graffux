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
 * The canvas layer stays resident on the GPU between strokes (core/wgpu-engine's resident layers):
 * [beginStroke] with the same `contentKey` (the committed image object) that [commitStroke] last
 * recorded binds the resident copy instead of uploading the whole canvas, and every frame reads
 * back and converts only the rectangle the engine reports as changed. Any other key -- undo/redo,
 * a resize, a CPU-rendered stroke -- misses and uploads, exactly as before. Committed images are
 * never mutated in place (CanvasState swaps whole images), so object identity is a safe key.
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

    // Resident layer state: the bind session of the current stroke (0 = not resident, plain
    // upload path), what the resident copy holds, and whether the last frame came from the GPU.
    private var session = 0L
    private var residentContent: Any? = null
    private var residentGeneration = 0L
    private var nextGeneration = 1L
    private var lastFrameFromGpu = false

    /** Strokes that started on the resident copy vs. with a whole-canvas upload (diagnostics, tests). */
    var residentHits = 0
        private set
    var residentUploads = 0
        private set

    /** What the canvas is painting with, for logs: the adapter, or why it is on the CPU. */
    var description: String = "CPU (GPU not probed yet)"
        private set

    /**
     * Starts a stroke over [baseArgb] (straight ARGB, the pre-stroke canvas). [contentKey] names
     * those pixels (the committed image object); null disables residency for this stroke. False =
     * use the CPU.
     */
    fun beginStroke(baseArgb: IntArray, width: Int, height: Int, contentKey: Any? = null): Boolean {
        val e = engineFor(width, height) ?: return false
        val bytes = baseArgb.size * BYTES_PER_PIXEL
        if (baseRgba.size != bytes) baseRgba = ByteArray(bytes)
        if (readback.size != bytes) readback = ByteArray(bytes)
        WgpuDabs.premultipliedRgba(baseArgb, baseRgba)
        frame = baseArgb.copyOf()
        touchedRows = IntRange.EMPTY
        lastFrameFromGpu = false
        session = if (contentKey == null) {
            0L
        } else {
            startResident(e, contentKey)
        }
        // Resident: the engine already holds the base and nothing is dirty, so the readback buffer
        // only has to hold the same pixels, and the frame starts as what a whole-layer readback
        // would have converted (straight -> premultiplied -> straight is not the identity at low
        // alpha, and frames must match the upload path exactly). CPU only; nothing crosses from the
        // GPU. Otherwise the first frame uploads, as it always did.
        if (session != 0L) {
            baseRgba.copyInto(readback)
            WgpuDabs.straightArgb(baseRgba, frame)
        }
        firstFrame = session == 0L
        return true
    }

    private fun startResident(e: WgpuStampEngine, contentKey: Any): Long {
        if (contentKey === residentContent) {
            val bound = e.bindLayer(LAYER_KEY, residentGeneration)
            if (bound != 0L) {
                residentHits++
                return bound
            }
        }
        residentContent = null
        val generation = nextGeneration++
        val uploaded = e.uploadLayer(LAYER_KEY, generation, baseRgba)
        if (uploaded != 0L) {
            residentUploads++
            residentContent = contentKey
            residentGeneration = generation
        }
        return uploaded
    }

    /**
     * The stroke just ended and [contentKey] (a new image object) now holds the last frame
     * [renderStroke] returned. Makes the resident copy match it exactly: the rows the stroke
     * touched are refreshed from those pixels re-premultiplied, since straight ARGB does not always
     * round-trip to the same premultiplied bytes at low alpha. If the stroke did not end on a GPU
     * frame, the resident copy is simply forgotten.
     */
    fun commitStroke(contentKey: Any) {
        val e = engine
        val s = session
        session = 0L
        residentContent = null
        if (e == null || s == 0L || !lastFrameFromGpu) return
        WgpuDabs.premultipliedRgba(frame, baseRgba)
        val generation = nextGeneration++
        if (e.refreshLayer(LAYER_KEY, s, generation, baseRgba, WgpuStampEngine.PixelRect.EMPTY)) {
            residentContent = contentKey
            residentGeneration = generation
        }
    }

    /** Forgets the resident canvas (e.g. the document was replaced). */
    fun invalidate() {
        residentContent = null
        session = 0L
        engine?.invalidateAllLayers()
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
        val copied = e.readbackRect(readback) ?: return fail("readback failed")
        if (firstFrame) {
            WgpuDabs.straightArgb(readback, frame, 0, e.width * e.height)
        } else if (!copied.isEmpty) {
            // Only the rectangle the engine reports changed (restored rows + this frame's dabs).
            for (row in copied.y until copied.y + copied.height) {
                val start = row * e.width + copied.x
                WgpuDabs.straightArgb(readback, frame, start, start + copied.width)
            }
        }
        firstFrame = false
        touchedRows = stamped
        lastFrameFromGpu = true
        return frame
    }

    private fun rowsOf(dabs: List<Dab>, height: Int): IntRange {
        if (dabs.isEmpty()) return IntRange.EMPTY
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (d in dabs) {
            val r = max(d.radius, MIN_DAB_RADIUS)
            top = minOf(top, d.y - r)
            bottom = maxOf(bottom, d.y + r)
        }
        // Same bounds as the engine's dispatch region (floor / ceil + 1), clamped to the layer.
        val first = floor(top).toInt().coerceAtLeast(0)
        val last = (ceil(bottom).toInt()).coerceAtMost(height - 1)
        return if (first > last) IntRange.EMPTY else first..last
    }

    private fun engineFor(width: Int, height: Int): WgpuStampEngine? {
        val current = engine?.takeIf { it.width == width && it.height == height }
        return when {
            !enabled -> {
                description = "CPU (-Dgraffux.gpu=false)"
                null
            }
            current != null -> current
            failedSize == width to height -> null
            else -> createEngine(width, height)
        }
    }

    private fun createEngine(width: Int, height: Int): WgpuStampEngine? {
        close()
        val created = WgpuStampEngine.create(width, height)
        engine = created
        if (created == null) failedSize = width to height
        description = if (created == null) {
            "CPU (${WgpuLibrary.failure ?: "no wgpu adapter with compute support"})"
        } else {
            "GPU wgpu -- ${created.adapterDescription}"
        }
        println("Graffux canvas: $description")
        return created
    }

    private fun fail(what: String): IntArray? {
        description = "CPU (wgpu $what)"
        println("Graffux canvas: $description")
        engine?.let { failedSize = it.width to it.height }
        close()
        lastFrameFromGpu = false
        return null
    }

    fun close() {
        engine?.close()
        engine = null
        session = 0L
        residentContent = null
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4

        /** The desktop canvas is one layer. */
        const val LAYER_KEY = 1L

        /** The engines' own minimum dab radius (StampEngine.h dabRegion / stamp.comp). */
        const val MIN_DAB_RADIUS = 0.5f

        val enabled: Boolean = System.getProperty("graffux.gpu")?.toBooleanStrictOrNull() ?: true
    }
}
