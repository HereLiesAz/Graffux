package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.util.SafeBitmap

/**
 * Kotlin UI layer implementation of image manipulation tools.
  * Native Android hardware-accelerated 2D pipeline.
   * JNI boundary calls have been fully eradicated.
    */
object ImageProcessor {

    init {
        NativeLibLoader.loadAll()
    }

        /**
         * The linear scale that [mapScreenToBitmap] applies to a *length* (e.g. a brush diameter):
         * screen_px * scale = bitmap_px. It is the same `fitScaleX / layerScale` used for coordinates
         * (ContentScale.Fit is uniform, so X and Y scales are equal). Drawing a stroke whose width is
         * `brushSize * scale` in bitmap space therefore renders at exactly `brushSize` screen px,
         * matching the size preview shown in the rail item. Returns 1f for a 1:1, unscaled layer.
         */
        fun screenToBitmapScale(
            screenWidth: Int,
            screenHeight: Int,
            bitmapWidth: Int,
            bitmapHeight: Int,
            layerScale: Float,
        ): Float {
            if (screenWidth <= 0 || screenHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return 1f
            val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
            val renderWidth = if (imageAspect > screenAspect) screenWidth.toFloat() else screenHeight * imageAspect
            val fitScaleX = if (renderWidth > 0f) bitmapWidth / renderWidth else 1f
            val safeLayerScale = if (layerScale > 0.0001f) layerScale else 1f
            return fitScaleX / safeLayerScale
        }

        /**
             * Maps screen-space touch coordinates to pixel-space bitmap coordinates,
                  * accounting for Compose's ContentScale.Fit logic used in the UI.
                       */
        /**
         * The exact inverse of [mapScreenToBitmap] — bitmap pixels back out to screen points.
         *
         * Needed by the magic wand, which is the one selection mode that works out its region in a
         * layer's pixels rather than under the finger: the flood fill and the contour trace happen
         * in bitmap space, and a [com.hereliesaz.graffitixr.common.model.Selection] is screen-space.
         * Written as the four steps of [mapScreenToBitmap] run backwards rather than as a separate
         * derivation, so the two cannot drift apart — a wand selection that landed even slightly
         * off would clip the paint somewhere other than where the ants were drawn.
         */
        fun mapBitmapToScreen(
            points: List<Offset>,
            screenWidth: Int,
            screenHeight: Int,
            bitmapWidth: Int,
            bitmapHeight: Int,
            layerScale: Float = 1f,
            layerOffset: Offset = Offset.Zero,
            layerRotationZ: Float = 0f,
        ): List<Offset> {
            val screenCx = screenWidth / 2f
            val screenCy = screenHeight / 2f

            val angleRad = Math.toRadians(-layerRotationZ.toDouble())
            val cosA = Math.cos(angleRad).toFloat()
            val sinA = Math.sin(angleRad).toFloat()

            val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
            val renderWidth: Float
            val renderHeight: Float
            if (imageAspect > screenAspect) {
                renderWidth = screenWidth.toFloat()
                renderHeight = screenWidth / imageAspect
            } else {
                renderHeight = screenHeight.toFloat()
                renderWidth = screenHeight * imageAspect
            }
            val fitOffX = (screenWidth - renderWidth) / 2f
            val fitOffY = (screenHeight - renderHeight) / 2f
            val fitScaleX = bitmapWidth / renderWidth
            val fitScaleY = bitmapHeight / renderHeight
            val safeScale = if (kotlin.math.abs(layerScale) > 1e-4f) layerScale else 1f

            return points.map { pt ->
                // Step 4 undone: redo the ContentScale.Fit letterboxing.
                val lx = (if (fitScaleX != 0f) pt.x / fitScaleX else 0f) + fitOffX
                val ly = (if (fitScaleY != 0f) pt.y / fitScaleY else 0f) + fitOffY

                // Step 3 undone: redo the layer scale, about the screen centre.
                val rx = (lx - screenCx) * safeScale
                val ry = (ly - screenCy) * safeScale

                // Step 2 undone: rotate back the other way. The forward pass multiplies by
                // [cosA, -sinA; sinA, cosA], whose inverse — it is a rotation, so its transpose —
                // is [cosA, sinA; -sinA, cosA].
                val dx = rx * cosA + ry * sinA
                val dy = -rx * sinA + ry * cosA

                // Step 1 undone: back out of pivot-relative coords and reapply the translation.
                Offset(dx + screenCx + layerOffset.x, dy + screenCy + layerOffset.y)
            }
        }

        fun mapScreenToBitmap(
                    stroke: List<Offset>,
                    screenWidth: Int,
                    screenHeight: Int,
                    bitmapWidth: Int,
                    bitmapHeight: Int,
                    layerScale: Float = 1f,
                    layerOffset: Offset = Offset.Zero,
                    layerRotationZ: Float = 0f
                ): List<Offset> {
                    val screenCx = screenWidth / 2f
                    val screenCy = screenHeight / 2f

                    // Precompute inverse rotation (negate the layer's rotationZ).
                    val angleRad = Math.toRadians(-layerRotationZ.toDouble())
                    val cosA = Math.cos(angleRad).toFloat()
                    val sinA = Math.sin(angleRad).toFloat()

                    // ContentScale.Fit: compute how the bitmap is letterboxed into the full screen.
                    val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
                    val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
                    val renderWidth: Float
                    val renderHeight: Float
                    if (imageAspect > screenAspect) {
                        renderWidth = screenWidth.toFloat()
                        renderHeight = screenWidth / imageAspect
                    } else {
                        renderHeight = screenHeight.toFloat()
                        renderWidth = screenHeight * imageAspect
                    }
                    val fitOffX = (screenWidth - renderWidth) / 2f
                    val fitOffY = (screenHeight - renderHeight) / 2f
                    val fitScaleX = bitmapWidth / renderWidth
                    val fitScaleY = bitmapHeight / renderHeight

                    return stroke.map { pt ->
                        // Step 1: Move to pivot-relative coords and undo layer translation.
                        val dx = pt.x - screenCx - layerOffset.x
                        val dy = pt.y - screenCy - layerOffset.y

                        // Step 2: Undo layer rotationZ.
                        val rx = dx * cosA - dy * sinA
                        val ry = dx * sinA + dy * cosA

                        // Step 3: Undo layer scale (guard a degenerate ~0 scale → avoid Infinity coords).
                        val safeScale = if (kotlin.math.abs(layerScale) > 1e-4f) layerScale else 1f
                        val ux = rx / safeScale
                        val uy = ry / safeScale

                        // Step 4: Back to layout space, then undo ContentScale.Fit letterboxing.
                        val lx = ux + screenCx
                        val ly = uy + screenCy
                        Offset(
                            (lx - fitOffX) * fitScaleX,
                            (ly - fitOffY) * fitScaleY
                        )
                    }
        }

            suspend fun applyToolToBitmap(
                        originalBitmap: Bitmap,
                        stroke: List<Offset>,
                        tool: Tool,
                        brushSize: Float = 50f,
                        brushColor: Int = Color.BLACK,
                        intensity: Float = 0.5f,
                        mutateInPlace: Boolean = false,
                        feathering: Float = 0f,
                        wrapAroundMode: Boolean = false,
                        // Procreate parity flags: alphaLock confines paint to existing alpha
                        // (SRC_ATOP); symmetryMode mirrors the stroke across one or more axes
                        // through the bitmap's centre. Both must be honoured here so undo/redo
                        // replay matches exactly what was painted live.
                        alphaLock: Boolean = false,
                        symmetryMode: SymmetryMode = SymmetryMode.NONE,
                        // Bitmap-space lasso selection, if one is active: every tool in the switch
                        // below is confined to it. Built by SelectionMask so the live paint, the
                        // commit and the history replay all clip to the identical boundary.
                        // Tool.LIQUIFY is the exception — it never reaches this function, and
                        // DrawingEngine.applyLiquify ignores the selection entirely.
                        clipPath: android.graphics.Path? = null,
                        // Tool.CLONE only: the bitmap-space vector from the stroke to the pixels it
                        // copies. Passed in rather than derived here because it is fixed at the
                        // moment the stroke starts and recorded onto the command, which is what
                        // makes a clone stroke replay identically on undo/redo.
                        cloneOffset: Offset? = null,
                    ): Bitmap = withContext(Dispatchers.Default) {
                        if (stroke.isEmpty()) return@withContext originalBitmap

                        val resultBitmap = if (mutateInPlace) originalBitmap
                        else originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                val canvas = Canvas(resultBitmap)
                                // Clip once, up front: applies to every branch below, including the
                                // symmetry twin and the BLUR composite.
                                if (clipPath != null) canvas.clipPath(clipPath)

                                        when (tool) {
                                                        Tool.BRUSH -> {
                                                                            val paint = Paint().apply {
                                                                                                    color = brushColor
                                                                                                    strokeWidth = brushSize
                                                                                                    style = Paint.Style.STROKE
                                                                                                    strokeCap = Paint.Cap.ROUND
                                                                                                    strokeJoin = Paint.Join.ROUND
                                                                                                    isAntiAlias = true
                                                                                                    if (alphaLock) {
                                                                                                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                                                                                                    }
                                                                                                    if (feathering > 0f) {
                                                                                                        maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                                                                                    }
                                                                            }
                                                                                            // The brush is dynamic (velocity width + start taper);
                                                                                            // BrushDynamics is deterministic from the points, so this
                                                                                            // replay renders exactly what the live stroke painted.
                                                                                            drawStrokeDynamic(canvas, stroke, paint, brushSize, wrapAroundMode, symmetryMode)
                                                        }

                                                                    Tool.ERASER -> {
                                                                                        val paint = Paint().apply {
                                                                                                                strokeWidth = brushSize
                                                                                                                style = Paint.Style.STROKE
                                                                                                                strokeCap = Paint.Cap.ROUND
                                                                                                                strokeJoin = Paint.Join.ROUND
                                                                                                                isAntiAlias = true
                                                                                                                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                                                                                                                if (feathering > 0f) {
                                                                                                                    maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                                                                                                }
                                                                                        }
                                                                                                        drawStroke(canvas, stroke, paint, wrapAroundMode, symmetryMode)
                                                                    }

                                Tool.BLUR -> {
                                    // Actually blur the pixels under the stroke: build a blurred copy of the layer
                                    // and stamp it back only where the stroke draws (the stroke is used as an alpha
                                    // mask). The old code set no Paint color, so Paint's default (black) was painted
                                    // as a translucent line — smearing black instead of blurring.
                                    val factor = (2 + (intensity.coerceIn(0f, 1f) * 12f)).toInt().coerceIn(2, 16)
                                    val blurred = cheapBlur(resultBitmap, factor)
                                    val maskBmp = Bitmap.createBitmap(resultBitmap.width, resultBitmap.height, Bitmap.Config.ARGB_8888)
                                    val maskCanvas = Canvas(maskBmp)
                                    val maskPaint = Paint().apply {
                                        strokeWidth = brushSize
                                        style = Paint.Style.STROKE
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                        isAntiAlias = true
                                        if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                    }
                                    drawStroke(maskCanvas, stroke, maskPaint, wrapAroundMode, symmetryMode)
                                    // Keep the blurred pixels only where the stroke drew, then composite onto the layer.
                                    maskCanvas.drawBitmap(blurred, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
                                    canvas.drawBitmap(maskBmp, 0f, 0f, null)
                                    maskBmp.recycle()
                                    blurred.recycle()
                                }

                                Tool.SHARPEN -> {
                                    // The inverse of BLUR: an **unsharp mask**, result = original +
                                    // amount × (original − blurred). That is what sharpening actually is — no tool
                                    // can invent detail that isn't in the pixels, so the only thing to add back is
                                    // the local contrast a blur throws away. A fixed 3×3 convolution kernel is the
                                    // other way to write it and is worse here: its strength isn't continuously
                                    // adjustable, so `intensity` would have nothing to control.
                                    //
                                    // The blur it differences against is a *tight* one (factor 2, roughly a two-
                                    // pixel radius) regardless of intensity. Widening it doesn't sharpen harder, it
                                    // sharpens *coarser* — haloes around big shapes instead of crisper edges — so
                                    // intensity moves the amount and leaves the radius alone.
                                    val amount = 0.4f + intensity.coerceIn(0f, 1f) * 1.6f
                                    val w = resultBitmap.width
                                    val h = resultBitmap.height

                                    // The stroke, rendered as a coverage mask. Same paint as every other tool here,
                                    // so the brush edge, the feathering and the symmetry twin all match.
                                    val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    val maskPaint = Paint().apply {
                                        strokeWidth = brushSize
                                        style = Paint.Style.STROKE
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                        isAntiAlias = true
                                        if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                    }
                                    drawStroke(Canvas(maskBmp), stroke, maskPaint, wrapAroundMode, symmetryMode)

                                    val soft = cheapBlur(resultBitmap, 2)
                                    val n = w * h
                                    val out = IntArray(n)
                                    val blur = IntArray(n)
                                    val mask = IntArray(n)
                                    resultBitmap.getPixels(out, 0, w, 0, 0, w, h)
                                    soft.getPixels(blur, 0, w, 0, 0, w, h)
                                    maskBmp.getPixels(mask, 0, w, 0, 0, w, h)
                                    soft.recycle()
                                    maskBmp.recycle()

                                    // The mask is applied *here*, per pixel, rather than by stamping a sharpened
                                    // copy through it with SRC_IN the way BLUR and CLONE do. Those two composite the
                                    // masked copy back with SRC_OVER, and source-over of a translucent pixel onto
                                    // itself RAISES alpha — a=4 over a=4 lands on 7 — so painting across a soft
                                    // edge quietly makes it more opaque every time. Lerping the colour channels by
                                    // the mask's coverage and never touching alpha at all has no such drift, and it
                                    // gives the same soft brush edge because coverage scales the amount.
                                    //
                                    // Alpha is left alone for a second reason too: sharpening the alpha channel
                                    // ratchets a soft edge towards fully-on/fully-off, so a stroke along the
                                    // boundary of a shape would leave the shape's own outline ragged — damage to
                                    // the artwork rather than to the pixels being worked on.
                                    for (i in 0 until n) {
                                        val coverage = mask[i] ushr 24 and 0xFF
                                        if (coverage == 0) continue
                                        val p = out[i]
                                        val q = blur[i]
                                        val k = amount * coverage / 255f
                                        val pr = p shr 16 and 0xFF
                                        val pg = p shr 8 and 0xFF
                                        val pb = p and 0xFF
                                        val r = (pr + k * (pr - (q shr 16 and 0xFF))).toInt().coerceIn(0, 255)
                                        val g = (pg + k * (pg - (q shr 8 and 0xFF))).toInt().coerceIn(0, 255)
                                        val b = (pb + k * (pb - (q and 0xFF))).toInt().coerceIn(0, 255)
                                        out[i] = (p.toLong() and 0xFF000000L).toInt() or (r shl 16) or (g shl 8) or b
                                    }

                                    // Back through the canvas rather than straight onto resultBitmap with
                                    // setPixels, because the selection clip lives on the canvas: writing pixels
                                    // directly would sharpen right through a lasso. PorterDuff.SRC replaces rather
                                    // than blends, which is what makes this a plain assignment of the array above —
                                    // and outside the stroke that array is byte-identical to what is already there.
                                    val composited = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    composited.setPixels(out, 0, w, 0, 0, w, h)
                                    canvas.drawBitmap(composited, 0f, 0f, Paint().apply {
                                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                                    })
                                    composited.recycle()
                                }

                                        Tool.CLONE -> {
                                            // Copies pixels from elsewhere on the same layer.
                                            //
                                            // Same shape as the BLUR branch above: the stroke is
                                            // drawn into a scratch bitmap as a mask, then the thing
                                            // being composited is kept only where the stroke drew
                                            // (SRC_IN) before going down on the layer. That is what
                                            // gives a soft, brush-shaped edge — clipping to a
                                            // stroked path instead would give a hard one.
                                            val d = cloneOffset
                                            if (d != null) {
                                                val maskBmp = SafeBitmap.create(resultBitmap.width, resultBitmap.height)
                                                if (maskBmp != null) {
                                                    val maskCanvas = Canvas(maskBmp)
                                                    val maskPaint = Paint().apply {
                                                        strokeWidth = brushSize
                                                        style = Paint.Style.STROKE
                                                        strokeCap = Paint.Cap.ROUND
                                                        strokeJoin = Paint.Join.ROUND
                                                        isAntiAlias = true
                                                        if (feathering > 0f) {
                                                            maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                                        }
                                                    }
                                                    drawStroke(maskCanvas, stroke, maskPaint, wrapAroundMode, symmetryMode)
                                                    // The source, shifted so the sampled pixels land
                                                    // under the brush. Read from originalBitmap, so
                                                    // an in-place stroke samples the layer as it now
                                                    // stands — which is what a clone brush does.
                                                    maskCanvas.drawBitmap(
                                                        originalBitmap, d.x, d.y,
                                                        Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) },
                                                    )
                                                    canvas.drawBitmap(maskBmp, 0f, 0f, null)
                                                    maskBmp.recycle()
                                                }
                                            }
                                        }

                                                                                                        Tool.HEAL -> {
                                                                                                                            val paint = Paint().apply {
                                                                                                                                                    color = brushColor
                                                                                                                                                    strokeWidth = brushSize
                                                                                                                                                    style = Paint.Style.STROKE
                                                                                                                                                    strokeCap = Paint.Cap.ROUND
                                                                                                                                                    strokeJoin = Paint.Join.ROUND
                                                                                                                                                    isAntiAlias = true
                                                                                                                                                    alpha = 128
                                                                                                                            }
                                                                                                                                            drawStroke(canvas, stroke, paint, wrapAroundMode, symmetryMode)
                                                                                                        }
                                                                                                        
                                                                                                                    Tool.BURN -> {
                                                                                                                                        val paint = Paint().apply {
                                                                                                                                                                color = Color.BLACK
                                                                                                                                                                strokeWidth = brushSize
                                                                                                                                                                style = Paint.Style.STROKE
                                                                                                                                                                strokeCap = Paint.Cap.ROUND
                                                                                                                                                                strokeJoin = Paint.Join.ROUND
                                                                                                                                                                alpha = (255 * intensity * 0.3f).toInt().coerceIn(0, 255)
                                                                                                                                                                                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                                                                                                                                        }
                                                                                                                                                        drawStroke(canvas, stroke, paint, wrapAroundMode, symmetryMode)
                                                                                                                    }
                                                                                                                    
                                                                                                                                Tool.DODGE -> {
                                                                                                                                                    val paint = Paint().apply {
                                                                                                                                                                            color = Color.WHITE
                                                                                                                                                                            strokeWidth = brushSize
                                                                                                                                                                            style = Paint.Style.STROKE
                                                                                                                                                                            strokeCap = Paint.Cap.ROUND
                                                                                                                                                                            strokeJoin = Paint.Join.ROUND
                                                                                                                                                                            alpha = (255 * intensity * 0.3f).toInt().coerceIn(0, 255)
                                                                                                                                                                                                xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                                                                                                                                                                                }
                                                                                                                                                                    drawStroke(canvas, stroke, paint, wrapAroundMode, symmetryMode)
                                                                                                                                }

                                                                                                                                            Tool.COLOR -> {
                                                                                                                                                // Approximates Photoshop's Color tool: washes the active colour over the
                                                                                                                                                // stroke. SRC_ATOP (unlike BRUSH's default SRC_OVER) confines it to
                                                                                                                                                // pixels the layer already has, so it tints existing artwork instead of
                                                                                                                                                // painting solid colour over blank canvas.
                                                                                                                                                val paint = Paint().apply {
                                                                                                                                                    color = brushColor
                                                                                                                                                    strokeWidth = brushSize
                                                                                                                                                    style = Paint.Style.STROKE
                                                                                                                                                    strokeCap = Paint.Cap.ROUND
                                                                                                                                                    strokeJoin = Paint.Join.ROUND
                                                                                                                                                    isAntiAlias = true
                                                                                                                                                    alpha = (255 * intensity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
                                                                                                                                                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                                                                                                                                                }
                                                                                                                                                drawStroke(canvas, stroke, paint, wrapAroundMode, symmetryMode)
                                                                                                                                            }

                                                                                                                                            else -> {}
                                        }

                                                resultBitmap
            }

                /**
                 * The point transforms [mode] mirrors/rotates a stroke through, one per twin drawn —
                 * excludes the identity (the caller already draws the untransformed stroke itself).
                 * [VERTICAL]/[HORIZONTAL] reflect across the centre line on that axis; [QUADRANT] is
                 * both reflections plus their combination (4-fold total including the original);
                 * [RADIAL_6] rotates the stroke five more times at 60° steps around the canvas centre.
                 */
                private fun symmetryTransforms(mode: SymmetryMode, w: Float, h: Float): List<(Offset) -> Offset> {
                    val cx = w / 2f
                    val cy = h / 2f
                    return when (mode) {
                        SymmetryMode.NONE -> emptyList()
                        SymmetryMode.VERTICAL -> listOf({ p: Offset -> Offset(w - p.x, p.y) })
                        SymmetryMode.HORIZONTAL -> listOf({ p: Offset -> Offset(p.x, h - p.y) })
                        SymmetryMode.QUADRANT -> listOf(
                            { p: Offset -> Offset(w - p.x, p.y) },
                            { p: Offset -> Offset(p.x, h - p.y) },
                            { p: Offset -> Offset(w - p.x, h - p.y) },
                        )
                        SymmetryMode.RADIAL_6 -> (1..5).map { k ->
                            val rad = Math.toRadians((60.0 * k))
                            val cos = kotlin.math.cos(rad).toFloat()
                            val sin = kotlin.math.sin(rad).toFloat()
                            val transform: (Offset) -> Offset = { p: Offset ->
                                val dx = p.x - cx
                                val dy = p.y - cy
                                Offset(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
                            }
                            transform
                        }
                    }
                }

                private fun drawStroke(canvas: Canvas, stroke: List<Offset>, paint: Paint, wrapAroundMode: Boolean = false, symmetryMode: SymmetryMode = SymmetryMode.NONE) {
                            if (symmetryMode != SymmetryMode.NONE) {
                                // Draw each mirrored/rotated twin first with symmetry off, then fall
                                // through to the normal draw — keeps the mirror math in exactly one place.
                                for (transform in symmetryTransforms(symmetryMode, canvas.width.toFloat(), canvas.height.toFloat())) {
                                    drawStroke(canvas, stroke.map(transform), paint, wrapAroundMode, symmetryMode = SymmetryMode.NONE)
                                }
                            }
                            if (stroke.size == 1) {
                                val cx = stroke.first().x
                                val cy = stroke.first().y
                                if (wrapAroundMode) {
                                    val w = canvas.width.toFloat()
                                    val h = canvas.height.toFloat()
                                    for (dx in -1..1) {
                                        for (dy in -1..1) {
                                            canvas.drawPoint(cx + dx * w, cy + dy * h, paint)
                                        }
                                    }
                                } else {
                                    canvas.drawPoint(cx, cy, paint)
                                }
                                return
                            }
                            val path = android.graphics.Path()
                            path.moveTo(stroke.first().x, stroke.first().y)
                            for (i in 1 until stroke.size) {
                                path.lineTo(stroke[i].x, stroke[i].y)
                            }
                            if (wrapAroundMode) {
                                val w = canvas.width.toFloat()
                                val h = canvas.height.toFloat()
                                for (dx in -1..1) {
                                    for (dy in -1..1) {
                                        if (dx == 0 && dy == 0) {
                                            canvas.drawPath(path, paint)
                                        } else {
                                            val p = android.graphics.Path(path)
                                            p.offset(dx * w, dy * h)
                                            canvas.drawPath(p, paint)
                                        }
                                    }
                                }
                            } else {
                                canvas.drawPath(path, paint)
                            }
                }

                /**
                 * Variable-width stroke for the dynamic brush: each segment is drawn as its own
                 * round-capped line at the width [com.hereliesaz.graffitixr.feature.editor.BrushDynamics]
                 * computes for it, giving velocity thinning and a start taper. The round caps make
                 * adjacent segments of different widths join seamlessly.
                 */
                private fun drawStrokeDynamic(
                    canvas: Canvas,
                    stroke: List<Offset>,
                    paint: Paint,
                    baseWidth: Float,
                    wrapAroundMode: Boolean = false,
                    symmetryMode: SymmetryMode = SymmetryMode.NONE,
                ) {
                    if (stroke.size == 1) {
                        drawStroke(canvas, stroke, paint, wrapAroundMode, symmetryMode)
                        return
                    }
                    val widths = com.hereliesaz.graffitixr.feature.editor.BrushDynamics.segmentWidths(stroke, baseWidth)
                    for (i in 0 until stroke.size - 1) {
                        paint.strokeWidth = widths[i]
                        drawStroke(canvas, listOf(stroke[i], stroke[i + 1]), paint, wrapAroundMode, symmetryMode)
                    }
                    paint.strokeWidth = baseWidth
                }

                /**
                 * Scanline flood fill (Procreate's ColorDrop): recolours the contiguous region around
                 * (x, y) whose pixels are within [tolerance] per-channel of the seed pixel. Mutates
                 * [bitmap] in place; a no-op when the seed already matches the fill colour or the
                 * seed lies outside the bitmap. Stack-based, so pathological regions can't recurse out.
                 */
                fun floodFill(
                    bitmap: Bitmap,
                    x: Int,
                    y: Int,
                    fillColor: Int,
                    tolerance: Int = 32,
                    // Lasso selection as a Region, if active. A Canvas clip can't reach a scanline
                    // fill, so containment is tested per pixel instead — the fill spreads only
                    // within the selection, and a seed tapped outside it fills nothing.
                    clipRegion: android.graphics.Region? = null,
                ) {
                    val w = bitmap.width
                    val h = bitmap.height
                    if (x !in 0 until w || y !in 0 until h) return
                    if (clipRegion != null && !clipRegion.contains(x, y)) return
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    val seed = pixels[y * w + x]
                    if (seed == fillColor) return

                    fun allowed(px: Int, py: Int): Boolean =
                        clipRegion == null || clipRegion.contains(px, py)

                    fun matches(c: Int): Boolean {
                        val da = kotlin.math.abs(((c ushr 24) and 0xFF) - ((seed ushr 24) and 0xFF))
                        val dr = kotlin.math.abs(((c ushr 16) and 0xFF) - ((seed ushr 16) and 0xFF))
                        val dg = kotlin.math.abs(((c ushr 8) and 0xFF) - ((seed ushr 8) and 0xFF))
                        val db = kotlin.math.abs((c and 0xFF) - (seed and 0xFF))
                        return da <= tolerance && dr <= tolerance && dg <= tolerance && db <= tolerance
                    }

                    // A pixel joins the fill only if it both matches the seed colour and lies inside
                    // the selection — so the selection edge stops the spread exactly like a colour
                    // boundary does.
                    // Visited set. Without it a fill whose own colour is within tolerance of the
                    // seed never terminates: a pixel already written to fillColor still `matches`,
                    // so each row re-tests and re-pushes its neighbours forever and the stack grows
                    // until the process dies. Reachable by eyedropping a colour and nudging it
                    // slightly — 0xFF000000 seed, 0xFF050505 fill, default tolerance 32.
                    val seen = BooleanArray(w * h)

                    fun fillable(px: Int, py: Int): Boolean {
                        val idx = py * w + px
                        return !seen[idx] && matches(pixels[idx]) && allowed(px, py)
                    }

                    val stack = ArrayDeque<Int>()
                    stack.addLast(y * w + x)
                    while (stack.isNotEmpty()) {
                        val idx = stack.removeLast()
                        val py = idx / w
                        var left = idx % w
                        // Walk to the left edge of this run.
                        while (left > 0 && fillable(left - 1, py)) left--
                        var spanUp = false
                        var spanDown = false
                        var i = left
                        while (i < w && fillable(i, py)) {
                            seen[py * w + i] = true
                            pixels[py * w + i] = fillColor
                            if (py > 0) {
                                val up = fillable(i, py - 1)
                                if (up && !spanUp) { stack.addLast((py - 1) * w + i); spanUp = true }
                                else if (!up) spanUp = false
                            }
                            if (py < h - 1) {
                                val down = fillable(i, py + 1)
                                if (down && !spanDown) { stack.addLast((py + 1) * w + i); spanDown = true }
                                else if (!down) spanDown = false
                            }
                            i++
                        }
                    }
                    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                }

                /**
                 * A cheap separable-ish blur via downscale→upscale with bilinear filtering, scaled by
                 * [factor] (larger = blurrier). Used by the BLUR tool to soften the region under a
                 * stroke without RenderScript (removed in API 31) or a native dependency.
                 */
                private fun cheapBlur(src: Bitmap, factor: Int): Bitmap {
                    val w = (src.width / factor).coerceAtLeast(1)
                    val h = (src.height / factor).coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(src, w, h, true)
                    val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
                    if (small !== up) small.recycle()
                    return up
                }
}
