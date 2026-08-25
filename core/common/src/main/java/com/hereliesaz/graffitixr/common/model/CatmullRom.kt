// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/CatmullRom.kt
package com.hereliesaz.graffitixr.common.model

/**
 * Uniform Catmull-Rom spline fitting for raster stroke input — Phase 1 of
 * `docs/Native Rendering Engine Design.md` §5: turns the straight chords [BrushStamps.place] and
 * [ImageProcessor.drawStrokeDynamic]'s per-segment `lineTo` walk have always drawn between touch
 * samples into a smooth curve that still passes through every original sample exactly, instead of
 * a polyline with a visible facet at each one (worst on a fast stroke, where samples land farther
 * apart). Same technique the companion doc's §"Mathematical Interpolation" describes Valkyrie
 * using on `coalescedTouches`/`predictedTouches`.
 *
 * Deliberately applied only at ONE-SHOT call sites (a stroke's full, final point list, known once
 * — commit and undo/redo replay), not at the INCREMENTAL live-preview call sites that redraw only
 * the newly-added tail each frame. Catmull-Rom is local (a segment depends only on its own 4
 * neighbouring points) so extending a point list never changes an INTERIOR segment already fit —
 * but it does change the LAST fitted segment, which used a reflected phantom point as its 4th
 * control point before a real one arrived. An incremental redraw that only ever appends assumes
 * every already-drawn segment is permanently final, which uniform Catmull-Rom's own boundary
 * handling does not guarantee for the most recent one — so live preview stays on the straight
 * chords it already draws, and the curved, smoothed version appears once the stroke commits. This
 * mirrors this session's whole-stroke-opacity fix: the live path approximates, the authoritative
 * path is exact, and the two are allowed to differ transiently.
 *
 * Pure and Android-free, the same interleaved `[x0,y0,x1,y1,…]` convention as [BrushStamps] and
 * [PathSimplify].
 */
object CatmullRom {

    /**
     * One interleaved point run per ORIGINAL segment of [points] (`points.size/2 - 1` segments —
     * empty if fewer than 2 points), each run walking [samplesPerSegment] Catmull-Rom-curved
     * sub-steps from that segment's start point to its end point, both ends inclusive
     * (`(samplesPerSegment + 1) * 2` floats per run). Adjacent runs share their boundary point —
     * harmless to draw/stamp twice, the way every consumer here already draws one straight
     * 2-point sub-line per original segment and can just draw [samplesPerSegment] short curved
     * ones at the same per-segment width instead.
     *
     * Endpoints get a phantom control point by reflection (`P(-1) = 2·P0 − P1`, and symmetrically
     * at the far end) — the standard boundary trick for a spline with no neighbour past the
     * first/last real point. [samplesPerSegment] below 1 is treated as 1 (the original straight
     * chord — Catmull-Rom evaluated at just `t=0,1` IS the chord).
     */
    fun segments(points: List<Float>, samplesPerSegment: Int = 8): List<FloatArray> {
        val n = points.size / 2
        if (n < 2) return emptyList()
        val steps = samplesPerSegment.coerceAtLeast(1)

        fun px(i: Int) = points[2 * i.coerceIn(0, n - 1)]
        fun py(i: Int) = points[2 * i.coerceIn(0, n - 1) + 1]
        // Reflected phantom point past either end; a real point passes through unchanged.
        fun x(i: Int) = when {
            i < 0 -> 2f * px(0) - px(1)
            i >= n -> 2f * px(n - 1) - px(n - 2)
            else -> px(i)
        }
        fun y(i: Int) = when {
            i < 0 -> 2f * py(0) - py(1)
            i >= n -> 2f * py(n - 1) - py(n - 2)
            else -> py(i)
        }

        // Standard uniform Catmull-Rom basis, t ∈ [0,1] between control points b and c.
        fun interp(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f * (
                2f * b +
                    (-a + c) * t +
                    (2f * a - 5f * b + 4f * c - d) * t2 +
                    (-a + 3f * b - 3f * c + d) * t3
                )
        }

        val out = ArrayList<FloatArray>(n - 1)
        for (i in 0 until n - 1) {
            val x0 = x(i - 1); val y0 = y(i - 1)
            val x1 = x(i); val y1 = y(i)
            val x2 = x(i + 1); val y2 = y(i + 1)
            val x3 = x(i + 2); val y3 = y(i + 2)
            val run = FloatArray((steps + 1) * 2)
            for (step in 0..steps) {
                val t = step.toFloat() / steps
                run[2 * step] = interp(x0, x1, x2, x3, t)
                run[2 * step + 1] = interp(y0, y1, y2, y3, t)
            }
            out.add(run)
        }
        return out
    }

    /**
     * [segments], flattened into one interleaved point list (each run's shared boundary point
     * with its neighbour de-duplicated) — for a caller that only wants the curved poly-line, not
     * the per-original-segment breakdown (e.g. feeding [BrushStamps.place]'s own arc-length walk
     * a smoother input polyline).
     */
    fun densify(points: List<Float>, samplesPerSegment: Int = 8): List<Float> {
        val runs = segments(points, samplesPerSegment)
        if (runs.isEmpty()) return points
        val out = ArrayList<Float>(runs.sumOf { it.size } - (runs.size - 1) * 2)
        runs.forEachIndexed { i, run ->
            val start = if (i == 0) 0 else 2 // skip the point shared with the previous run
            for (j in start until run.size) out.add(run[j])
        }
        return out
    }
}
