package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import com.hereliesaz.graffitixr.common.azphalt.MaskedBrushBlendMode
import com.hereliesaz.graffitixr.common.model.CatmullRom
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

private const val GRAIN_SEED_SALT = 0x475241494E5F5048L

/**
 * Android correctness renderer for resolved stamp dabs.
 *
 * The fast historical round/image paths remain untouched for ordinary brushes. Ratio, grain, or a
 * masked second tip opt a dab into the Krita-style mask pipeline: reusable tip masks come from
 * [BrushTipMaskCache], a second tip combines with that mask, texture is applied as a separate stage,
 * and only then is the result tinted/composited onto the destination.
 */
internal object StampBrushRenderer {

    fun paintStroke(
        canvas: Canvas,
        points: List<Float>,
        brush: AzphaltBrush,
        colorArgb: Int,
        diameterPx: Float,
        flow: Float,
        seed: Long,
        stamp: Bitmap? = null,
        grain: Bitmap? = null,
        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
    ) {
        val curved = CatmullRom.densify(points)
        paintDabs(
            canvas,
            BrushStamps.dabs(curved, diameterPx, brush, seed),
            brush,
            colorArgb,
            flow,
            stamp,
            grain,
            maskStamp,
            seed,
            secondaryColorArgb,
        )
    }

    fun paintDynamicStroke(
        canvas: Canvas,
        samples: List<BrushSample>,
        brush: AzphaltBrush,
        colorArgb: Int,
        diameterPx: Float,
        flow: Float,
        seed: Long,
        stamp: Bitmap? = null,
        grain: Bitmap? = null,
        maskStamp: Bitmap? = null,
        secondaryColorArgb: Int = colorArgb,
    ) {
        paintDabs(
            canvas,
            BrushStamps.dynamicDabs(samples, diameterPx, brush, seed),
            brush,
            colorArgb,
            flow,
            stamp,
            grain,
            maskStamp,
            seed,
            secondaryColorArgb,
        )
    }

    fun paintDabs(
        canvas: Canvas,
        dabs: List<Dab>,
        brush: AzphaltBrush,
        colorArgb: Int,
        flow: Float,
        stamp: Bitmap? = null,
        grain: Bitmap? = null,
        maskStamp: Bitmap? = null,
        seed: Long = 0L,
        secondaryColorArgb: Int = colorArgb,
    ) {
        if (dabs.isEmpty()) return
        val advancedMaskPipeline = brush.tipRatio != 1f || grain != null || brush.maskedBrush != null
        if (advancedMaskPipeline) {
            paintMaskedDabs(canvas, dabs, brush, colorArgb, secondaryColorArgb, flow, stamp, grain, maskStamp, seed)
            return
        }

        // Historical shaped-tip path: preserved so existing brushes stay pixel-compatible.
        if (stamp != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val sw = stamp.width.toFloat()
            val sh = stamp.height.toFloat()
            if (sw <= 0f || sh <= 0f) return
            val m = Matrix()
            val baseFlow = flow.coerceIn(0f, 1f)
            for (d in dabs) {
                val dabColor = resolvedColor(colorArgb, secondaryColorArgb, brush, d)
                val diameter = max(d.radius, 0.5f) * 2f
                val alphaVal = (
                    Color.alpha(dabColor) * d.alpha * baseFlow * d.flowMultiplier.coerceAtLeast(0f)
                    ).toInt().coerceIn(0, 255)
                val tint = (dabColor and 0x00FFFFFF) or (alphaVal shl 24)
                paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                m.reset()
                m.postTranslate(-sw / 2f, -sh / 2f)
                m.postScale(diameter / sw, diameter / sh)
                m.postRotate(d.angleDeg)
                m.postTranslate(d.x, d.y)
                canvas.drawBitmap(stamp, m, paint)
            }
            return
        }

        // Historical generated-round path: also intentionally unchanged.
        val hardness = brush.hardness.coerceIn(0f, 1f)
        val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.999f), 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val baseFlow = flow.coerceIn(0f, 1f)
        for (d in dabs) {
            val dabColor = resolvedColor(colorArgb, secondaryColorArgb, brush, d)
            val rgbNoAlpha = dabColor and 0x00FFFFFF
            val radius = max(d.radius, 0.5f)
            val alphaVal = (
                Color.alpha(dabColor) * d.alpha * baseFlow * d.flowMultiplier.coerceAtLeast(0f)
                ).toInt().coerceIn(0, 255)
            val core = rgbNoAlpha or (alphaVal shl 24)
            val edge = rgbNoAlpha
            paint.shader = RadialGradient(
                d.x, d.y, radius,
                intArrayOf(core, core, edge),
                stops,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(d.x, d.y, radius, paint)
        }
        paint.shader = null
    }

