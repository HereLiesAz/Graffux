package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.LiveStroke
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A transient, zoom-crisp re-render of one layer's visible [region], [scale] times the layer bitmap's
 * resolution. It is drawn instead of the base bitmap inside [region] (same transform, EditorScreen's
 * drawLayerBitmap) and is never saved or exported: the layer's base bitmap stays the source of truth.
 * Not drawn once [key] no longer matches the layer.
 */
internal class HighResOverlay(
    val layerId: String,
    val key: OverlayContentKey,
    val region: PixelRect,
    val scale: Int,
    val bitmap: Bitmap,
) {
    val image: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) { bitmap.asImageBitmap() }
}

/** What the controller reads from the editor's layer caches. */
internal interface HighResOverlaySource {
    fun base(layerId: String): Bitmap?
    fun strokes(layerId: String): List<StrokeCommand>
}

/** One layer's render request. */
internal class HighResRenderJob(
    val layerId: String,
    val base: Bitmap,
    val strokes: List<StrokeCommand>,
    val frame: LayerFrame,
    val plan: RegionPlan,
)

/** Renders a [HighResRenderJob]; null = cannot (not replayable at scale, GPU unavailable, no memory). */
internal fun interface HighResRenderer {
    suspend fun render(job: HighResRenderJob): Bitmap?
}

/**
 * Keeps at most a few [HighResOverlay]s current for the editor's zoomed-in view.
 *
 * [start] feeds it [OverlayInputs]. Every new input first drops what it made stale (a changed layer,
 * zoom back at or below the trigger) and cancels any render in flight, then waits out the debounce
 * ([HighResOverlayConfig.DEBOUNCE_MS] of quiet) before rendering what is missing or too coarse, off
 * the main thread. A render whose layer changed meanwhile is discarded unpublished. All failure
 * degrades to "no overlay": the bilinear base path keeps showing.
 */
internal class HighResOverlayController(
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    private val source: HighResOverlaySource,
    private val renderer: HighResRenderer,
    private val enabled: Boolean = HighResOverlayConfig.ENABLED,
    private val debounceMs: Long = HighResOverlayConfig.DEBOUNCE_MS,
) {
    private val _overlays = MutableStateFlow<Map<String, HighResOverlay>>(emptyMap())
    val overlays: StateFlow<Map<String, HighResOverlay>> = _overlays.asStateFlow()

    @Volatile private var latest: OverlayInputs? = null
    @Volatile private var memoryPressure = false

    fun start(inputs: Flow<OverlayInputs>): Job = scope.launch { inputs.collectLatest { onInputs(it) } }

    /** Memory pressure: drop everything and build nothing more until the view zooms back out. */
    fun onMemoryPressure() {
        memoryPressure = true
        _overlays.value = emptyMap()
    }

    suspend fun onInputs(inputs: OverlayInputs) {
        latest = inputs
        if (!enabled) {
            clear()
            return
        }
        val zoomedIn = inputs.zoom > HighResOverlayConfig.TRIGGER_ZOOM && !inputs.unsupportedMode
        if (!zoomedIn) {
            memoryPressure = false
            clear()
            return
        }
        dropStale(inputs)
        if (memoryPressure || !HighResOverlayPlan.shouldBuild(inputs, enabled)) return
        delay(debounceMs)
        renderMissing(inputs)
    }

    private fun clear() {
        if (_overlays.value.isNotEmpty()) _overlays.value = emptyMap()
    }

    private fun dropStale(inputs: OverlayInputs) {
        val keys = inputs.layers.associate { it.id to it.key }
        val current = _overlays.value
        val kept = current.filter { (id, o) -> !HighResOverlayPlan.isStale(o.key, keys[id]) }
        if (kept.size != current.size) _overlays.value = kept
    }

    private suspend fun renderMissing(inputs: OverlayInputs) {
        val candidates = inputs.layers
            .sortedByDescending { it.isActive }
            .mapNotNull { layer ->
                val base = source.base(layer.id) ?: return@mapNotNull null
                val strokes = source.strokes(layer.id)
                if (strokes.isEmpty() || !strokes.all(HighResOverlayPlan::strokeReplayable)) return@mapNotNull null
                Triple(layer, base, strokes)
            }
        // A layer that can no longer have an overlay must not keep one.
        val ids = candidates.map { it.first.id }.toSet()
        _overlays.update { cur -> cur.filterKeys { it in ids } }
        val budget = HighResOverlayPlan.budgetPerLayer(candidates.size)
        for ((layer, base, strokes) in candidates) {
            val visible = HighResOverlayPlan.visibleBounds(inputs, layer)
            val wanted = HighResOverlayPlan.chooseScale(HighResOverlayPlan.magnification(inputs, layer))
            val plan = HighResOverlayPlan.planRegion(visible, base.width, base.height, wanted, budget)
            if (plan == null) {
                _overlays.update { it - layer.id }
                continue
            }
            val existing = _overlays.value[layer.id]?.let { RegionPlan(it.region, it.scale) }
            if (!HighResOverlayPlan.needsRefresh(existing, plan, visible)) continue
            val bitmap = withContext(renderDispatcher) {
                renderer.render(HighResRenderJob(layer.id, base, strokes, layer.frame, plan))
            } ?: continue
            // Superseded while rendering (a stroke, an undo, an edit): never publish stale pixels.
            val liveKey = latest?.layers?.firstOrNull { it.id == layer.id }?.key
            if (latest !== inputs && HighResOverlayPlan.isStale(layer.key, liveKey) || memoryPressure) {
                bitmap.recycle()
                continue
            }
            val overlay = HighResOverlay(layer.id, layer.key, plan.region, plan.scale, bitmap)
            _overlays.update { it + (layer.id to overlay) }
        }
    }
}

