package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.model.CatmullRom
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.ROUND_BRUSH_DAB_SPACING_FRACTION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import kotlin.math.max
import kotlin.math.roundToInt

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

    }
