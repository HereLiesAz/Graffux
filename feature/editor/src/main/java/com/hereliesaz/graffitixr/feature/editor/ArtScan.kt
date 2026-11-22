package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.scene.ArtBounds
import kotlin.math.max
import kotlin.math.min

/** Finds where a layer's paint is, for the art-sized flat sheet (spec/3d-space.md, "The art-sized sheet"). */
internal object ArtScan {
    private const val ALPHA_SHIFT = 24
    private const val CHECK_ROWS = 16

    /**
     * The bounds of [bitmap]'s non-transparent pixels, or null when every pixel is transparent. One row at a time
     * into one reusable buffer, so a large bitmap is never copied whole. Reads the alpha byte directly (not
     * `Color.alpha`, which a JVM test stubs). Meant for a background thread; a bitmap recycled or resized under
     * the scan throws IllegalStateException or IllegalArgumentException, which the caller turns into "cannot tell".
     */
    fun bounds(bitmap: Bitmap): ArtBounds? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val row = IntArray(w)
        var left = w
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val first = row.indexOfFirst { it ushr ALPHA_SHIFT != 0 }
            if (first < 0) continue
            val last = row.indexOfLast { it ushr ALPHA_SHIFT != 0 }
            if (top < 0) top = y
            bottom = y
            left = min(left, first)
            right = max(right, last)
        }
        return if (top < 0) null else ArtBounds(left, top, right + 1, bottom + 1)
    }

    /**
     * Whether every pixel of [bitmap] is transparent: the same alpha test as [bounds], stopping at the first painted
     * pixel, reading rows from the top. An empty bitmap costs every row; so does paint only near the bottom, so the
     * early
     * exit is no bound. [checkpoint] runs every [CHECK_ROWS] rows: a background caller passes `ensureActive` so a
     * cancelled scan stops there (it throws). Pure: touches no shared state, so it is safe on any thread. This is the
     * one
     * emptiness check ("Adopting the view plane" and the flat sheet both use it).
     */
    fun isEmpty(bitmap: Bitmap, checkpoint: () -> Unit = {}): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(maxOf(w, 0))
        var painted = false
        var y = 0
        while (!painted && y < h && w > 0) {
            if (y % CHECK_ROWS == 0) checkpoint()
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            painted = row.any { it ushr ALPHA_SHIFT != 0 }
            y++
        }
        return !painted
    }

    /** Samples per side of [probe]'s grid. */
    private const val PROBE_SIDE = 128
    private const val HALF = 0.5f

    /**
     * A bounded emptiness probe for the main thread: whether any of a [PROBE_SIDE] x [PROBE_SIDE] grid of pixels is
     * painted. It reads at most 16,384 pixels whatever the bitmap's size, against every pixel for [isEmpty] on an empty
     * one; that is a bound on the work, not a measured time on a device (a JVM-only measurement is in the spec). A
     * true answer is exact; false means only "no painted sample", so paint thinner than the
     * grid's spacing is missed. Callers use it as evidence, never to move or discard anything. A bitmap that cannot be
     * read pixel by pixel (hardware, recycled) answers false.
     */
    fun probe(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        return try {
            (0 until PROBE_SIDE).any { j ->
                val y = ((j + HALF) * h / PROBE_SIDE).toInt().coerceIn(0, h - 1)
                (0 until PROBE_SIDE).any { i ->
                    val x = ((i + HALF) * w / PROBE_SIDE).toInt().coerceIn(0, w - 1)
                    bitmap.getPixel(x, y) ushr ALPHA_SHIFT != 0
                }
            }
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
