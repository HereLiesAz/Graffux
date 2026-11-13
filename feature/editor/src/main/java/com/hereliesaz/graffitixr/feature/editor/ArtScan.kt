package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.scene.ArtBounds
import kotlin.math.max
import kotlin.math.min

/** Finds where a layer's paint is, for the art-sized flat sheet (spec/3d-space.md, "The art-sized sheet"). */
internal object ArtScan {
    private const val ALPHA_SHIFT = 24

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
     * pixel. This is the one emptiness check ("Adopting the view plane" and the flat sheet both use it).
     */
    fun isEmpty(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(maxOf(w, 0))
        var painted = false
        var y = 0
        while (!painted && y < h && w > 0) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            painted = row.any { it ushr ALPHA_SHIFT != 0 }
            y++
        }
        return !painted
    }
}