/** The overlay inputs for [state] and the in-flight [live] stroke. */
internal fun overlayInputsOf(state: EditorUiState, live: LiveStroke): OverlayInputs {
    val layers = state.layers.mapNotNull { layer ->
        val bmp = layer.bitmap
        if (bmp == null || bmp.isRecycled || !layer.isEligibleForOverlay()) return@mapNotNull null
        val frame = layer.frame(bmp.width, bmp.height)
        OverlayLayerInput(
            id = layer.id,
            key = OverlayContentKey(
                System.identityHashCode(bmp), bmp.generationId, bmp.width, bmp.height,
                frame.width, frame.height, frame.originX, frame.originY,
            ),
            frame = frame,
            scale = layer.scale,
            offset = layer.offset,
            rotationZ = layer.rotationZ,
            isActive = layer.id == state.activeLayerId,
        )
    }
    return OverlayInputs(
        zoom = state.viewportZoom,
        viewportOffset = state.viewportOffset,
        viewportRotation = state.viewportRotation,
        canvasWidth = state.canvasSize.width,
        canvasHeight = state.canvasSize.height,
        layers = layers,
        liveLayerId = live.layerId.takeIf { live.bitmap != null },
        unsupportedMode = state.wrapAroundMode || state.isAnimationMode,
    )
}

/** The key of what [layer] currently displays, or null without a bitmap; matches [overlayInputsOf]. */
internal fun overlayKeyOf(layer: Layer, bmp: Bitmap, frame: LayerFrame) = OverlayContentKey(
    System.identityHashCode(bmp), bmp.generationId, bmp.width, bmp.height,
    frame.width, frame.height, frame.originX, frame.originY,
)

private fun Layer.isEligibleForOverlay(): Boolean =
    type == LayerType.RASTER && isVisible && parentId == null && rotationX == 0f && rotationY == 0f &&
        shapes.isEmpty() && warpMesh.isEmpty() && textParams == null

/**
 * Re-plays a layer's recorded strokes on the GPU into a bitmap of the plan's region at its scale.
 * Only for a layer whose base is fully transparent (so the strokes are all of its content) and whose
 * strokes are all [HighResOverlayPlan.strokeReplayable]; anything else returns null.
 *
 * The maths: the stroke's dabs are generated exactly as for the base bitmap (same mapped path, same
 * brush scale), only with the path shifted by the region origin and everything multiplied by the
 * scale, so the hi-res layer is the base layer's strokes sampled more finely.
 */
internal class GpuHighResRenderer : HighResRenderer {
    private var warned = false

    override suspend fun render(job: HighResRenderJob): Bitmap? {
        val plan = job.plan
        if (job.frame.width <= 0 || job.base.isRecycled || !isBlank(job.base)) return null
        val target = SafeBitmap.create(plan.region.width * plan.scale, plan.region.height * plan.scale)
            ?: return null
        var ok = false
        try {
            ok = replay(job, target)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            warnOnce(e)
        } finally {
            if (!ok) target.recycle()
        }
        return target.takeIf { ok }
    }

    private suspend fun replay(job: HighResRenderJob, target: Bitmap): Boolean {
        val plan = job.plan
        val s = plan.scale.toFloat()
        GpuReplaySession().use { session ->
            for (stroke in job.strokes) {
                currentCoroutineContext().ensureActive()
                val mapped = ImageProcessor.mapScreenToBitmap(
                    stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, job.frame,
                    stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
                ).map { Offset((it.x - plan.region.left) * s, (it.y - plan.region.top) * s) }
                val brushScale = ImageProcessor.screenToBitmapScale(
                    stroke.canvasSize.width, stroke.canvasSize.height, job.frame, stroke.layerScale,
                ) * s
                val brush = stroke.stampBrush
                session.paint(target, stroke) { engine ->
                    if (brush != null) {
                        GpuStampCommit.paintOnto(engine, stroke, brush, mapped, brushScale, target)
                    } else {
                        GpuBasicStroke.paintOnto(engine, stroke, mapped, brushScale, target)
                    }
                }
                if (session.broken) {
                    warnOnce(null)
                    return false
                }
            }
            if (!session.flush()) {
                warnOnce(null)
                return false
            }
        }
        return true
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr ALPHA_SHIFT != 0 }) return false
        }
        return true
    }

    /** The GPU failed: the base path keeps showing; say so once, not on every pan. */
    private fun warnOnce(e: Throwable?) {
        if (warned) return
        warned = true
        Log.w(TAG, "High-res overlay unavailable (GPU replay failed); keeping the base bitmap view", e)
    }

    private companion object {
        const val TAG = "HighResOverlay"
        const val ALPHA_SHIFT = 24
    }
}
