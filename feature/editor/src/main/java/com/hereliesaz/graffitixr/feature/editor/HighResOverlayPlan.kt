package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.model.Tool
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Knobs for the zoom-aware crisp view (the "high-res overlay", see HighResOverlayController).
 * Flip [ENABLED] to false to switch the whole feature off: nothing is rendered, nothing is drawn,
 * and the editor behaves exactly as it did before it existed.
 */
internal object HighResOverlayConfig {
    const val ENABLED = true

    /** The overlay is built only while the viewport zoom exceeds this. */
    const val TRIGGER_ZOOM = 1.5f

    /** Quiet time after the last gesture / stroke / edit before a render starts. */
    const val DEBOUNCE_MS = 250L

    const val MIN_SCALE = 2
    const val MAX_SCALE = 4

    /** Pixels across ALL overlays together (4096 x 4096), and the longest side of any one. */
    const val MAX_TOTAL_PIXELS = 4096L * 4096L
    const val MAX_SIDE = 4096

    /** Extra margin around the visible region, as a fraction of it, so small pans stay covered. */
    const val MARGIN_FRACTION = 0.25f
}

/** An integer pixel rectangle in a layer bitmap's space (right/bottom exclusive). */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** A float rectangle in a layer bitmap's space. */
internal data class FloatBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** What identifies a layer's pixels: when any part changes, an overlay built from them is stale. */
internal data class OverlayContentKey(
    val bitmapId: Int,
    val generation: Int,
    val width: Int,
    val height: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val originX: Int,
    val originY: Int,
)

/** The slice of a layer the overlay logic needs. */
internal data class OverlayLayerInput(
    val id: String,
    val key: OverlayContentKey,
    val frame: LayerFrame,
    val scale: Float,
    val offset: Offset,
    val rotationZ: Float,
    val isActive: Boolean,
)

/** Everything the overlay controller reacts to; any change restarts the debounce. */
internal data class OverlayInputs(
    val zoom: Float,
    val viewportOffset: Offset,
    val viewportRotation: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Eligible-looking layers (visible raster, no perspective tilt, no group parent). */
    val layers: List<OverlayLayerInput>,
    /** The layer a stroke is being painted on right now, if any. */
    val liveLayerId: String?,
    /** Wrap-around tiling or animation onion skins: the overlay does not model them. */
    val unsupportedMode: Boolean,
)

/** Where and how large an overlay should be rendered. */
internal data class RegionPlan(val region: PixelRect, val scale: Int)

/** The pure decisions behind the overlay: when to build, how big, what makes one stale. */
internal object HighResOverlayPlan {

    fun shouldBuild(inputs: OverlayInputs, enabled: Boolean = HighResOverlayConfig.ENABLED): Boolean =
        enabled && !inputs.unsupportedMode && inputs.liveLayerId == null &&
            inputs.canvasWidth > 0 && inputs.canvasHeight > 0 &&
            inputs.zoom > HighResOverlayConfig.TRIGGER_ZOOM

    /**
     * Bitmap pixels per screen pixel at layer scale 1 for a [frame] fitted into the canvas: the
     * ContentScale.Fit factor. Mirrors ImageProcessor.screenToBitmapScale (not called, because
     * ImageProcessor loads native code on first use and this file is unit-tested on the JVM).
     */
    private fun fitScale(canvasWidth: Int, canvasHeight: Int, frame: LayerFrame): Float {
        if (canvasWidth <= 0 || canvasHeight <= 0 || frame.width <= 0 || frame.height <= 0) return 1f
        val imageAspect = frame.width.toFloat() / frame.height
        val screenAspect = canvasWidth.toFloat() / canvasHeight
        val renderWidth = if (imageAspect > screenAspect) canvasWidth.toFloat() else canvasHeight * imageAspect
        return if (renderWidth > 0f) frame.width / renderWidth else 1f
    }

