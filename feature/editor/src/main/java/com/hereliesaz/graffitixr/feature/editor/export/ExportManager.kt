// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/export/ExportManager.kt
package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.BlendMode as NativeBlendMode
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.PathEditing
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.model.buildLayerTree
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The artboard (document) rectangle within a [canvasW]×[canvasH] world container — the document's
 * aspect ratio fit and centred, exactly as the on-screen `ArtboardFrame` draws it. Returns
 * `[left, top, width, height]` in canvas pixels. Pure (no Android), so it's unit-testable and shared
 * by the exact-pixel export. Degenerate inputs fall back to the whole canvas.
 */
fun artboardRect(canvasW: Int, canvasH: Int, docW: Int, docH: Int): FloatArray {
    if (canvasW <= 0 || canvasH <= 0 || docW <= 0 || docH <= 0) {
        return floatArrayOf(0f, 0f, canvasW.toFloat(), canvasH.toFloat())
    }
    val docAspect = docW.toFloat() / docH.toFloat()
    val canvasAspect = canvasW.toFloat() / canvasH.toFloat()
    val w: Float
    val h: Float
    if (docAspect > canvasAspect) {
        w = canvasW.toFloat()
        h = w / docAspect
    } else {
        h = canvasH.toFloat()
        w = h * docAspect
    }
    return floatArrayOf((canvasW - w) / 2f, (canvasH - h) / 2f, w, h)
}

/**
 * Handles compositing and exporting of project layers.
 */
class ExportManager @Inject constructor() {

    fun compositeLayers(
        layers: List<Layer>,
        screenWidth: Int,
        screenHeight: Int,
        backgroundBitmap: Bitmap? = null,
        backgroundColor: Int = android.graphics.Color.TRANSPARENT
    ): Bitmap {
        val result = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        if (backgroundColor != android.graphics.Color.TRANSPARENT) {
            canvas.drawColor(backgroundColor)
        }

        backgroundBitmap?.let { bg ->
            val bgAspect = bg.width.toFloat() / bg.height.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

            var renderWidth = screenWidth.toFloat()
            var renderHeight = screenHeight.toFloat()

            if (bgAspect > screenAspect) {
                renderWidth = renderHeight * bgAspect
            } else {
                renderHeight = renderWidth / bgAspect
            }

            val matrix = Matrix()
            matrix.postScale(renderWidth / bg.width, renderHeight / bg.height)
            matrix.postTranslate((screenWidth - renderWidth) / 2f, (screenHeight - renderHeight) / 2f)

            canvas.drawBitmap(bg, matrix, null)
        }

        // buildLayerTree so a LayerType.GROUP composites as one unit (its own opacity/blend applied
        // to the whole child stack, matching the live Compose LayerStackNode) rather than each child
        // painting individually at full strength straight onto the export — the flat forEach this
        // replaced didn't know groups existed at all.
        drawNodes(canvas, buildLayerTree(layers), screenWidth, screenHeight)
        return result
    }

    private fun drawNodes(canvas: Canvas, nodes: List<LayerNode>, w: Int, h: Int) {
        for (node in nodes) drawNode(canvas, node, w, h)
    }

