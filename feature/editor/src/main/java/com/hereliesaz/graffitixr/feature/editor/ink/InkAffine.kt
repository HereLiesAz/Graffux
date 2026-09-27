package com.hereliesaz.graffitixr.feature.editor.ink

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2D affine transform `x' = a·x + b·y + c`, `y' = d·x + e·y + f` — the one shape every coordinate
 * space on the Jetpack Ink path is related by. Pure Kotlin (no `android.graphics.Matrix`, no Ink
 * classes) so the mapping can be unit-tested on the JVM; [toMatrixValues] hands it to a `Matrix`
 * at the Android edge.
 *
 * The Ink path has three spaces:
 * - **screen**: the canvas composable's own pixels, where MotionEvents land;
 * - **world**: screen with the viewport camera (pan/zoom/rotate) taken out — the space every
 *   [com.hereliesaz.graffitixr.feature.editor.StrokeCommand] path is recorded in, and the space the
 *   Ink `Stroke` is built in (so its inputs line up with the command's path point for point);
 * - **bitmap**: the active layer's pixels, world with the layer's own scale/offset/rotation and the
 *   ContentScale.Fit letterbox taken out.
 */
internal data class InkAffine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
) {
    fun mapX(x: Float, y: Float): Float = a * x + b * y + c
    fun mapY(x: Float, y: Float): Float = d * x + e * y + f

    /** `this ∘ first`: applies [first], then this. */
    fun after(first: InkAffine): InkAffine = InkAffine(
        a = a * first.a + b * first.d,
        b = a * first.b + b * first.e,
        c = a * first.c + b * first.f + c,
        d = d * first.a + e * first.d,
        e = d * first.b + e * first.e,
        f = d * first.c + e * first.f + f,
    )

    /** The same transform translated by ([dx], [dy]) afterwards — one Wrap Around tile. */
    fun translated(dx: Float, dy: Float): InkAffine = copy(c = c + dx, f = f + dy)

    /** Row-major 3x3 values, the layout `android.graphics.Matrix.setValues` expects. */
    fun toMatrixValues(): FloatArray = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)

    companion object {
        val IDENTITY = InkAffine(1f, 0f, 0f, 0f, 1f, 0f)

        fun translation(dx: Float, dy: Float) = InkAffine(1f, 0f, dx, 0f, 1f, dy)

        /**
         * Screen → world: exactly [com.hereliesaz.graffitixr.feature.editor.CanvasHitTest.screenToWorld]
         * (subtract the viewport offset, divide by zoom, rotate by -rotation) as a matrix, so a
         * stroke Ink records matches what the existing brush path would have been handed.
         */
        fun screenToWorld(offsetX: Float, offsetY: Float, zoom: Float, rotationDeg: Float): InkAffine {
            val z = if (zoom > MIN_SCALE) zoom else 1f
            val rad = Math.toRadians(-rotationDeg.toDouble())
            val cs = cos(rad).toFloat() / z
            val sn = sin(rad).toFloat() / z
            // world = R(-rot) · (screen - offset) / z
            return InkAffine(
                a = cs, b = -sn, c = -(cs * offsetX - sn * offsetY),
                d = sn, e = cs, f = -(sn * offsetX + cs * offsetY),
            )
        }

        /**
         * World → layer bitmap: exactly `ImageProcessor.mapScreenToBitmap` (whose "screen" is the
         * world space strokes are recorded in) as a matrix — undo the layer translation about the
         * canvas centre, undo its rotation, undo its scale, then undo the ContentScale.Fit
         * letterbox. Its linear scale is `ImageProcessor.screenToBitmapScale`, which is why an Ink
         * brush sized in world units lands at the same bitmap width the round brush's
         * `brushSize * brushScale` does.
         */
        @Suppress("LongParameterList") // Mirrors mapScreenToBitmap's own parameter list one for one.
        fun worldToBitmap(
            canvasWidth: Int,
            canvasHeight: Int,
            bitmapWidth: Int,
            bitmapHeight: Int,
            layerScale: Float,
            layerOffsetX: Float,
            layerOffsetY: Float,
            layerRotationDeg: Float,
        ): InkAffine {
            if (minOf(canvasWidth, canvasHeight, bitmapWidth, bitmapHeight) <= 0) return IDENTITY
            val cx = canvasWidth / 2f
            val cy = canvasHeight / 2f
            val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
            val screenAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
            val renderWidth: Float
            val renderHeight: Float
            if (imageAspect > screenAspect) {
                renderWidth = canvasWidth.toFloat()
                renderHeight = canvasWidth / imageAspect
            } else {
                renderHeight = canvasHeight.toFloat()
                renderWidth = canvasHeight * imageAspect
            }
            val fitOffX = (canvasWidth - renderWidth) / 2f
            val fitOffY = (canvasHeight - renderHeight) / 2f
            val fitScaleX = bitmapWidth / renderWidth
            val fitScaleY = bitmapHeight / renderHeight
            val s = if (abs(layerScale) > MIN_SCALE) layerScale else 1f
            val rad = Math.toRadians(-layerRotationDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            // Step by step, as mapScreenToBitmap does it, composed right to left.
            val toPivot = translation(-cx - layerOffsetX, -cy - layerOffsetY)
            val unrotate = InkAffine(cosA, -sinA, 0f, sinA, cosA, 0f)
            val unscale = InkAffine(1f / s, 0f, 0f, 0f, 1f / s, 0f)
            val fromPivot = translation(cx - fitOffX, cy - fitOffY)
            val fit = InkAffine(fitScaleX, 0f, 0f, 0f, fitScaleY, 0f)
            return fit.after(fromPivot).after(unscale).after(unrotate).after(toPivot)
        }

        private const val MIN_SCALE = 1e-4f
    }
}
