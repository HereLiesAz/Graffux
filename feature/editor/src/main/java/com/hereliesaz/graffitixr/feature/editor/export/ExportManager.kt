// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/export/ExportManager.kt
package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.BlendMode as NativeBlendMode
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix
import javax.inject.Inject

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

        layers.filter { it.isVisible }.forEach { layer ->
            if (layer.shapes.isNotEmpty()) {
                drawVectorLayer(canvas, layer, screenWidth, screenHeight)
                return@forEach
            }
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

                val matrix = getLayerScreenMatrix(layer, screenWidth, screenHeight)
                canvas.drawBitmap(b, matrix, paint)
            }
        }
        return result
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
     * scale / rotationZ / offset, with the layer opacity applied via a save-layer. Blend mode is left
     * at the default (SrcOver) for shapes in this first pass.
     */
    private fun drawVectorLayer(canvas: Canvas, layer: Layer, screenWidth: Int, screenHeight: Int) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val matrix = Matrix().apply {
            postScale(layer.scale, layer.scale, cx, cy)
            postRotate(layer.rotationZ, cx, cy)
            postTranslate(layer.offset.x, layer.offset.y)
        }
        val saveCount = canvas.saveLayerAlpha(null, (layer.opacity * 255).toInt().coerceIn(0, 255))
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