    private fun paintMaskedDabs(
        destination: Canvas,
        dabs: List<Dab>,
        brush: AzphaltBrush,
        baseColor: Int,
        secondaryColor: Int,
        flow: Float,
        stamp: Bitmap?,
        grain: Bitmap?,
        maskStamp: Bitmap?,
        seed: Long,
    ) {
        val scratch = DabScratch()
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val baseFlow = flow.coerceIn(0f, 1f)
        val grainTile = grain?.let {
            BrushTipMaskCache.grainMask(it, brush.grainBlendMode, brush.grainStrength)
        }
        val random = if (brush.grainRandomOffsetPerStroke && grainTile != null) {
            Random(seed xor GRAIN_SEED_SALT)
        } else null
        val grainPhaseX = brush.grainOffsetX + (random?.nextFloat()?.times(grainTile?.width ?: 0) ?: 0f)
        val grainPhaseY = brush.grainOffsetY + (random?.nextFloat()?.times(grainTile?.height ?: 0) ?: 0f)

        try {
            dabs.forEach { dab ->
                val primaryWidth = ceil(max(dab.radius, 0.5f) * 2f).toInt().coerceAtLeast(1)
                val primaryHeight = ceil(primaryWidth * dab.tipRatio.coerceIn(0.05f, 1f)).toInt().coerceAtLeast(1)
                val primaryHalfDiagonal = hypot(primaryWidth / 2f, primaryHeight / 2f)
                val secondary = dab.mask
                val secondaryWidth = secondary?.let { ceil(max(it.radius, 0.5f) * 2f).toInt().coerceAtLeast(1) } ?: 0
                val secondaryHeight = secondary?.let {
                    ceil(secondaryWidth * it.tipRatio.coerceIn(0.05f, 1f)).toInt().coerceAtLeast(1)
                } ?: 0
                val secondaryExtent = secondary?.let {
                    hypot(it.x - dab.x, it.y - dab.y) + hypot(secondaryWidth / 2f, secondaryHeight / 2f)
                } ?: 0f
                val extent = ceil(max(primaryHalfDiagonal, secondaryExtent)).toInt() + 3
                val side = extent * 2 + 1
                scratch.ensure(side)
                scratch.primary.eraseColor(Color.TRANSPARENT)

                val left = floor(dab.x - extent).toInt()
                val top = floor(dab.y - extent).toInt()
                val localPrimaryX = dab.x - left
                val localPrimaryY = dab.y - top
                val primaryMask = BrushTipMaskCache.tipMask(
                    stamp, primaryWidth, primaryHeight, brush.hardness,
                )
                drawCenteredMask(
                    scratch.primaryCanvas,
                    primaryMask,
                    localPrimaryX,
                    localPrimaryY,
                    dab.angleDeg,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )

                secondary?.let { maskDab ->
                    scratch.secondary.eraseColor(Color.TRANSPARENT)
                    val secondaryTip = BrushTipMaskCache.tipMask(
                        maskStamp,
                        secondaryWidth,
                        secondaryHeight,
                        brush.maskedBrush?.hardness ?: 1f,
                    )
                    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (maskDab.alpha * maskDab.flowMultiplier * 255f)
                            .roundToInt().coerceIn(0, 255)
                    }
                    drawCenteredMask(
                        scratch.secondaryCanvas,
                        secondaryTip,
                        maskDab.x - left,
                        maskDab.y - top,
                        maskDab.angleDeg,
                        maskPaint,
                    )
                    val keepInside = when (maskDab.blendMode) {
                        MaskedBrushBlendMode.MULTIPLY -> !maskDab.invert
                        MaskedBrushBlendMode.SUBTRACT -> maskDab.invert
                    }
                    val xfer = Paint().apply {
                        xfermode = PorterDuffXfermode(
                            if (keepInside) PorterDuff.Mode.DST_IN else PorterDuff.Mode.DST_OUT
                        )
                    }
                    scratch.primaryCanvas.drawBitmap(scratch.secondary, 0f, 0f, xfer)
                    xfer.xfermode = null
                }

                if (grainTile != null && brush.grainStrength > 0f) {
                    applyGrain(
                        scratch.primary,
                        grainTile,
                        brush.grainBehavior,
                        brush.grainScale,
                        left,
                        top,
                        grainPhaseX,
                        grainPhaseY,
                    )
                }

                val dabColor = resolvedColor(baseColor, secondaryColor, brush, dab)
                val alphaVal = (
                    Color.alpha(dabColor) * dab.alpha * baseFlow * dab.flowMultiplier.coerceAtLeast(0f)
                    ).roundToInt().coerceIn(0, 255)
                if (alphaVal == 0) return@forEach
                val opaqueTint = (dabColor and 0x00FFFFFF) or 0xFF000000.toInt()
                drawPaint.alpha = alphaVal
                drawPaint.colorFilter = PorterDuffColorFilter(opaqueTint, PorterDuff.Mode.SRC_IN)
                destination.drawBitmap(scratch.primary, left.toFloat(), top.toFloat(), drawPaint)
            }
        } finally {
            scratch.close()
        }
    }

    private fun drawCenteredMask(
        canvas: Canvas,
        mask: Bitmap,
        centerX: Float,
        centerY: Float,
        angleDeg: Float,
        paint: Paint,
    ) {
        val save = canvas.save()
        canvas.translate(centerX, centerY)
        if (angleDeg != 0f) canvas.rotate(angleDeg)
        canvas.drawBitmap(mask, -mask.width / 2f, -mask.height / 2f, paint)
        canvas.restoreToCount(save)
    }

    /** Apply a processed tiling grain to the composed dab mask without changing its RGB. */
    private fun applyGrain(
        mask: Bitmap,
        grain: Bitmap,
        behavior: GrainBehavior,
        scale: Float,
        globalLeft: Int,
        globalTop: Int,
        phaseX: Float,
        phaseY: Float,
    ) {
        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)
        val grainPixels = IntArray(grain.width * grain.height)
        grain.getPixels(grainPixels, 0, grain.width, 0, 0, grain.width, grain.height)
        val scaleSafe = scale.coerceAtLeast(0.05f)

        fun wrap(value: Int, size: Int): Int {
            val m = value % size
            return if (m < 0) m + size else m
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val currentAlpha = Color.alpha(pixels[i])
                if (currentAlpha == 0) continue
                val sourceX = when (behavior) {
                    GrainBehavior.MOVING -> x.toFloat()
                    GrainBehavior.CANVAS_LOCKED -> (globalLeft + x).toFloat()
                }
                val sourceY = when (behavior) {
                    GrainBehavior.MOVING -> y.toFloat()
                    GrainBehavior.CANVAS_LOCKED -> (globalTop + y).toFloat()
                }
                val gx = wrap(floor(sourceX / scaleSafe + phaseX).toInt(), grain.width)
                val gy = wrap(floor(sourceY / scaleSafe + phaseY).toInt(), grain.height)
                val grainAlpha = Color.alpha(grainPixels[gy * grain.width + gx])
                val outAlpha = (currentAlpha * grainAlpha / 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = (outAlpha shl 24) or 0x00FFFFFF
            }
        }
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    internal fun resolvedColor(baseArgb: Int, secondaryArgb: Int, brush: AzphaltBrush, dab: Dab): Int {
        val sourced = when (brush.colorSource) {
            BrushColorSource.PLAIN -> baseArgb
            BrushColorSource.GRADIENT -> lerpArgb(baseArgb, secondaryArgb, dab.colorMix)
            BrushColorSource.UNIFORM_RANDOM -> lerpArgb(baseArgb, secondaryArgb, dab.sourceRandom)
        }
        if (dab.hueShiftDeg == 0f &&
            dab.saturationMultiplier == 1f &&
            dab.valueMultiplier == 1f
        ) return sourced

        val hsv = FloatArray(3)
        Color.colorToHSV(sourced, hsv)
        hsv[0] = ((hsv[0] + dab.hueShiftDeg) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * dab.saturationMultiplier).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * dab.valueMultiplier).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(sourced), hsv)
    }

    private fun lerpArgb(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(ca: Int, cb: Int): Int = (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
        return Color.argb(
            channel(Color.alpha(a), Color.alpha(b)),
            channel(Color.red(a), Color.red(b)),
            channel(Color.green(a), Color.green(b)),
            channel(Color.blue(a), Color.blue(b)),
        )
    }

    private class DabScratch {
        lateinit var primary: Bitmap
            private set
        lateinit var secondary: Bitmap
            private set
        lateinit var primaryCanvas: Canvas
            private set
        lateinit var secondaryCanvas: Canvas
            private set
        private var side = 0

        fun ensure(requiredSide: Int) {
            if (side >= requiredSide && ::primary.isInitialized && !primary.isRecycled) return
            close()
            side = requiredSide
            primary = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            secondary = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            primaryCanvas = Canvas(primary)
            secondaryCanvas = Canvas(secondary)
        }

        fun close() {
            if (::primary.isInitialized && !primary.isRecycled) primary.recycle()
            if (::secondary.isInitialized && !secondary.isRecycled) secondary.recycle()
            side = 0
        }
    }
}