    private fun drawNode(canvas: Canvas, node: LayerNode, w: Int, h: Int) {
        val layer = node.layer
        if (!layer.isVisible) return
        if (layer.type == LayerType.GROUP) {
            val groupBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawNodes(Canvas(groupBmp), node.children, w, h)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                blendMode = layer.blendMode.toNativeBlendMode()
            }
            canvas.drawBitmap(groupBmp, 0f, 0f, paint)
            groupBmp.recycle()
            return
        }
        if (layer.shapes.isNotEmpty()) {
            drawVectorLayer(canvas, layer, w, h, clipToBelow = layer.clipToLayerBelow)
            return
        }
        val b = layer.bitmap ?: return
        val cm = createColorMatrix(
            saturation = layer.saturation,
            contrast = layer.contrast,
            brightness = layer.brightness,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB,
            isInverted = layer.isInverted
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(cm.values))
            // Clip-to-below masks against whatever's already painted beneath it in this same canvas
            // (SRC_IN keeps only the overlap) — Procreate's Clipping Mask. This takes priority over
            // the layer's own blend mode rather than combining both, since doing both correctly needs
            // its own two-pass offscreen composite; a clipped layer with a custom blend is the rare
            // case, and clip-wins is the honest v1 rather than silently dropping the clip instead.
            xfermode = if (layer.clipToLayerBelow) PorterDuffXfermode(PorterDuff.Mode.SRC_IN) else null
            if (!layer.clipToLayerBelow) blendMode = layer.blendMode.toNativeBlendMode()
        }
        val matrix = getLayerScreenMatrix(layer, w, h)
        canvas.drawBitmap(b, matrix, paint)
    }

    /**
     * Composites the [layers] and returns just the **artboard** at the document's exact pixel size
     * ([docW]×[docH]) — what "Export" should produce, rather than the whole screen. Layers are
     * composited in their [canvasW]×[canvasH] world space (where their offsets/scales live), then the
     * centred artboard rect is cropped out and scaled to the document dimensions. Camera-independent
     * (pan/zoom/rotation are view-only and never enter the composite).
     */
    fun compositeToDocument(
        layers: List<Layer>,
        canvasW: Int,
        canvasH: Int,
        docW: Int,
        docH: Int,
        backgroundBitmap: Bitmap? = null,
        backgroundColor: Int = android.graphics.Color.TRANSPARENT,
    ): Bitmap {
        val full = compositeLayers(layers, canvasW, canvasH, backgroundBitmap, backgroundColor)
        if (docW <= 0 || docH <= 0) return full
        val rect = artboardRect(canvasW, canvasH, docW, docH)
        val left = rect[0].roundToInt().coerceIn(0, (full.width - 1).coerceAtLeast(0))
        val top = rect[1].roundToInt().coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val cw = rect[2].roundToInt().coerceIn(1, full.width - left)
        val ch = rect[3].roundToInt().coerceIn(1, full.height - top)
        val cropped = Bitmap.createBitmap(full, left, top, cw, ch)
        // createBitmap(source, ...) can return `source` itself when the requested subset covers it
        // exactly, so only recycle `full` when a distinct bitmap was actually allocated for the crop
        // — otherwise this would recycle the very bitmap we're about to return/use. Every
        // export/print call previously leaked this full-canvas-resolution intermediate.
        if (cropped !== full) full.recycle()
        return if (cropped.width == docW && cropped.height == docH) {
            cropped
        } else {
            val scaled = Bitmap.createScaledBitmap(cropped, docW, docH, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        }
    }

    /**
     * Composites [linkedLayers] into the local coordinate space of the [anchor] layer.
     * The resulting bitmap is capped to a maximum dimension of 2048px to prevent OOM.
     */
    fun compositeToLayerSpace(anchor: Layer, linkedLayers: List<Layer>, screenWidth: Int, screenHeight: Int): Bitmap {
        val anchorBitmap = anchor.bitmap ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        // Cap target dimensions to 2048px to avoid OOM
        val maxDim = 2048
        var targetWidth = anchorBitmap.width
        var targetHeight = anchorBitmap.height
        val aspect = targetWidth.toFloat() / targetHeight.toFloat()
        
        if (targetWidth > maxDim || targetHeight > maxDim) {
            if (aspect > 1f) {
                targetWidth = maxDim
                targetHeight = (maxDim / aspect).toInt()
            } else {
                targetHeight = maxDim
                targetWidth = (maxDim * aspect).toInt()
            }
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val anchorMatrix = getLayerScreenMatrix(anchor, screenWidth, screenHeight)
        val anchorMatrixInv = Matrix()
        if (!anchorMatrix.invert(anchorMatrixInv)) {
            return result
        }

        // Scale factor from original anchor pixels to capped target pixels
        val canvasScale = targetWidth.toFloat() / anchorBitmap.width.toFloat()

        linkedLayers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { b ->
                val cm = createColorMatrix(
                    saturation = layer.saturation,
                    contrast = layer.contrast,
                    brightness = layer.brightness,
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB,
                    isInverted = layer.isInverted
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                    blendMode = layer.blendMode.toNativeBlendMode()
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix(cm.values)
                    )
                }

                val layerMatrix = getLayerScreenMatrix(layer, screenWidth, screenHeight)
                val relativeMatrix = Matrix(anchorMatrixInv)
                relativeMatrix.postConcat(layerMatrix)
                
                // Adjust for capped canvas size
                relativeMatrix.postScale(canvasScale, canvasScale)

                canvas.drawBitmap(b, relativeMatrix, paint)
            }
        }
        return result
    }

    /**
     * Draws a vector [layer]'s shapes into [canvas], mirroring the on-screen render: shapes are drawn
     * centered in the (screenWidth × screenHeight) surface, transformed by the layer's
     * scale / rotationZ / offset. Opacity, blend mode, and the colour adjustments (brightness /
     * contrast / saturation / colour balance / invert) are applied through the save-layer paint, so
     * a vector layer exports with exactly the parity a raster layer gets — a pen layer set to
     * Multiply no longer flattens back to SrcOver.
     */
    private fun drawVectorLayer(canvas: Canvas, layer: Layer, screenWidth: Int, screenHeight: Int, clipToBelow: Boolean = false) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val matrix = Matrix().apply {
            postScale(layer.scale, layer.scale, cx, cy)
            postRotate(layer.rotationZ, cx, cy)
            postTranslate(layer.offset.x, layer.offset.y)
        }
        val cm = createColorMatrix(
            saturation = layer.saturation,
            contrast = layer.contrast,
            brightness = layer.brightness,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB,
            isInverted = layer.isInverted
        )
        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(cm.values))
            // See drawNode's identical raster-layer comment: clip wins over a custom blend mode.
            xfermode = if (clipToBelow) PorterDuffXfermode(PorterDuff.Mode.SRC_IN) else null
            if (!clipToBelow) blendMode = layer.blendMode.toNativeBlendMode()
        }
        val saveCount = canvas.saveLayer(null, layerPaint)
        canvas.concat(matrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        layer.shapes.forEach { drawShape(canvas, it, cx, cy, paint) }
        canvas.restoreToCount(saveCount)
    }

    private fun drawShape(canvas: Canvas, shape: VectorShape, cx: Float, cy: Float, paint: Paint) {
        val w = shape.width
        val h = shape.height
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        when (shape.kind) {
            ShapeKind.RECTANGLE -> {
                if (shape.hasFill) {
                    paint.style = Paint.Style.FILL; paint.color = shape.fillArgb.toInt()
                    canvas.drawRoundRect(rect, shape.cornerRadius, shape.cornerRadius, paint)
                }
                if (shape.hasStroke) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = shape.strokeWidth; paint.color = shape.strokeArgb.toInt()
                    canvas.drawRoundRect(rect, shape.cornerRadius, shape.cornerRadius, paint)
                }
            }
            ShapeKind.ELLIPSE -> {
                if (shape.hasFill) {
                    paint.style = Paint.Style.FILL; paint.color = shape.fillArgb.toInt()
                    canvas.drawOval(rect, paint)
                }
                if (shape.hasStroke) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = shape.strokeWidth; paint.color = shape.strokeArgb.toInt()
                    canvas.drawOval(rect, paint)
                }
            }
            ShapeKind.LINE -> {
                if (shape.hasStroke) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = shape.strokeWidth; paint.color = shape.strokeArgb.toInt()
                    canvas.drawLine(cx - w / 2f, cy, cx + w / 2f, cy, paint)
                }
            }
            ShapeKind.POLYGON -> {
                val n = shape.sides.coerceAtLeast(3)
                val rx = w / 2f
                val ry = h / 2f
                val path = android.graphics.Path().apply {
                    for (i in 0 until n) {
                        val a = -Math.PI / 2 + i * 2 * Math.PI / n
                        val px = cx + rx * Math.cos(a).toFloat()
                        val py = cy + ry * Math.sin(a).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                if (shape.hasFill) {
                    paint.style = Paint.Style.FILL; paint.color = shape.fillArgb.toInt()
                    canvas.drawPath(path, paint)
                }
                if (shape.hasStroke) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = shape.strokeWidth; paint.color = shape.strokeArgb.toInt()
                    canvas.drawPath(path, paint)
                }
            }
            ShapeKind.PATH -> {
                val pts = shape.points
                if (pts.size >= 4) {
                    // Curves through each node's bezier handles where present, straight segments
                    // where not — the same figure EditorScreen's pathFigure draws on the canvas.
                    val path = android.graphics.Path().apply {
                        if (shape.hasCurves) {
                            val nodes = PathEditing.nodes(shape)
                            moveTo(cx + nodes[0].x, cy + nodes[0].y)
                            for (s in 0 until PathEditing.segmentCount(nodes.size, shape.closed)) {
                                val a = nodes[s]
                                val b = nodes[(s + 1) % nodes.size]
                                if (a.isCorner && b.isCorner) {
                                    lineTo(cx + b.x, cy + b.y)
                                } else {
                                    cubicTo(
                                        cx + a.x + a.outDx, cy + a.y + a.outDy,
                                        cx + b.x + b.inDx, cy + b.y + b.inDy,
                                        cx + b.x, cy + b.y,
                                    )
                                }
                            }
                        } else {
                            moveTo(cx + pts[0], cy + pts[1])
                            var i = 2
                            while (i + 1 < pts.size) { lineTo(cx + pts[i], cy + pts[i + 1]); i += 2 }
                        }
                        if (shape.closed) close()
                    }
                    if (shape.hasFill) {
                        paint.style = Paint.Style.FILL; paint.color = shape.fillArgb.toInt()
                        canvas.drawPath(path, paint)
                    }
                    if (shape.hasStroke) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = shape.strokeWidth; paint.color = shape.strokeArgb.toInt()
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
    }

    private fun getLayerScreenMatrix(layer: Layer, screenWidth: Int, screenHeight: Int): Matrix {
        val b = layer.bitmap ?: return Matrix()
        val matrix = Matrix()

        // Calculate ContentScale.Fit logic so the exported image matches the UI layout bounds
        val imageAspect = b.width.toFloat() / b.height.toFloat()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

        var renderWidth = screenWidth.toFloat()
        var renderHeight = screenHeight.toFloat()

        if (imageAspect > screenAspect) {
            renderHeight = renderWidth / imageAspect
        } else {
            renderWidth = renderHeight * imageAspect
        }

        // 1. Initial Scale to screen constraints
        matrix.postScale(renderWidth / b.width, renderHeight / b.height)

        // 2. Center Pivot
        matrix.postTranslate(-renderWidth / 2f, -renderHeight / 2f)

        // 3. User Transforms (Scale, Rotate, Offset)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ) // Standard 2D export only respects Z

        // 4. Move to center of screen + apply pan
        matrix.postTranslate(screenWidth / 2f + layer.offset.x, screenHeight / 2f + layer.offset.y)

        return matrix
    }

    private fun BlendMode.toNativeBlendMode(): NativeBlendMode {
        return when (this) {
            BlendMode.Clear -> NativeBlendMode.CLEAR
            BlendMode.Src -> NativeBlendMode.SRC
            BlendMode.Dst -> NativeBlendMode.DST
            BlendMode.SrcOver -> NativeBlendMode.SRC_OVER
            BlendMode.DstOver -> NativeBlendMode.DST_OVER
            BlendMode.SrcIn -> NativeBlendMode.SRC_IN
            BlendMode.DstIn -> NativeBlendMode.DST_IN
            BlendMode.SrcOut -> NativeBlendMode.SRC_OUT
            BlendMode.DstOut -> NativeBlendMode.DST_OUT
            BlendMode.SrcAtop -> NativeBlendMode.SRC_ATOP
            BlendMode.DstAtop -> NativeBlendMode.DST_ATOP
            BlendMode.Xor -> NativeBlendMode.XOR
            BlendMode.Plus -> NativeBlendMode.PLUS
            BlendMode.Modulate -> NativeBlendMode.MODULATE
            BlendMode.Screen -> NativeBlendMode.SCREEN
            BlendMode.Overlay -> NativeBlendMode.OVERLAY
            BlendMode.Darken -> NativeBlendMode.DARKEN
            BlendMode.Lighten -> NativeBlendMode.LIGHTEN
            BlendMode.ColorDodge -> NativeBlendMode.COLOR_DODGE
            BlendMode.ColorBurn -> NativeBlendMode.COLOR_BURN
            BlendMode.Hardlight -> NativeBlendMode.HARD_LIGHT
            BlendMode.Softlight -> NativeBlendMode.SOFT_LIGHT
            BlendMode.Difference -> NativeBlendMode.DIFFERENCE
            BlendMode.Exclusion -> NativeBlendMode.EXCLUSION
            BlendMode.Multiply -> NativeBlendMode.MULTIPLY
            BlendMode.Hue -> NativeBlendMode.HUE
            BlendMode.Saturation -> NativeBlendMode.SATURATION
            BlendMode.Color -> NativeBlendMode.COLOR
            BlendMode.Luminosity -> NativeBlendMode.LUMINOSITY
            else -> NativeBlendMode.SRC_OVER
        }
    }
}