package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.LayerFrame
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Region geometry for the crisp overlay: which part of a layer bitmap the screen shows, and how big
 * a region may be rendered. The screen-to-bitmap steps mirror ImageProcessor.mapScreenToBitmap; they
 * are repeated here (not called) because ImageProcessor loads native code on first use and this file
 * is unit-tested on the JVM.
 */
internal object HighResOverlayRegion {

    /** Bitmap pixels per screen pixel at layer scale 1 for [frame] fitted into the canvas. */
    fun fitScale(canvasWidth: Int, canvasHeight: Int, frame: LayerFrame): Float {
        val renderWidth = renderWidth(canvasWidth, canvasHeight, frame)
        return if (renderWidth > 0f) frame.width / renderWidth else 1f
    }

    private fun renderWidth(canvasWidth: Int, canvasHeight: Int, frame: LayerFrame): Float {
        val sizes = listOf(canvasWidth, canvasHeight, frame.width, frame.height)
        if (sizes.any { it <= 0 }) return 0f
        val imageAspect = frame.width.toFloat() / frame.height
        val screenAspect = canvasWidth.toFloat() / canvasHeight
        return if (imageAspect > screenAspect) canvasWidth.toFloat() else canvasHeight * imageAspect
    }

    /**
     * The bounds, in [layer]'s bitmap pixels, of the screen rectangle mapped back through the
     * viewport (CanvasHitTest.screenToWorld) and the layer's own transform.
     */
    fun visibleBounds(inputs: OverlayInputs, layer: OverlayLayerInput): FloatBounds {
        val w = inputs.canvasWidth.toFloat()
        val h = inputs.canvasHeight.toFloat()
        val renderW = renderWidth(inputs.canvasWidth, inputs.canvasHeight, layer.frame)
        val renderH = if (renderW > 0f) renderW * layer.frame.height / layer.frame.width else h
        val fitOff = Offset((w - renderW) / 2f, (h - renderH) / 2f)
        val fit = Offset(layer.frame.width / renderW, layer.frame.height / renderH)
        val rad = Math.toRadians(-layer.rotationZ.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val scale = if (abs(layer.scale) > HighResOverlayConfig.MIN_LAYER_SCALE) layer.scale else 1f
        val screenCorners = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
        val mapped = screenCorners.map { corner ->
            val p = CanvasHitTest.screenToWorld(corner, inputs.viewportOffset, inputs.zoom, inputs.viewportRotation)
            val dx = p.x - w / 2f - layer.offset.x
            val dy = p.y - h / 2f - layer.offset.y
            val ux = (dx * cosA - dy * sinA) / scale + w / 2f
            val uy = (dx * sinA + dy * cosA) / scale + h / 2f
            Offset(
                (ux - fitOff.x) * fit.x - layer.frame.originX,
                (uy - fitOff.y) * fit.y - layer.frame.originY,
            )
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
        val full = padded(visible, bitmapWidth, bitmapHeight)
        return if (full == null || pixelBudget <= 0) null else fitPlan(full, visible, wantedScale, pixelBudget)
    }

    private fun padded(visible: FloatBounds, bitmapWidth: Int, bitmapHeight: Int): PixelRect? {
        val overlapsX = visible.right > 0f && visible.left < bitmapWidth
        val overlapsY = visible.bottom > 0f && visible.top < bitmapHeight
        val mx = visible.width * HighResOverlayConfig.MARGIN_FRACTION
        val my = visible.height * HighResOverlayConfig.MARGIN_FRACTION
        val rect = PixelRect(
            floor(max(0f, visible.left - mx)).toInt(),
            floor(max(0f, visible.top - my)).toInt(),
            ceil(min(bitmapWidth.toFloat(), visible.right + mx)).toInt(),
            ceil(min(bitmapHeight.toFloat(), visible.bottom + my)).toInt(),
        )
        return rect.takeIf { overlapsX && overlapsY && !it.isEmpty && bitmapWidth > 0 && bitmapHeight > 0 }
    }

    private fun fitPlan(full: PixelRect, visible: FloatBounds, wantedScale: Int, budget: Long): RegionPlan? {
        var scale = wantedScale.coerceIn(HighResOverlayConfig.MIN_SCALE, HighResOverlayConfig.MAX_SCALE)
        while (scale > HighResOverlayConfig.MIN_SCALE && !fits(full, scale, budget)) scale--
        val region = if (fits(full, scale, budget)) full else shrinkToFit(full, visible, scale, budget)
        return region?.let { RegionPlan(it, scale) }
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
        val cx = (visible.left + visible.right) / 2f
        val cy = (visible.top + visible.bottom) / 2f
        val l = (cx - w / 2f).toInt().coerceIn(full.left, max(full.left, full.right - w))
        val t = (cy - h / 2f).toInt().coerceIn(full.top, max(full.top, full.bottom - h))
        return PixelRect(l, t, l + w, t + h).takeIf { w >= 1 && h >= 1 }
    }
}