    /** Screen pixels per layer-bitmap pixel for [layer]: how magnified the base bitmap looks. */
    fun magnification(inputs: OverlayInputs, layer: OverlayLayerInput): Float {
        val safeLayerScale = if (layer.scale > 0.0001f) layer.scale else 1f
        val bitmapPerScreen = fitScale(inputs.canvasWidth, inputs.canvasHeight, layer.frame) / safeLayerScale
        return if (bitmapPerScreen > 0f) inputs.zoom / bitmapPerScreen else inputs.zoom
    }

    /** Integer render scale for a [magnification]: enough texels per screen pixel, within limits. */
    fun chooseScale(magnification: Float): Int =
        ceil(magnification.toDouble() - 1e-3).toInt()
            .coerceIn(HighResOverlayConfig.MIN_SCALE, HighResOverlayConfig.MAX_SCALE)

    /**
     * The part of [layer]'s bitmap the screen shows: the screen rectangle mapped back through the
     * viewport (CanvasHitTest.screenToWorld) and the layer's own transform, the same steps as
     * ImageProcessor.mapScreenToBitmap (mirrored here for the same reason as [fitScale]).
     */
    fun visibleBounds(inputs: OverlayInputs, layer: OverlayLayerInput): FloatBounds {
        val w = inputs.canvasWidth
        val h = inputs.canvasHeight
        val cx = w / 2f
        val cy = h / 2f
        val rad = Math.toRadians(-layer.rotationZ.toDouble())
        val cosA = Math.cos(rad).toFloat()
        val sinA = Math.sin(rad).toFloat()
        val frame = layer.frame
        val imageAspect = frame.width.toFloat() / frame.height
        val screenAspect = w.toFloat() / h
        val renderWidth = if (imageAspect > screenAspect) w.toFloat() else h * imageAspect
        val renderHeight = if (imageAspect > screenAspect) w / imageAspect else h.toFloat()
        val fitOffX = (w - renderWidth) / 2f
        val fitOffY = (h - renderHeight) / 2f
        val sx = frame.width / renderWidth
        val sy = frame.height / renderHeight
        val safeScale = if (kotlin.math.abs(layer.scale) > 1e-4f) layer.scale else 1f
        val corners = listOf(Offset(0f, 0f), Offset(w.toFloat(), 0f), Offset(w.toFloat(), h.toFloat()), Offset(0f, h.toFloat()))
        val mapped = corners.map { c ->
            val p = CanvasHitTest.screenToWorld(c, inputs.viewportOffset, inputs.zoom, inputs.viewportRotation)
            val dx = p.x - cx - layer.offset.x
            val dy = p.y - cy - layer.offset.y
            val ux = (dx * cosA - dy * sinA) / safeScale + cx
            val uy = (dx * sinA + dy * cosA) / safeScale + cy
            Offset((ux - fitOffX) * sx - frame.originX, (uy - fitOffY) * sy - frame.originY)
        }
        return FloatBounds(mapped.minOf { it.x }, mapped.minOf { it.y }, mapped.maxOf { it.x }, mapped.maxOf { it.y })
    }

