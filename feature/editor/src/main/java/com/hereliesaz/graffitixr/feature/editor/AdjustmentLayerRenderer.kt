@file:Suppress(
    "MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LongParameterList", "NestedBlockDepth",
    "TooManyFunctions", "MaxLineLength",
)

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.util.Log
import com.hereliesaz.graffitixr.common.azphalt.wgpu.WgpuPhotoAdjuster
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustMath
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustParams
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import com.hereliesaz.graffitixr.common.photo.RetouchMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Keeps every photo adjustment layer's [Layer.bitmap] current: the composite of the layers below
 * it (in its own parent), adjusted by its [Layer.adjustment], times its mask.
 *
 * Two costs, kept apart so a slider drag only ever pays the cheap one:
 * - **The layers below changed** (a stroke under it, a reorder, an undo): composite them once on
 *   the CPU and upload the result to the GPU. Once per change, never per frame.
 * - **The settings changed** (a slider): one GPU compute pass and a readback of the composite
 *   ([WgpuPhotoAdjuster]); with no GPU, the CPU reference ([PhotoAdjustMath]) instead.
 *
 * [sync] is called with every new layer list; it works out which layers need which cost by
 * comparing keys, and queues them, newest request per layer winning. Renders run in order, bottom
 * layer first, on one background thread (the adjuster is not thread-safe, and a layer's render reads
 * the renders under it). Each result is published through [publish] on the caller's main dispatcher.
 *
 * Every pixel buffer is [width] x [height], the world size every layer is drawn into (the display),
 * so a render drawn as an ordinary layer lands exactly over what it adjusted.
 */
