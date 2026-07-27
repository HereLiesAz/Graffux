package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Selection

/**
 * Pure polygon maths for [Selection] — no Android graphics, so it is unit-testable directly and
 * usable from gesture code that must decide *now*, on the main thread, whether a finger landed
 * inside the selection (a move) or outside it (a new lasso).
 *
 * The Android-side rasterisation of the same region (clip paths, flood-fill masks) lives in the
 * editor's `SelectionMask`; both derive from the same closed polygon, so they agree.
 */
object SelectionGeometry {

    /**
     * True when [point] (screen space) lies in the selected region, honouring
     * [Selection.inverted]. Even-odd ray casting: count the polygon edges a ray cast to +x
     * crosses; odd means inside. Points exactly on an edge may land either way — a half-pixel
     * ambiguity that no gesture can perceive.
     */
    fun contains(selection: Selection, point: Offset): Boolean {
        if (!selection.isUsable) return false
        val inside = insidePolygon(selection.path, point)
        return if (selection.inverted) !inside else inside
    }

    /** Even-odd containment for the raw polygon, ignoring [Selection.inverted]. */
    fun insidePolygon(path: List<Offset>, point: Offset): Boolean {
        if (path.size < 3) return false
        var inside = false
        var j = path.size - 1
        for (i in path.indices) {
            val a = path[i]
            val b = path[j]
            // Does the edge b→a straddle the ray's y? Half-open on purpose ([min, max)) so a
            // vertex shared by two edges is counted once, not twice or zero times.
            if ((a.y > point.y) != (b.y > point.y)) {
                val t = (point.y - a.y) / (b.y - a.y)
                if (point.x < a.x + t * (b.x - a.x)) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Drops points closer than [minSpacing] to the previously kept one. A lasso traced with a
     * finger arrives at touch-event rate and can run to thousands of points; the clip path and the
     * marquee only need enough to be smooth, and every retained point is re-mapped on every paint
     * operation the selection clips.
     */
    fun simplify(path: List<Offset>, minSpacing: Float = 3f): List<Offset> {
        if (path.size < 3) return path
        val out = ArrayList<Offset>(path.size)
        out.add(path.first())
        for (p in path) {
            if ((p - out.last()).getDistance() >= minSpacing) out.add(p)
        }
        // A closed polygon gains nothing from a final point sitting on top of the first.
        if (out.size > 2 && (out.last() - out.first()).getDistance() < minSpacing) {
            out.removeAt(out.size - 1)
        }
        return if (out.size >= 3) out else path
    }
}
