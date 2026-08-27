package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.model.CatmullRom
import kotlin.math.max

/**
 * Paints an azphalt stamp-brush stroke onto a bitmap-space [Canvas]. The stroke poly-line is expanded
 * into evenly spaced dabs by [BrushStamps.dabs] (which applies the brush's spacing, size/opacity
 * jitter, scatter and follow-stroke rotation), and each dab is drawn as a radial-gradient disc whose
 * hard core reaches to `brush.hardness` of the radius before fading to transparent — the profile from
 * [BrushStamps.stampCoverage]. Overlapping dabs build up via normal source-over compositing, so a low
 * [flow] paints in gradually (matching [BrushStamps.buildUp]).
 *
 * Sensor-aware strokes arrive through [paintDynamicStroke]. [BrushStamps.dynamicDabs] resolves the
 * Krita-style sensor routes before rendering; this class only consumes concrete dab instructions.
 * That keeps pressure/speed/tilt logic out of Android raster code and makes CPU live/commit/replay use
 * the exact same resolved geometry, flow and colour dynamics.
 *
 * When a `stamp` bitmap is supplied (the brush's [AzphaltBrush.shapePath] tip), each dab draws that
 * image tinted to the resolved dab colour, scaled to the dab diameter and rotated, instead of the
 * generated disc. A brush's [AzphaltBrush.grainPath] texture is honoured in a later pass. Device code
 * — call on a background thread with coordinates already mapped into the target bitmap's pixel space.
 */
internal object StampBrushRenderer {

    /** Legacy/static one-shot path. Existing brushes remain pixel-compatible. */
    fun paintStroke(
        canvas: Canvas,
        points: List<Float>,
        brush: AzphaltBrush,
        colorArgb: Int,
        diameterPx: Float,
        flow: Float,
        seed: Long,
        stamp: Bitmap? = null,
    ) {
        val curved = CatmullRom.densify(points)
        paintDabs(canvas, BrushStamps.dabs(curved, diameterPx, brush, seed), brush, colorArgb, flow, stamp)
    }

    /**
     * Sensor-aware one-shot path. Telemetry kinematics are already recorded in [samples], so only x/y
     * should have been remapped into bitmap space by the caller. [BrushStamps.dynamicDabs] performs its
     * own deterministic arc interpolation and deliberately ignores predicted samples.
     */
    fun paintDynamicStroke(
        canvas: Canvas,
        samples: List<BrushSample>,
        brush: AzphaltBrush,
        colorArgb: Int,
        diameterPx: Float,
        flow: Float,
        seed: Long,
        stamp: Bitmap? = null,
    ) {
        paintDabs(
            canvas,
            BrushStamps.dynamicDabs(samples, diameterPx, brush, seed),
            brush,
            colorArgb,
            flow,
            stamp,
        )
    }

    /**
     * Draw an already-computed list of dabs onto [canvas]. Static dabs carry identity flow/colour
     * multipliers, so the fast legacy behavior is unchanged. Dynamic dabs may independently alter
     * flow and HSV without asking the geometry engine to depend on Android's Color implementation.
     */
    fun paintDabs(
        canvas: Canvas,
        dabs: List<Dab>,
        brush: AzphaltBrush,
        colorArgb: Int,
        flow: Float,
        stamp: Bitmap? = null,
    ) {
        if (dabs.isEmpty()) return
        val baseFlow = flow.coerceIn(0f, 1f)

        if (stamp != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val sw = stamp.width.toFloat()
            val sh = stamp.height.toFloat()
            if (sw <= 0f || sh <= 0f) return
            val m = Matrix()
            for (d in dabs) {
                val dabColor = resolvedColor(colorArgb, d)
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

        val hardness = brush.hardness.coerceIn(0f, 1f)
        val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.999f), 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (d in dabs) {
            val dabColor = resolvedColor(colorArgb, d)
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

    /** Avoid HSV conversion entirely for the overwhelmingly common identity/static dab. */
    internal fun resolvedColor(baseArgb: Int, dab: Dab): Int {
        if (dab.hueShiftDeg == 0f &&
            dab.saturationMultiplier == 1f &&
            dab.valueMultiplier == 1f
        ) return baseArgb

        val hsv = FloatArray(3)
        Color.colorToHSV(baseArgb, hsv)
        hsv[0] = ((hsv[0] + dab.hueShiftDeg) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * dab.saturationMultiplier).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * dab.valueMultiplier).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(baseArgb), hsv)
    }
}