internal class AdjustmentLayerRenderer(
    private val scope: CoroutineScope,
    private val composite: (List<Layer>, Int, Int) -> Bitmap,
    private val publish: (layerId: String, bitmap: Bitmap) -> Unit,
    private val gpuFactory: (Int, Int) -> WgpuPhotoAdjuster? = { w, h -> WgpuPhotoAdjuster.create(w, h) },
    /** A layer's mask at its current revision (world-sized, one byte a pixel), or null for none. */
    private val maskOf: (Layer) -> ByteArray? = { null },
    /** Changes whenever a layer's mask bytes change in place (a Local stroke in progress). */
    private val maskGeneration: (String) -> Long = { 0L },
) {
    private val thread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "photo-adjust").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    private var width = 0
    private var height = 0
    private var gpu: WgpuPhotoAdjuster? = null
    private var gpuTried = false

    /** What each layer was last rendered from, and its buffers. Touched only on [thread]. */
    private class Slot {
        var sourceKey: Long = Long.MIN_VALUE
        var renderKey: Long = Long.MIN_VALUE
        var source: ByteArray? = null
        var maskKey: Long = Long.MIN_VALUE
        val outputs = arrayOfNulls<Bitmap>(2)
        var next = 0
    }

    private val slots = HashMap<String, Slot>()

    /** Which layer's source is on the GPU now. */
    private var uploadedFor: String? = null
    private var out: ByteArray? = null

    /** Requests not yet run, newest per layer. Guarded by itself. */
    private val pending = LinkedHashMap<String, Request>()

    private data class Request(val layer: Layer, val below: List<Layer>, val sourceKey: Long, val renderKey: Long)

    /** GPU (or CPU) time of the last settings render, in nanoseconds; for the timing log and tests. */
    @Volatile var lastRenderNanos: Long = 0
        private set

    /** Whether renders run on the GPU (false until the first render, and where wgpu is missing). */
    @Volatile var onGpu: Boolean = false
        private set

    /**
     * Queues a render for every adjustment layer in [layers] (bottom-first) whose layers below or
     * whose settings differ from what it was last rendered from. [compare] true renders nothing
     * (hold-to-compare hides them all anyway).
     */
    fun sync(layers: List<Layer>, worldWidth: Int, worldHeight: Int) {
        if (worldWidth <= 0 || worldHeight <= 0) return
        val requests = plan(layers).mapNotNull { (layer, below) ->
            val sourceKey = sourceKey(below)
            val renderKey = 31 * renderKey(layer, sourceKey) + maskGeneration(layer.id)
            Request(layer, below, sourceKey, renderKey)
        }
        val live = layers.filter { it.adjustment != null }.map { it.id }.toSet()
        synchronized(pending) {
            pending.keys.retainAll(live)
            requests.forEach { pending[it.layer.id] = it }
        }
        scope.launch(thread) {
            if (worldWidth != width || worldHeight != height) resize(worldWidth, worldHeight)
            slots.keys.retainAll(live)
        }
        kick()
    }

    private fun kick() {
        // Every kick queues a drain on the one render thread; a drain that finds nothing does nothing.
        scope.launch(thread) {
            while (true) {
                val next = synchronized(pending) {
                    val first = pending.entries.firstOrNull() ?: return@synchronized null
                    pending.remove(first.key)
                    first.value
                } ?: break
                runCatching { render(next) }.onFailure { Log.e(TAG, "adjustment render failed", it) }
            }
        }
    }

    private fun resize(w: Int, h: Int) {
        gpu?.close()
        gpu = null
        gpuTried = false
        width = w
        height = h
        slots.values.forEach { s -> s.sourceKey = Long.MIN_VALUE; s.renderKey = Long.MIN_VALUE; s.source = null }
        uploadedFor = null
        out = null
    }

    private fun adjuster(): WgpuPhotoAdjuster? {
        if (!gpuTried) {
            gpuTried = true
            gpu = runCatching { gpuFactory(width, height) }.getOrNull()
            onGpu = gpu != null
            Log.i(
                TAG,
                if (gpu != null) {
                    "adjustments on the GPU (${width}x$height)"
                } else {
                    "adjustments on the CPU: no wgpu adapter"
                },
            )
        }
        return gpu
    }

    private fun render(request: Request) {
        if (width <= 0) return
        val slot = slots.getOrPut(request.layer.id) { Slot() }
        if (slot.renderKey == request.renderKey && slot.sourceKey == request.sourceKey) return
        val adjustment = request.layer.adjustment ?: return
        val n = width * height
        if (slot.sourceKey != request.sourceKey || slot.source == null) {
            val below = composite(request.below, width, height)
            val bytes = slot.source?.takeIf { it.size == n * 4 } ?: ByteArray(n * 4)
            below.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            below.recycle()
            slot.source = bytes
            slot.sourceKey = request.sourceKey
            if (uploadedFor == request.layer.id) uploadedFor = null
        }
        val source = slot.source ?: return
        val outBytes = out?.takeIf { it.size == n * 4 } ?: ByteArray(n * 4).also { out = it }
        val mask = maskOf(request.layer)?.takeIf { adjustment.mask.enabled && it.size >= n }
        val started = System.nanoTime()
        if (adjustment.tool == PhotoTool.HEAL || adjustment.tool == PhotoTool.RED_EYE) {
            // Spot work: small and only on a change, so on the CPU (RetouchMath).
            retouch(adjustment, source, width, height, mask, outBytes)
            lastRenderNanos = System.nanoTime() - started
        } else {
            val params = PhotoAdjustParams.pack(adjustment, grainSeed = request.layer.id.hashCode(), maskOn = mask != null)
            val curves = PhotoAdjustParams.curvesTable(adjustment)
            val gpu = adjuster()
            val ok = if (gpu != null) {
                if (uploadedFor != request.layer.id) {
                    gpu.setSource(source)
                    uploadedFor = request.layer.id
                    slot.maskKey = Long.MIN_VALUE
                }
                val maskKey = maskGeneration(request.layer.id) * 31 + adjustment.mask.revision
                if (mask != null && slot.maskKey != maskKey) {
                    gpu.setMask(mask)
                    slot.maskKey = maskKey
                }
                gpu.setCurves(curves) && gpu.render(params, outBytes)
            } else {
                false
            }
            if (ok) {
                lastRenderNanos = gpu?.lastRenderNanos ?: 0
            } else {
                params[16] = width.toFloat()
                params[17] = height.toFloat()
                PhotoAdjustMath.render(source, width, height, params, curves, mask, outBytes)
                lastRenderNanos = System.nanoTime() - started
            }
        }
        // Two bitmaps in turn: the canvas may still be drawing the previous one.
        val target = slot.outputs[slot.next]?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { slot.outputs[slot.next] = it }
        slot.next = 1 - slot.next
        target.copyPixelsFromBuffer(ByteBuffer.wrap(outBytes))
        slot.renderKey = request.renderKey
        publish(request.layer.id, target)
    }

    /**
     * Renders [adjustment] over [below] (a [width] x [height] composite) now, on the calling
     * thread, for export: the export composites at its own size and must not wait on the queue.
     */
    fun renderNow(below: Bitmap, layer: Layer): Bitmap? {
        val adjustment = layer.adjustment ?: return null
        val w = below.width
        val h = below.height
        val src = ByteArray(w * h * 4)
        below.copyPixelsToBuffer(ByteBuffer.wrap(src))
        val outBytes = ByteArray(w * h * 4)
        // A mask at another size than this composite (a thumbnail): scaled to it.
        val mask = maskOf(layer)?.takeIf { adjustment.mask.enabled }?.let { m ->
            if (m.size == w * h) m else maskSize(m)?.let { (mw, mh) ->
                AdjustmentMasks.fromBitmap(AdjustmentMasks.toBitmap(m, mw, mh), w, h)
            }
        }
        if (adjustment.tool == PhotoTool.HEAL || adjustment.tool == PhotoTool.RED_EYE) {
            val sx = w.toFloat() / width.coerceAtLeast(1)
            val sy = h.toFloat() / height.coerceAtLeast(1)
            retouch(scaled(adjustment, sx, sy), src, w, h, mask, outBytes)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(outBytes)) }
        }
        val params = PhotoAdjustParams.pack(adjustment, grainSeed = layer.id.hashCode(), maskOn = mask != null)
        val curves = PhotoAdjustParams.curvesTable(adjustment)
        val ok = synchronized(exportLock) {
            // One adjuster kept for exports and thumbnails, apart from the live one (its thread).
            val g = exportGpu?.takeIf { it.width == w && it.height == h }
                ?: run {
                    exportGpu?.close()
                    runCatching { gpuFactory(w, h) }.getOrNull().also { exportGpu = it }
                }
            g != null && g.setSource(src) && g.setCurves(curves) && (mask == null || g.setMask(mask)) &&
                g.render(params, outBytes)
        }
        if (!ok) {
            params[16] = w.toFloat()
            params[17] = h.toFloat()
            PhotoAdjustMath.render(src, w, h, params, curves, mask, outBytes)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(outBytes))
        }
    }

    /** The world size a mask of [m]'s length was painted at: the live size when it matches. */
    private fun maskSize(m: ByteArray): Pair<Int, Int>? = (width to height).takeIf { width * height == m.size }

    /** Spot positions and sizes scaled from world to a composite [sx] x [sy] the world's size. */
    private fun scaled(a: PhotoAdjustment, sx: Float, sy: Float): PhotoAdjustment = if (sx == 1f && sy == 1f) a else a.copy(
        healSpots = a.healSpots.map {
            it.copy(x = it.x * sx, y = it.y * sy, radius = it.radius * sx, sourceX = it.sourceX * sx, sourceY = it.sourceY * sy)
        },
        redEyeSpots = a.redEyeSpots.map { it.copy(x = it.x * sx, y = it.y * sy, radius = it.radius * sx) },
    )

    /**
     * The Heal or Red-eye layer: transparent but for its spots ([RetouchMath]), times its mask.
     * Content-aware unless the layer's "heal.clone" setting is on.
     */
    private fun retouch(a: PhotoAdjustment, src: ByteArray, w: Int, h: Int, mask: ByteArray?, out: ByteArray) {
        java.util.Arrays.fill(out, 0)
        val contentAware = (a.values[HEAL_CLONE_KEY] ?: 0f) < 0.5f
        a.healSpots.forEach { RetouchMath.heal(src, w, h, it, contentAware, out) }
        a.redEyeSpots.forEach { RetouchMath.redEye(src, w, h, it, out) }
        if (mask != null) {
            for (i in 0 until w * h) {
                val m = (mask[i].toInt() and 0xFF).let { if (a.mask.inverted) 255 - it else it }
                if (m == 255) continue
                for (k in 0..3) out[i * 4 + k] = ((out[i * 4 + k].toInt() and 0xFF) * m / 255).toByte()
            }
        }
    }

    /**
     * A copy of the composite under [layerId] as last rendered (premultiplied RGBA8, the world's
     * size), or null before its first render: what Heal picks its source from.
     */
    suspend fun sourceOf(layerId: String): Pair<ByteArray, Pair<Int, Int>>? =
        kotlinx.coroutines.withContext(thread) { slots[layerId]?.source?.copyOf()?.let { it to (width to height) } }

    private val exportLock = Any()
    private var exportGpu: WgpuPhotoAdjuster? = null

    fun close() {
        synchronized(exportLock) {
            exportGpu?.close()
            exportGpu = null
        }
        scope.launch(thread) {
            gpu?.close()
            gpu = null
            slots.clear()
        }.invokeOnCompletion { thread.close() }
    }

    companion object {
        private const val TAG = "AdjustmentLayers"

        /** A Heal layer's mode, in its values: 1 = Clone (copy as is), absent = content-aware. */
        const val HEAL_CLONE_KEY = "heal.clone"

        /**
         * Each adjustment layer with the layers it adjusts: those before it in its own parent
         * (bottom-first), with everything inside them. Hidden adjustment layers are left out; they
         * draw nothing.
         */
        fun plan(layers: List<Layer>): List<Pair<Layer, List<Layer>>> {
            val byId = layers.associateBy { it.id }
            fun ancestorIn(l: Layer, ids: Set<String>): Boolean {
                var p = l.parentId
                val seen = HashSet<String>()
                while (p != null && seen.add(p)) {
                    if (p in ids) return true
                    p = byId[p]?.parentId
                }
                return false
            }
            return layers.mapIndexedNotNull { index, layer ->
                if (layer.adjustment == null || !layer.isVisible) return@mapIndexedNotNull null
                val siblings = layers.subList(0, index).filter { it.parentId == layer.parentId }.map { it.id }.toSet()
                val below = layers.subList(0, index).filter { it.id in siblings || ancestorIn(it, siblings) }
                    .map { if (it.parentId == layer.parentId) it.copy(parentId = null) else it }
                layer to below
            }
        }

        /** Changes whenever anything that draws the [below] layers changes, pixels included. */
        fun sourceKey(below: List<Layer>): Long {
            var h = 1125899906842597L
            for (l in below) {
                val b = l.bitmap
                h = 31 * h + l.copy(bitmap = null, heightMap = null).hashCode()
                h = 31 * h + (if (b == null || b.isRecycled) 0 else System.identityHashCode(b))
                h = 31 * h + (if (b == null || b.isRecycled) 0 else b.generationId)
            }
            return h
        }

        /** Changes with the layer's own settings or its source. */
        fun renderKey(layer: Layer, sourceKey: Long): Long =
            31 * sourceKey + (layer.adjustment?.hashCode() ?: 0)
    }
}
