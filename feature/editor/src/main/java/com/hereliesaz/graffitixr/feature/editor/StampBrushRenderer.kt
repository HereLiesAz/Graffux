package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.Dab
import kotlin.math.max

/**
 * Paints an azphalt stamp-brush stroke onto a bitmap-space [Canvas]. The stroke poly-line is expanded
 * into evenly spaced dabs by [BrushStamps.dabs] (which applies the brush's spacing, size/opacity
 * jitter, scatter and follow-stroke rotation), and each dab is drawn as a radial-gradient disc whose
 * hard core reaches to `brush.hardness` of the radius before fading to transparent — the profile from
 * [BrushStamps.stampCoverage]. Overlapping dabs build up via normal source-over compositing, so a low
 * [flow] paints in gradually (matching [BrushStamps.buildUp]).
 *
 * The renderer is round-tip only for now (a generated soft disc); a bespoke [AzphaltBrush.shapePath]
 * stamp image and [AzphaltBrush.grainPath] texture are honoured in a later pass. Device code — call on
 * a background thread with [points] already mapped into the target bitmap's pixel space.
 */
internal object StampBrushRenderer {

    /**
     * Stamp [brush] along [points] (interleaved `[x0,y0,…]`, bitmap space) onto [canvas] in [colorArgb]
     * at tip [diameterPx], modulating each dab by [flow] (0..1). [seed] makes the jitter deterministic
     * so a replayed stroke re-composites identically — pass a stable per-stroke seed.
     */
    fun paintStroke(
        canvas: Canvas,
        points: List<Float>,
        brush: AzphaltBrush,
        colorArgb: Int,
        diameterPx: Float,
        flow: Float,
        seed: Long,
    ) {
        paintDabs(canvas, BrushStamps.dabs(points, diameterPx, brush, seed), brush, colorArgb, flow)
    }

    /**
     * Draw an already-computed list of [dabs] (from [BrushStamps.dabs]) onto [canvas]. Split out so the
     * live preview can stamp *only the newly-added* dabs each drag frame — because [BrushStamps.dabs]
     * grows a stable prefix (earlier dab positions and their seeded jitter don't change as the stroke
     * extends), re-drawing just `dabs.subList(alreadyDrawn, size)` incrementally matches a full re-render.
     */
    fun paintDabs(
        canvas: Canvas,
        dabs: List<Dab>,
        brush: AzphaltBrush,
        colorArgb: Int,
        flow: Float,
    ) {
        if (dabs.isEmpty()) return
        val hardness = brush.hardness.coerceIn(0f, 1f)
        val f = flow.coerceIn(0f, 1f)
        val baseAlpha = Color.alpha(colorArgb)
        val rgb = colorArgb or 0xFF000000.toInt()   // opaque version; the gradient carries the alpha
        val edge = rgb and 0x00FFFFFF                // transparent edge (alpha 0) — loop-invariant
        // Solid to `hardness` of the radius, then ramp to transparent at the edge — loop-invariant.
        val stops = floatArrayOf(0f, hardness.coerceIn(0f, 0.999f), 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (d in dabs) {
            val radius = max(d.radius, 0.5f)
            val alphaVal = (baseAlpha * d.alpha * f).toInt().coerceIn(0, 255)
            val core = (rgb and 0x00FFFFFF) or (alphaVal shl 24)
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
}
