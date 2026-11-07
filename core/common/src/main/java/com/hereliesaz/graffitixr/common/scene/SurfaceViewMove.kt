@file:Suppress(
    "MagicNumber",
    "ReturnCount",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "ComplexCondition",
    "MaxLineLength",
)

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Moving selected pixels on a multi-view surface (spec/3d-space.md, "Selection on a multi-view surface").
 *
 * The rule: the selected paint slides over the surface as the viewer sees it. A pixel of a view image
 * receives what was under the canvas point [delta] behind it, found through the surface (the nearest
 * facing triangle there, in whichever view image that triangle belongs to). A drag across a crease is
 * therefore resampled into the neighbouring image by the same projective mapping the surface is drawn
 * with, a face square to the camera moves rigidly in its image, and a pixel carried off the visible surface
 * is lost, like one moved off the edge of a flat layer. The lifted pixels themselves are the caller's
 * (already cut to the selection, feather included); this only works out where they land.
 */
object SurfaceViewMove {
    /** One view image's pixels, as the planner sees them. */
    interface ViewPixels {
        val width: Int
        val height: Int

        /** The continuous pixel position of the paint point, or null off the image. */
        fun pixelOf(x: Float, y: Float): Pair<Float, Float>?

        /** The paint point at a continuous pixel position. */
        fun paintOf(px: Float, py: Float): Pair<Float, Float>?

        /** The lifted pixel (ARGB, transparent outside the selection). */
        fun argb(px: Int, py: Int): Int
    }

    /** Pixels to paste into [view]'s image: [width] by [height] ARGB at ([left], [top]) in its bitmap pixels. */
    class Patch(val view: SurfaceView, val left: Int, val top: Int, val width: Int, val height: Int, val pixels: IntArray)

    /**
     * The patch each view's image receives when the selection (its lifted pixels in [images], within the canvas
     * rectangle [source]) moves by [delta] on the canvas. Views that receive nothing are absent.
     */
    fun plan(
        projection: SurfaceProjection,
        images: Map<SurfaceView, ViewPixels>,
        delta: Offset,
        source: Rect,
    ): Map<SurfaceView, Patch> {
        val out = LinkedHashMap<SurfaceView, Patch>()
        for ((view, img) in images) {
            var xs = IntArray(INITIAL)
            var ys = IntArray(INITIAL)
            var cs = IntArray(INITIAL)
            var n = 0
            for (t in projection.facing(view)) {
                // The triangle can only receive what comes from the selection's rectangle.
                if (t.maxSx - delta.x < source.left || t.minSx - delta.x > source.right ||
                    t.maxSy - delta.y < source.top || t.minSy - delta.y > source.bottom
                ) continue
                var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
                var ok = true
                for (k in 0..2) {
                    val p = img.pixelOf(t.px[k], t.py[k])
                    if (p == null) { ok = false; break }
                    x0 = min(x0, floor(p.first).toInt()); x1 = max(x1, floor(p.first).toInt() + 1)
                    y0 = min(y0, floor(p.second).toInt()); y1 = max(y1, floor(p.second).toInt() + 1)
                }
                if (!ok) continue
                for (py in max(y0, 0)..min(y1, img.height - 1)) {
                    for (px in max(x0, 0)..min(x1, img.width - 1)) {
                        val paint = img.paintOf(px + 0.5f, py + 0.5f) ?: continue
                        if (!t.holdsPaint(paint.first, paint.second, 0f)) continue
                        val at = t.screenAt(paint.first, paint.second) ?: continue
                        val from = Offset(at.x - delta.x, at.y - delta.y)
                        if (!source.contains(from)) continue
                        val hit = projection.surfaceAt(from) ?: continue
                        val src = images[hit.tri.view] ?: continue
                        val q = src.pixelOf(hit.x, hit.y) ?: continue
                        val qx = floor(q.first).toInt()
                        val qy = floor(q.second).toInt()
                        if (qx !in 0 until src.width || qy !in 0 until src.height) continue
                        val c = src.argb(qx, qy)
                        if ((c ushr 24) == 0) continue
                        if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2); cs = cs.copyOf(n * 2) }
                        xs[n] = px; ys[n] = py; cs[n] = c; n++
                    }
                }
            }
            if (n == 0) continue
            var l = Int.MAX_VALUE; var tp = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
            for (i in 0 until n) { l = min(l, xs[i]); r = max(r, xs[i]); tp = min(tp, ys[i]); b = max(b, ys[i]) }
            val w = r - l + 1
            val h = b - tp + 1
            val pixels = IntArray(w * h)
            for (i in 0 until n) pixels[(ys[i] - tp) * w + (xs[i] - l)] = cs[i]
            out[view] = Patch(view, l, tp, w, h, pixels)
        }
        return out
    }

    private const val INITIAL = 1024
}