    /**
     * The region and scale to render for [visible] on a [bitmapWidth] x [bitmapHeight] layer within
     * [pixelBudget] overlay pixels: the visible part plus a margin, clamped to the bitmap. When it
     * does not fit, the scale drops towards [HighResOverlayConfig.MIN_SCALE] first, then the region
     * shrinks around the visible centre. Null when nothing of the bitmap is visible.
     */
    fun planRegion(
        visible: FloatBounds,
        bitmapWidth: Int,
        bitmapHeight: Int,
        wantedScale: Int,
        pixelBudget: Long,
    ): RegionPlan? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || pixelBudget <= 0) return null
        if (visible.right <= 0f || visible.bottom <= 0f || visible.left >= bitmapWidth || visible.top >= bitmapHeight) {
            return null
        }
        val mx = visible.width * HighResOverlayConfig.MARGIN_FRACTION
        val my = visible.height * HighResOverlayConfig.MARGIN_FRACTION
        val left = floor(max(0f, visible.left - mx)).toInt()
        val top = floor(max(0f, visible.top - my)).toInt()
        val right = ceil(min(bitmapWidth.toFloat(), visible.right + mx)).toInt()
        val bottom = ceil(min(bitmapHeight.toFloat(), visible.bottom + my)).toInt()
        val full = PixelRect(left, top, right, bottom)
        if (full.isEmpty) return null
        var scale = wantedScale.coerceIn(HighResOverlayConfig.MIN_SCALE, HighResOverlayConfig.MAX_SCALE)
        while (scale > HighResOverlayConfig.MIN_SCALE && !fits(full, scale, pixelBudget)) scale--
        if (fits(full, scale, pixelBudget)) return RegionPlan(full, scale)
        val region = shrinkToFit(full, visible, scale, pixelBudget) ?: return null
        return RegionPlan(region, scale)
    }

    private fun fits(r: PixelRect, scale: Int, budget: Long): Boolean =
        r.width.toLong() * scale <= HighResOverlayConfig.MAX_SIDE &&
            r.height.toLong() * scale <= HighResOverlayConfig.MAX_SIDE &&
            r.width.toLong() * scale * r.height * scale <= budget

    private fun shrinkToFit(full: PixelRect, visible: FloatBounds, scale: Int, budget: Long): PixelRect? {
        var w = min(full.width, HighResOverlayConfig.MAX_SIDE / scale)
        var h = min(full.height, HighResOverlayConfig.MAX_SIDE / scale)
        val over = w.toDouble() * scale * h * scale / budget
        if (over > 1.0) {
            val k = sqrt(1.0 / over)
            w = floor(w * k).toInt()
            h = floor(h * k).toInt()
        }
        if (w < 1 || h < 1) return null
        val cx = (visible.left + visible.right) / 2f
        val cy = (visible.top + visible.bottom) / 2f
        val l = (cx - w / 2f).toInt().coerceIn(full.left, full.right - w)
        val t = (cy - h / 2f).toInt().coerceIn(full.top, full.bottom - h)
        return PixelRect(l, t, l + w, t + h)
    }

    /** The overlay pixel budget each of [layerCount] layers may use out of the shared total. */
    fun budgetPerLayer(layerCount: Int, total: Long = HighResOverlayConfig.MAX_TOTAL_PIXELS): Long =
        if (layerCount <= 0) 0L else total / layerCount

    /** An overlay built from [builtFrom] no longer matches the layer: drop it, never draw it. */
    fun isStale(builtFrom: OverlayContentKey, current: OverlayContentKey?): Boolean = builtFrom != current

    /**
     * Whether an existing overlay (null = none) should be re-rendered for [plan]: it is missing,
     * too coarse, or no longer covers the visible part (and a different region would).
     */
    fun needsRefresh(existing: RegionPlan?, plan: RegionPlan, visible: FloatBounds): Boolean {
        if (existing == null || existing.scale < plan.scale) return true
        if (covers(existing.region, visible)) return false
        return existing.region != plan.region
    }

    fun covers(region: PixelRect, visible: FloatBounds): Boolean =
        region.left <= visible.left && region.top <= visible.top &&
            region.right >= visible.right && region.bottom >= visible.bottom

    /**
     * Whether [stroke] re-renders faithfully at another resolution: a plain stamp, Basic Brush or
     * Eraser stroke. Anything that reads or depends on the layer's pixel grid (selection, warp,
     * fill, Ink, smudge/liquify/clone, impasto, grain, dual tip, substrate, wrap-around) is not.
     */
    fun strokeReplayable(stroke: StrokeCommand): Boolean {
        if (stroke.selection != null || stroke.warpHandles != null || stroke.fillSelection || stroke.clearAll) return false
        if (stroke.inkStroke != null || stroke.wrapAroundMode || stroke.substrateId != null) return false
        return when (stroke.tool) {
            Tool.ERASER -> true
            Tool.BRUSH -> stroke.stampBrush?.let { brush ->
                brush.impastoThicknessRate <= 0f && brush.maskedBrush == null && brush.substrateResponse <= 0f &&
                    stroke.stampGrain == null && stroke.stampMaskShape == null
            } ?: true
            else -> false
        }
    }
}
