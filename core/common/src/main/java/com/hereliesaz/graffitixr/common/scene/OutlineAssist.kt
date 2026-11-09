@file:Suppress(
    "MagicNumber", "ReturnCount", "LongParameterList", "TooManyFunctions", "MaxLineLength", "ComplexCondition",
    "CyclomaticComplexMethod", "LargeClass", "LongMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "ComplexMethod",
)

package com.hereliesaz.graffitixr.common.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Surface-outline assist (spec/3d-space.md, "Outline assist"): cleans a freehand surface outline
 * (the front, side or top profile stroke of "Draw surface") when the finger lifts, so a square drawn
 * wobbly is a square. Pure Kotlin on canvas points, run once per stroke, before
 * [SurfaceMath.capture] casts them onto the surface.
 *
 * What it does, in order (every threshold is a named, documented field of [Config]):
 *  1. A tiny or degenerate stroke is returned unchanged (the very same list).
 *  2. The stroke is *closed* when its ends meet ([Config.closeGapFraction]) and it encloses area.
 *  3. A closed stroke that fits an ellipse ([Config.ellipseFitTolerance]) becomes a circle
 *     ([Config.circleAspectTolerance]) or an ellipse (axis-aligned within [Config.axisSnapDegrees]).
 *  4. Otherwise the stroke is split at its corners ([Config.cornerAngleDegrees]); a piece that is
 *     nearly straight ([Config.straightFraction]) becomes exactly straight, snapped horizontal or
 *     vertical when near ([Config.axisSnapDegrees]) and meeting its neighbours at exactly 90 degrees
 *     when near ([Config.rightAngleDegrees]); a piece that is not straight keeps its shape with the
 *     jitter smoothed out. Corners stay corners. A closed four-sided result with square corners is a
 *     rectangle, or a square when its sides are within [Config.squareAspectTolerance]; a closed
 *     three-sided one is equilateral when its sides are within [Config.equalSideTolerance].
 *  5. Mirror symmetry: a shape that is nearly symmetric about a vertical axis (and, closed, a
 *     horizontal one) within [Config.symmetryRmsFraction] / [Config.symmetryMaxFraction] is made
 *     exactly symmetric by averaging its two halves; a lopsided one is left alone.
 *
 * The result always passes [isValidOutline] (else the input is returned): finite, at most
 * [Config.maxPoints] points, long enough for [SurfaceMath.capture], and still enclosing area when the
 * input did. A closed result ends on its first point.
 *
 * Idempotent: `assist(assist(x)) == assist(x)` exactly for lines, rectangles, squares and triangles
 * and to well under 0.1% of the stroke's size for ellipses and smoothed curves (checked in
 * OutlineAssistTest).
 */
object OutlineAssist {
    /**
     * Every threshold, in one place. Lengths are fractions of the stroke's bounding-box diagonal `D`
     * (so they hold at any zoom), angles are degrees. The defaults were tuned on seeded noisy inputs
     * (jitter up to about 1% of D) in OutlineAssistTest; they still need a device check with real
     * finger input.
     */
    data class Config(
        /** A stroke whose path length or bounding diagonal is below this (canvas px) is returned unchanged. */
        val minSizePx: Double = 12.0,
        /** The ends of a stroke are "meeting" when their gap is at most this fraction of the path length. */
        val closeGapFraction: Double = 0.12,
        /** For a thin closed shape, tolerances scale with this many times its thickness (never more than the diagonal). */
        val thinShapeScale: Double = 3.0,
        /** ...but never below this fraction of the diagonal. */
        val minScaleFraction: Double = 0.05,
        /** A closed cleanup that leaves less or more than this fraction of the drawn area is not trusted; the stroke is kept. */
        val areaKeepMin: Double = 0.75,
        /** See [areaKeepMin]. */
        val areaKeepMax: Double = 1.33,
        /** An open cleanup that moves an end further than this fraction of the diagonal is not trusted. */
        val endMoveFraction: Double = 0.15,
        /** Corner detection simplifies the stroke to this tolerance first (Ramer-Douglas-Peucker). */
        val cornerSimplifyFraction: Double = 0.03,
        /** A bend of at least this many degrees (summed over neighbouring vertices) is a corner. */
        val cornerAngleDegrees: Double = 60.0,
        /** A corner must also turn this many degrees over the stroke [cornerWindowFraction] either side of it (a wavy side is not a corner). */
        val cornerFineAngleDegrees: Double = 50.0,
        /** The arc length each side of a corner over which [cornerFineAngleDegrees] is measured. */
        val cornerWindowFraction: Double = 0.06,
        /** Neighbouring bends are summed into one corner only while they total at most this many degrees. */
        val cornerClusterMaxDegrees: Double = 150.0,
        /** Vertices closer than this are one bend when their turning angles are summed (a rounded corner). */
        val cornerClusterFraction: Double = 0.12,
        /** A piece is straight when no point strays further than this fraction of its chord. */
        val straightFraction: Double = 0.05,
        /** A straight piece within this many degrees of horizontal or vertical is made exactly so. */
        val axisSnapDegrees: Double = 8.0,
        /** An angle between two straight pieces within this many degrees of 90 is made exactly 90. */
        val rightAngleDegrees: Double = 12.0,
        /** Straight pieces within this many degrees of parallel to the reference side are made exactly parallel (tighter than a right angle: a trapezoid's legs are not parallel). */
        val parallelAngleDegrees: Double = 3.0,
        /** A rectangle whose sides differ by at most this fraction of the longer is a square. */
        val squareAspectTolerance: Double = 0.12,
        /** A triangle whose sides differ by at most this fraction of their mean is equilateral. */
        val equalSideTolerance: Double = 0.10,
        /** A closed stroke is an ellipse when its mean radial deviation from the fitted ellipse is at most this (relative radius). */
        val ellipseFitTolerance: Double = 0.05,
        /** An ellipse whose axes differ by at most this fraction of the longer is a circle. */
        val circleAspectTolerance: Double = 0.12,
        /** A near-circular fit is a regular polygon, not a circle, when its radius swings this much (relative) with this many sides per turn. */
        val polygonHarmonicTolerance: Double = 0.02,
        /** Fewest sides of a regular polygon recognised this way (fewer sides are found by their corners). */
        val polygonMinSides: Int = 5,
        /** Most sides of a regular polygon recognised this way (more look like a circle). */
        val polygonMaxSides: Int = 8,
        /** The cleaned closed outline starts at the stroke's first point; within this fraction of the diagonal of a vertex it starts on that vertex. */
        val startSnapFraction: Double = 0.01,
        /** An elongated ellipse also gets this many vertices spaced evenly in direction, so its tips are round. */
        val ellipseTipSteps: Int = 72,
        /** Vertices of the ellipse or circle drawn in place of the stroke. */
        val ellipseVertices: Int = 64,
        /** Smoothing samples are this far apart along a curve. */
        val smoothStepFraction: Double = 0.01,
        /** Number of [1 2 1]/4 smoothing passes over the resampled curve. */
        val smoothPasses: Int = 6,
        /** A curve whose smoothing moves it less than this is already clean and kept as drawn. */
        val settledFraction: Double = 0.004,
        /** Smoothed curves are simplified to this tolerance. */
        val curveSimplifyFraction: Double = 0.0015,
        /** Mirror symmetry is accepted when the RMS distance between the shape and its mirror image is at most this. */
        val symmetryRmsFraction: Double = 0.025,
        /** ...and the largest such distance is at most this. */
        val symmetryMaxFraction: Double = 0.07,
        /** A shape already symmetric to within this is left untouched. */
        val symmetrySettledFraction: Double = 0.001,
        /** A corner this close to the symmetry axis (or to the middle of an open stroke) is taken to lie on it. */
        val symmetryAxisSnapFraction: Double = 0.03,
        /** A result with more points than this is rejected (the profile validator's limit, [SurfaceMath.MAX_POINTS]). */
        val maxPoints: Int = SurfaceMath.MAX_POINTS,
        /** A result shorter than this (canvas px) is rejected (the profile validator's minimum length). */
        val minResultLengthPx: Double = 4.0,
    )

    val DEFAULT = Config()

    /** Whether [points] is a stroke [SurfaceMath.capture] and the hull accept: finite, long enough, within the point limit. */
    fun isValidOutline(points: List<Pair<Float, Float>>, config: Config = DEFAULT): Boolean {
        if (points.size < 2 || points.size > config.maxPoints) return false
        var length = 0.0
        for (i in points.indices) {
            val p = points[i]
            if (!p.first.isFinite() || !p.second.isFinite()) return false
            if (i > 0) length += hypot((p.first - points[i - 1].first).toDouble(), (p.second - points[i - 1].second).toDouble())
        }
        return length >= config.minResultLengthPx
    }

    /**
     * [points] cleaned up (see the class doc), or [points] itself when it is too small to be a shape,
     * the assist would make it invalid, or it is already clean.
     */
    fun assist(points: List<Pair<Float, Float>>, config: Config = DEFAULT): List<Pair<Float, Float>> {
        if (points.size < 2) return points
        val raw = dedupe(points) ?: return points
        val n = raw.size / 2
        if (n < 2) return points
        val box = Box.of(raw, n)
        val length = pathLength(raw, n)
        if (length < config.minSizePx || box.diag < config.minSizePx) return points
        // Jitter inflates a stroke's length; judge the end gap on a lightly simplified copy. Whether it encloses
        // area is the profile's own rule (SurfacePayload.isClosedOutline), not a shape-recognition threshold.
        val keep = rdp(raw, 0, n - 1, config.cornerSimplifyFraction * box.diag)
        val lite = DoubleArray(keep.size * 2)
        for (i in keep.indices) { lite[i * 2] = raw[keep[i] * 2]; lite[i * 2 + 1] = raw[keep[i] * 2 + 1] }
        val closed = n >= 3 && hypot(raw[0] - raw[n * 2 - 2], raw[1] - raw[n * 2 - 1]) <= config.closeGapFraction * pathLength(lite, keep.size) &&
            SurfacePayload.isClosedOutline(points)
        // Tolerances scale with the stroke's size; a thin closed shape (a bar) scales with its thickness instead.
        val d = if (closed) max(min(box.diag, config.thinShapeScale * minorExtent(raw, n)), config.minScaleFraction * box.diag) else box.diag
        val loop = if (closed) raw.copyOf(raw.size + 2).also { it[raw.size] = raw[0]; it[raw.size + 1] = raw[1] } else raw
        val shape = (if (closed) assistClosed(loop, d, config) else assistOpen(loop, d, config)) ?: return points
        val symmetric = (if (shape.exact) null else symmetrise(shape, d, config)) ?: shape
        val finalXy = if (closed) orient(symmetric.xy, raw, d, config) else symmetric.xy
        val out = ArrayList<Pair<Float, Float>>(finalXy.size / 2)
        for (i in 0 until finalXy.size / 2) out += finalXy[i * 2].toFloat() to finalXy[i * 2 + 1].toFloat()
        if (!isValidOutline(out, config)) return points
        if (closed && !SurfacePayload.isClosedOutline(out)) return points
        if (!preservesShape(raw, n, finalXy, closed, box.diag, config)) return points
        return out
    }

    // ---------------------------------------------------------------- shapes

    /** A cleaned stroke: [xy] interleaved, [corner] per point; a closed one ends on its first point. */
    private class Shape(val xy: DoubleArray, val corner: BooleanArray, val closed: Boolean, val exact: Boolean = false) {
        val n: Int get() = xy.size / 2
    }

    private class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val diag: Double get() = hypot(maxX - minX, maxY - minY)
        val cx: Double get() = (minX + maxX) / 2
        val cy: Double get() = (minY + maxY) / 2

        companion object {
            fun of(xy: DoubleArray, n: Int): Box {
                var x0 = Double.MAX_VALUE
                var y0 = Double.MAX_VALUE
                var x1 = -Double.MAX_VALUE
                var y1 = -Double.MAX_VALUE
                for (i in 0 until n) {
                    x0 = min(x0, xy[i * 2]); x1 = max(x1, xy[i * 2])
                    y0 = min(y0, xy[i * 2 + 1]); y1 = max(y1, xy[i * 2 + 1])
                }
                return Box(x0, y0, x1, y1)
            }
        }
    }

    private fun dedupe(points: List<Pair<Float, Float>>): DoubleArray? {
        val out = DoubleArray(points.size * 2)
        var n = 0
        for (p in points) {
            if (!p.first.isFinite() || !p.second.isFinite()) return null
            if (n > 0 && hypot(p.first - out[n * 2 - 2], p.second - out[n * 2 - 1]) < 1e-6) continue
            out[n * 2] = p.first.toDouble()
            out[n * 2 + 1] = p.second.toDouble()
            n++
        }
        return out.copyOf(n * 2)
    }

    private fun pathLength(xy: DoubleArray, n: Int): Double {
        var s = 0.0
        for (i in 1 until n) s += hypot(xy[i * 2] - xy[i * 2 - 2], xy[i * 2 + 1] - xy[i * 2 - 1])
        return s
    }

    /** The thickness of [xy]'s points across their principal axis: the shorter extent in the frame of the covariance axes. */
    private fun minorExtent(xy: DoubleArray, n: Int): Double {
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) { mx += xy[i * 2]; my += xy[i * 2 + 1] }
        mx /= n; my /= n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xy[i * 2] - mx
            val dy = xy[i * 2 + 1] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val a = 0.5 * atan2(2 * sxy, sxx - syy)
        val ca = cos(a)
        val sa = sin(a)
        var u0 = Double.MAX_VALUE
        var u1 = -Double.MAX_VALUE
        var v0 = Double.MAX_VALUE
        var v1 = -Double.MAX_VALUE
        for (i in 0 until n) {
            val dx = xy[i * 2] - mx
            val dy = xy[i * 2 + 1] - my
            val u = dx * ca + dy * sa
            val v = -dx * sa + dy * ca
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
        }
        return min(u1 - u0, v1 - v0)
    }

    /** A cleanup that changed the enclosed area, or moved an open stroke's ends, by more than expected is not trusted. */
    private fun preservesShape(raw: DoubleArray, n: Int, out: DoubleArray, closed: Boolean, diag: Double, c: Config): Boolean {
        val m = out.size / 2
        if (closed) {
            val before = abs(signedArea(raw, n))
            val after = abs(signedArea(out, m - 1))
            return before <= 0 || (after / before in c.areaKeepMin..c.areaKeepMax)
        }
        val e0 = hypot(raw[0] - out[0], raw[1] - out[1])
        val e1 = hypot(raw[n * 2 - 2] - out[m * 2 - 2], raw[n * 2 - 1] - out[m * 2 - 1])
        return max(e0, e1) <= c.endMoveFraction * diag
    }

    // ---------------------------------------------------------------- open strokes

    private fun assistOpen(xy: DoubleArray, d: Double, c: Config): Shape? {
        val n = xy.size / 2
        val corners = findCorners(xy, n, closed = false, d, c)
        val breaks = IntArray(corners.size + 2)
        for (i in corners.indices) breaks[i + 1] = corners[i]
        breaks[breaks.lastIndex] = n - 1
        return buildPieces(xy, breaks, closed = false, d, c)
    }

    // ---------------------------------------------------------------- closed strokes

    private fun assistClosed(loop: DoubleArray, d: Double, c: Config): Shape? {
        val n = loop.size / 2
        fitEllipse(loop, n, d, c)?.let { return it }
        val corners = findCorners(loop, n, closed = true, d, c)
        if (corners.isEmpty()) return smoothLoop(loop, n, d, c)
        // Start the loop at its first corner so every piece runs corner to corner.
        val c0 = corners[0]
        val m = n - 1
        val rot = DoubleArray(n * 2)
        for (i in 0 until m) {
            val src = (c0 + i) % m
            rot[i * 2] = loop[src * 2]; rot[i * 2 + 1] = loop[src * 2 + 1]
        }
        rot[m * 2] = rot[0]; rot[m * 2 + 1] = rot[1]
        val breaks = IntArray(corners.size + 1)
        for (i in corners.indices) breaks[i] = (corners[i] - c0 + m) % m
        breaks[breaks.lastIndex] = m
        return buildPieces(rot, breaks, closed = true, d, c)
    }

    /** The ellipse (or circle) [loop] is a hand-drawn copy of, or null when it does not fit one closely enough. */
    private fun fitEllipse(loop: DoubleArray, n: Int, d: Double, c: Config): Shape? {
        val count = 128
        val pts = resampleLoop(loop, n - 1, count)
        var mx = 0.0
        var my = 0.0
        for (i in 0 until count) { mx += pts[i * 2]; my += pts[i * 2 + 1] }
        mx /= count; my /= count
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 0 until count) {
            val dx = pts[i * 2] - mx
            val dy = pts[i * 2 + 1] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val ct = cos(theta)
        val st = sin(theta)
        // Least squares for A = 1/a^2, B = 1/b^2 over A*u^2 + B*v^2 = 1.
        var u4 = 0.0
        var u2v2 = 0.0
        var v4 = 0.0
        var u2 = 0.0
        var v2 = 0.0
        for (i in 0 until count) {
            val dx = pts[i * 2] - mx
            val dy = pts[i * 2 + 1] - my
            val u = dx * ct + dy * st
            val v = -dx * st + dy * ct
            u4 += u * u * u * u; u2v2 += u * u * v * v; v4 += v * v * v * v
            u2 += u * u; v2 += v * v
        }
        val det = u4 * v4 - u2v2 * u2v2
        if (abs(det) < 1e-12) return null
        val a2 = (u2 * v4 - v2 * u2v2) / det
        val b2 = (v2 * u4 - u2 * u2v2) / det
        if (a2 <= 0 || b2 <= 0) return null
        var semiA = 1 / sqrt(a2)
        var semiB = 1 / sqrt(b2)
        // A near-circular stroke may be a regular polygon, which an ellipse fits only roughly.
        if (abs(semiA - semiB) <= c.circleAspectTolerance * max(semiA, semiB)) fitRegularPolygon(pts, count, mx, my, c)?.let { return it }
        var err = 0.0
        for (i in 0 until count) {
            val dx = pts[i * 2] - mx
            val dy = pts[i * 2 + 1] - my
            val u = dx * ct + dy * st
            val v = -dx * st + dy * ct
            val r = sqrt(a2 * u * u + b2 * v * v)
            err += (r - 1) * (r - 1)
        }
        if (sqrt(err / count) > c.ellipseFitTolerance) return null
        var tilt = theta
        if (min(semiA, semiB) < d * 0.01) return null
        if (abs(semiA - semiB) <= c.circleAspectTolerance * max(semiA, semiB)) {
            val r = (semiA + semiB) / 2
            semiA = r; semiB = r; tilt = 0.0
        } else {
            val quarter = PI / 2
            val snapped = (tilt / quarter).roundToInt() * quarter
            if (abs(tilt - snapped) <= Math.toRadians(c.axisSnapDegrees)) tilt = snapped
        }
        // Evenly spaced round the ellipse, plus (for an elongated one) evenly spaced in direction, so the tips are not kinked.
        val ts = ArrayList<Double>()
        for (i in 0 until c.ellipseVertices) ts += 2 * PI * i / c.ellipseVertices
        if (max(semiA, semiB) > 1.3 * min(semiA, semiB)) {
            val steps = c.ellipseTipSteps
            for (i in 0 until steps) {
                val phi = 2 * PI * i / steps
                ts += atan2(-cos(phi) / semiA, sin(phi) / semiB).let { if (it < 0) it + 2 * PI else it }
            }
        }
        ts.sort()
        val dedup = ArrayList<Double>()
        for (t in ts) if (dedup.isEmpty() || t - dedup.last() > 1e-3) dedup += t
        if (dedup.size > 1 && 2 * PI - dedup.last() <= 1e-3) dedup.removeAt(dedup.size - 1)
        val k = dedup.size
        val xy = DoubleArray((k + 1) * 2)
        val ctt = cos(tilt)
        val stt = sin(tilt)
        for (i in 0 until k) {
            val u = semiA * cos(dedup[i])
            val v = semiB * sin(dedup[i])
            xy[i * 2] = mx + u * ctt - v * stt
            xy[i * 2 + 1] = my + u * stt + v * ctt
        }
        xy[k * 2] = xy[0]; xy[k * 2 + 1] = xy[1]
        return Shape(xy, BooleanArray(k + 1), closed = true, exact = true)
    }

    /**
     * The regular polygon (5 to 8 sides) a near-circular closed stroke is, found from the harmonics of its
     * radius round the centre ([mx], [my]); null when it is a circle. A polygon's radius swings once per side.
     */
    private fun fitRegularPolygon(pts: DoubleArray, count: Int, mx: Double, my: Double, c: Config): Shape? {
        val th = DoubleArray(count)
        val rad = DoubleArray(count)
        for (i in 0 until count) {
            th[i] = atan2(pts[i * 2 + 1] - my, pts[i * 2] - mx)
            rad[i] = hypot(pts[i * 2] - mx, pts[i * 2 + 1] - my)
        }
        val dth = DoubleArray(count) { normalize(th[(it + 1) % count] - th[it]) }
        val turn = dth.sum()
        if (abs(abs(turn) - 2 * PI) > 0.5) return null
        val sign = if (turn >= 0) 1.0 else -1.0
        // Midpoint rule over the angle swept between neighbouring samples.
        val tm = DoubleArray(count) { th[it] + dth[it] / 2 }
        val rm = DoubleArray(count) { (rad[it] + rad[(it + 1) % count]) / 2 }
        var c0 = 0.0
        for (i in 0 until count) c0 += rm[i] * dth[i] * sign
        c0 /= 2 * PI
        if (c0 <= 0) return null
        var bestN = 0
        var bestAmp = 0.0
        var bestPhase = 0.0
        for (n in c.polygonMinSides..c.polygonMaxSides) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until count) {
                val w = rm[i] * dth[i] * sign
                re += w * cos(n * tm[i]); im += w * sin(n * tm[i])
            }
            val amp = 2 * hypot(re, im) / (2 * PI) / c0
            if (amp > bestAmp) { bestAmp = amp; bestN = n; bestPhase = atan2(im, re) / n }
        }
        if (bestN == 0 || bestAmp < c.polygonHarmonicTolerance) return null
        val half = PI / bestN
        val meanFactor = cos(half) * (bestN / PI) * kotlin.math.ln(1 / cos(half) + kotlin.math.tan(half))
        val radius = c0 / meanFactor
        // It must really be that polygon: the radius follows the polygon's to within the ellipse tolerance.
        var resid = 0.0
        val apothem = radius * cos(half)
        for (i in 0 until count) {
            var rel = (th[i] - bestPhase) % (2 * half)
            if (rel < 0) rel += 2 * half
            val expected = apothem / cos(rel - half)
            resid += ((rad[i] - expected) / c0).let { it * it }
        }
        if (sqrt(resid / count) > c.ellipseFitTolerance) return null
        val xy = DoubleArray((bestN + 1) * 2)
        for (k in 0 until bestN) {
            val a = bestPhase + 2 * PI * k / bestN
            xy[k * 2] = mx + radius * cos(a)
            xy[k * 2 + 1] = my + radius * sin(a)
        }
        xy[bestN * 2] = xy[0]; xy[bestN * 2 + 1] = xy[1]
        return Shape(xy, BooleanArray(bestN + 1) { true }, closed = true, exact = true)
    }

    /**
     * A closed [ring] (ending on its first point) turned to run the way [raw] ran and to start at the
     * point of the outline nearest [raw]'s first point, so the arc-length origin of the paint does not move.
     */
    private fun orient(ring: DoubleArray, raw: DoubleArray, d: Double, c: Config): DoubleArray {
        val m = ring.size / 2 - 1
        var pts = ring.copyOf(m * 2)
        if (signedArea(raw, raw.size / 2) * signedArea(pts, m) < 0) {
            val rev = DoubleArray(m * 2)
            for (i in 0 until m) { rev[i * 2] = pts[(m - 1 - i) * 2]; rev[i * 2 + 1] = pts[(m - 1 - i) * 2 + 1] }
            pts = rev
        }
        // The nearest point of the outline to the stroke's first point.
        var bestEdge = 0
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until m) {
            val j = (i + 1) % m
            val ex = pts[j * 2] - pts[i * 2]
            val ey = pts[j * 2 + 1] - pts[i * 2 + 1]
            val l2 = ex * ex + ey * ey
            val t = if (l2 < 1e-18) 0.0 else (((raw[0] - pts[i * 2]) * ex + (raw[1] - pts[i * 2 + 1]) * ey) / l2).coerceIn(0.0, 1.0)
            val dd = hypot(raw[0] - (pts[i * 2] + ex * t), raw[1] - (pts[i * 2 + 1] + ey * t))
            if (dd < bestD) { bestD = dd; bestEdge = i; bestT = t }
        }
        val j = (bestEdge + 1) % m
        val sx = pts[bestEdge * 2] + (pts[j * 2] - pts[bestEdge * 2]) * bestT
        val sy = pts[bestEdge * 2 + 1] + (pts[j * 2 + 1] - pts[bestEdge * 2 + 1]) * bestT
        val snap = c.startSnapFraction * d
        val onStart = hypot(sx - pts[bestEdge * 2], sy - pts[bestEdge * 2 + 1]) <= snap
        val onEnd = hypot(sx - pts[j * 2], sy - pts[j * 2 + 1]) <= snap
        val out = ArrayList<Double>(m * 2 + 4)
        if (onStart || onEnd) {
            val first = if (onStart) bestEdge else j
            for (k in 0 until m) { val i = (first + k) % m; out += pts[i * 2]; out += pts[i * 2 + 1] }
        } else {
            out += sx; out += sy
            for (k in 1..m) { val i = (bestEdge + k) % m; out += pts[i * 2]; out += pts[i * 2 + 1] }
        }
        out += out[0]; out += out[1]
        return DoubleArray(out.size) { out[it] }
    }

    private fun signedArea(xy: DoubleArray, n: Int): Double {
        var a = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1]
        }
        return a
    }

    /** A closed stroke with no corners and no ellipse in it: the jitter smoothed out all the way round. */
    private fun smoothLoop(loop: DoubleArray, n: Int, d: Double, c: Config): Shape {
        val m = n - 1
        val len = pathLength(loop, n)
        val count = (len / (c.smoothStepFraction * d)).let { ceil(it).toInt() }.coerceIn(16, 512)
        val pts = resampleLoop(loop, m, count)
        val sm = pts.copyOf()
        smoothPasses(sm, count, closed = true, c.smoothPasses)
        var worst = 0.0
        for (i in 0 until count) worst = max(worst, hypot(sm[i * 2] - pts[i * 2], sm[i * 2 + 1] - pts[i * 2 + 1]))
        val use = if (worst <= c.settledFraction * d) loop.copyOf(m * 2) else sm
        val total = use.size / 2
        val closedXy = use.copyOf(total * 2 + 2)
        closedXy[total * 2] = closedXy[0]; closedXy[total * 2 + 1] = closedXy[1]
        return simplifiedShape(closedXy, closed = true, d, c)
    }

    // ---------------------------------------------------------------- corners

    /** Ramer-Douglas-Peucker over `xy[from..to]`: the kept indices, ascending. */
    private fun rdp(xy: DoubleArray, from: Int, to: Int, tol: Double): IntArray {
        if (to - from < 2) return intArrayOf(from, to).distinct().toIntArray()
        val keep = BooleanArray(to + 1)
        keep[from] = true; keep[to] = true
        val stack = IntArray(2 * (to - from + 2))
        var sp = 0
        stack[sp++] = from; stack[sp++] = to
        while (sp > 0) {
            val b = stack[--sp]
            val a = stack[--sp]
            var worst = -1
            var worstD = tol
            for (i in a + 1 until b) {
                val dd = segDist(xy[i * 2], xy[i * 2 + 1], xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1])
                if (dd > worstD) { worst = i; worstD = dd }
            }
            if (worst >= 0) {
                keep[worst] = true
                if (sp + 4 > stack.size) break
                stack[sp++] = a; stack[sp++] = worst
                stack[sp++] = worst; stack[sp++] = b
            }
        }
        var cnt = 0
        for (i in from..to) if (keep[i]) cnt++
        val out = IntArray(cnt)
        var k = 0
        for (i in from..to) if (keep[i]) out[k++] = i
        return out
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-18) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    /**
     * Corner indices into [xy]: RDP vertices where the path bends by at least
     * [Config.cornerAngleDegrees], neighbouring bends summed. For a closed loop (`xy` ends on its
     * first point) the indices are in `0 until n - 1`.
     */
    private fun findCorners(xy: DoubleArray, n: Int, closed: Boolean, d: Double, c: Config): IntArray {
        val idx = rdp(xy, 0, n - 1, c.cornerSimplifyFraction * d)
        val v = if (closed) idx.copyOf(idx.size - 1) else idx
        val first = if (closed) 0 else 1
        val last = if (closed) v.size - 1 else v.size - 2
        if (v.size < 3 || last < first) return IntArray(0)
        val count0 = last - first + 1
        val cand0 = IntArray(count0)
        val turn0 = DoubleArray(count0)
        for (k in 0 until count0) {
            val i = first + k
            val prev = v[(i - 1 + v.size) % v.size]
            val cur = v[i]
            val next = v[(i + 1) % v.size]
            val a1 = atan2(xy[cur * 2 + 1] - xy[prev * 2 + 1], xy[cur * 2] - xy[prev * 2])
            val a2 = atan2(xy[next * 2 + 1] - xy[cur * 2 + 1], xy[next * 2] - xy[cur * 2])
            cand0[k] = cur
            turn0[k] = normalize(a2 - a1)
        }
        val gap = c.cornerClusterFraction * d
        val minTurn = Math.toRadians(10.0)
        // Vertices that barely bend (a stroke's start point on a side, a collinear point) are not corners and
        // must not join two real ones together.
        val sig = (0 until count0).filter { abs(turn0[it]) > minTurn }
        val count = sig.size
        if (count == 0) return IntArray(0)
        val cand = IntArray(count) { cand0[sig[it]] }
        val turn = DoubleArray(count) { turn0[sig[it]] }
        // Cluster consecutive bends that are close and turn the same way (a rounded corner), up to a
        // sharp corner's worth; the wrap-around joins the last to the first.
        val maxSum = Math.toRadians(c.cornerClusterMaxDegrees)
        val clusterOf = IntArray(count)
        var cl = 0
        var run = 0.0
        for (k in 0 until count) {
            if (k > 0) {
                val far = hypot(xy[cand[k] * 2] - xy[cand[k - 1] * 2], xy[cand[k] * 2 + 1] - xy[cand[k - 1] * 2 + 1]) > gap
                val flips = turn[k] * turn[k - 1] < 0
                if (far || flips || abs(run + turn[k]) > maxSum) { cl++; run = 0.0 }
            }
            run += turn[k]
            clusterOf[k] = cl
        }
        var clusters = cl + 1
        if (closed && clusters > 1 && hypot(xy[cand[0] * 2] - xy[cand[count - 1] * 2], xy[cand[0] * 2 + 1] - xy[cand[count - 1] * 2 + 1]) <= gap &&
            turn[0] * turn[count - 1] > 0 &&
            abs((0 until count).filter { clusterOf[it] == 0 || clusterOf[it] == clusters - 1 }.sumOf { turn[it] }) <= maxSum
        ) {
            for (k in 0 until count) if (clusterOf[k] == clusters - 1) clusterOf[k] = 0
            clusters--
        }
        val sum = DoubleArray(clusters + 1)
        val bestTurn = DoubleArray(clusters + 1)
        val bestIdx = IntArray(clusters + 1) { -1 }
        for (k in 0 until count) {
            val g = clusterOf[k]
            sum[g] += turn[k]
            if (abs(turn[k]) > bestTurn[g]) { bestTurn[g] = abs(turn[k]); bestIdx[g] = cand[k] }
        }
        // Simplifying cuts corners and a wavy side bends gently at coarse scale: the corner is where the stroke
        // itself turns sharply over a short distance, searched for round the candidate.
        val m = if (closed) n - 1 else n
        val w = c.cornerWindowFraction * d
        val search = 0.5 * gap
        val fineLimit = Math.toRadians(c.cornerFineAngleDegrees)
        for (g in 0..clusters) if (bestIdx[g] >= 0) {
            var apex = -1
            var apexTurn = 0.0
            val from = bestIdx[g]
            var i = from
            var acc = 0.0
            // Walk both ways from the candidate while within the search distance.
            for (dir in intArrayOf(-1, 1)) {
                i = from
                acc = 0.0
                while (true) {
                    val t = abs(fineTurn(xy, i, m, closed, w))
                    if (t > apexTurn) { apexTurn = t; apex = i }
                    val nxt = i + dir
                    if (nxt < 0 || nxt > m - 1) { if (!closed) break }
                    val ni = ((nxt % m) + m) % m
                    acc += hypot(xy[ni * 2] - xy[i * 2], xy[ni * 2 + 1] - xy[i * 2 + 1])
                    if (acc > search || ni == from) break
                    i = ni
                }
            }
            bestIdx[g] = if (apex >= 0 && apexTurn >= fineLimit) apex else -1
        }
        val limit = Math.toRadians(c.cornerAngleDegrees)
        val found = ArrayList<Int>()
        for (g in 0..clusters) if (bestIdx[g] >= 0 && abs(sum[g]) >= limit) found += bestIdx[g]
        found.sort()
        for (k in found.size - 1 downTo 1) if (found[k] == found[k - 1]) found.removeAt(k)
        found.sort()
        return found.toIntArray()
    }

    /** The point [w] along the stroke from point [i] in direction [dir] (+1 or -1), or null when the stroke ends before half of that. */
    private fun walk(xy: DoubleArray, i: Int, m: Int, closed: Boolean, dir: Int, w: Double): DoubleArray? {
        var cur = i
        var acc = 0.0
        var steps = 0
        while (steps < m) {
            val nxt = cur + dir
            if (!closed && (nxt < 0 || nxt > m - 1)) break
            val ni = ((nxt % m) + m) % m
            val l = hypot(xy[ni * 2] - xy[cur * 2], xy[ni * 2 + 1] - xy[cur * 2 + 1])
            if (acc + l >= w) {
                val f = if (l < 1e-12) 0.0 else (w - acc) / l
                return doubleArrayOf(xy[cur * 2] + (xy[ni * 2] - xy[cur * 2]) * f, xy[cur * 2 + 1] + (xy[ni * 2 + 1] - xy[cur * 2 + 1]) * f)
            }
            acc += l
            cur = ni
            steps++
        }
        return if (acc >= w / 2) doubleArrayOf(xy[cur * 2], xy[cur * 2 + 1]) else null
    }

    /** The signed bend of the stroke at point [i], from the chord reaching back [w] to the chord reaching on [w]. */
    private fun fineTurn(xy: DoubleArray, i: Int, m: Int, closed: Boolean, w: Double): Double {
        val a = walk(xy, i, m, closed, -1, w) ?: return 0.0
        val b = walk(xy, i, m, closed, 1, w) ?: return 0.0
        val a1 = atan2(xy[i * 2 + 1] - a[1], xy[i * 2] - a[0])
        val a2 = atan2(b[1] - xy[i * 2 + 1], b[0] - xy[i * 2])
        return normalize(a2 - a1)
    }

    private fun normalize(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }

    // ---------------------------------------------------------------- pieces

    private class Fit(val cx: Double, val cy: Double, val angle: Double)

    /** The total-least-squares line through `xy[from..to]` (segments weighted by length); angle in [0, PI). */
    private fun fitLine(xy: DoubleArray, from: Int, to: Int): Fit {
        var w = 0.0
        var mx = 0.0
        var my = 0.0
        for (i in from until to) {
            val l = hypot(xy[i * 2 + 2] - xy[i * 2], xy[i * 2 + 3] - xy[i * 2 + 1])
            w += l
            mx += l * (xy[i * 2] + xy[i * 2 + 2]) / 2
            my += l * (xy[i * 2 + 1] + xy[i * 2 + 3]) / 2
        }
        if (w < 1e-12) return Fit(xy[from * 2], xy[from * 2 + 1], 0.0)
        mx /= w; my /= w
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in from until to) {
            val l = hypot(xy[i * 2 + 2] - xy[i * 2], xy[i * 2 + 3] - xy[i * 2 + 1])
            // Each segment as a uniform bar: its second moment about the centroid.
            val ax = xy[i * 2] - mx
            val ay = xy[i * 2 + 1] - my
            val bx = xy[i * 2 + 2] - mx
            val by = xy[i * 2 + 3] - my
            sxx += l * (ax * ax + ax * bx + bx * bx) / 3
            syy += l * (ay * ay + ay * by + by * by) / 3
            sxy += l * (2 * ax * ay + ax * by + bx * ay + 2 * bx * by) / 6
        }
        var a = 0.5 * atan2(2 * sxy, sxx - syy)
        if (a < 0) a += PI
        if (a >= PI) a -= PI
        return Fit(mx, my, a)
    }

    private fun isStraight(xy: DoubleArray, from: Int, to: Int, c: Config): Boolean {
        val chord = hypot(xy[to * 2] - xy[from * 2], xy[to * 2 + 1] - xy[from * 2 + 1])
        if (chord < 1e-9) return false
        val f = fitLine(xy, from, to)
        val dx = cos(f.angle)
        val dy = sin(f.angle)
        var worst = 0.0
        for (i in from..to) worst = max(worst, abs((xy[i * 2] - f.cx) * -dy + (xy[i * 2 + 1] - f.cy) * dx))
        return worst <= c.straightFraction * chord
    }

    private fun lineIntersection(a: Fit, ta: Double, b: Fit, tb: Double): DoubleArray? {
        val dax = cos(ta)
        val day = sin(ta)
        val dbx = cos(tb)
        val dby = sin(tb)
        val den = dax * dby - day * dbx
        if (abs(den) < 0.25) return null
        val s = ((b.cx - a.cx) * dby - (b.cy - a.cy) * dbx) / den
        return doubleArrayOf(a.cx + dax * s, a.cy + day * s)
    }

    private fun project(px: Double, py: Double, f: Fit, theta: Double): DoubleArray {
        val dx = cos(theta)
        val dy = sin(theta)
        val t = (px - f.cx) * dx + (py - f.cy) * dy
        return doubleArrayOf(f.cx + dx * t, f.cy + dy * t)
    }

    /**
     * Splits [xy] at [breaks] (indices into it, ascending; a closed loop's last break is its end point,
     * the first point again), straightens the straight pieces, smooths the others, and rebuilds the
     * corners from the straightened pieces.
     */
    private fun buildPieces(xy: DoubleArray, breaks: IntArray, closed: Boolean, d: Double, c: Config): Shape? {
        val pieces = breaks.size - 1
        if (pieces < 1) return null
        // Corner-cutting by the simplifier leaves a short stub of the neighbouring side on each end of a piece;
        // straightness and the line fit ignore it.
        val trim = 2.0 * c.cornerSimplifyFraction * d
        val lo = IntArray(pieces)
        val hi = IntArray(pieces)
        for (j in 0 until pieces) {
            val a = breaks[j]
            val b = breaks[j + 1]
            var l = a
            var h = b
            val startTrim = closed || j > 0
            val endTrim = closed || j < pieces - 1
            var acc = 0.0
            if (startTrim) {
                while (l < b && acc < trim) { acc += hypot(xy[l * 2 + 2] - xy[l * 2], xy[l * 2 + 3] - xy[l * 2 + 1]); l++ }
            }
            acc = 0.0
            if (endTrim) {
                while (h > a && acc < trim) { acc += hypot(xy[h * 2] - xy[h * 2 - 2], xy[h * 2 + 1] - xy[h * 2 - 1]); h-- }
            }
            if (h - l < 2) { l = a; h = b }
            lo[j] = l; hi[j] = h
        }
        val straight = BooleanArray(pieces)
        val fit = arrayOfNulls<Fit>(pieces)
        for (j in 0 until pieces) {
            val full = pathLength(xy.copyOfRange(breaks[j] * 2, breaks[j + 1] * 2 + 2), breaks[j + 1] - breaks[j] + 1)
            val inner = pathLength(xy.copyOfRange(lo[j] * 2, hi[j] * 2 + 2), hi[j] - lo[j] + 1)
            if ((lo[j] != breaks[j] || hi[j] != breaks[j + 1]) && inner >= 0.6 * full && isStraight(xy, lo[j], hi[j], c)) {
                straight[j] = true; fit[j] = fitLine(xy, lo[j], hi[j])
            } else if (isStraight(xy, breaks[j], breaks[j + 1], c)) {
                straight[j] = true; fit[j] = fitLine(xy, breaks[j], breaks[j + 1])
            }
        }
        // The reference orientation: the longest straight piece's, snapped to an axis when near.
        var ref = Double.NaN
        var refLen = -1.0
        for (j in 0 until pieces) if (straight[j]) {
            val l = hypot(xy[breaks[j + 1] * 2] - xy[breaks[j] * 2], xy[breaks[j + 1] * 2 + 1] - xy[breaks[j] * 2 + 1])
            if (l > refLen + 1e-9) { refLen = l; ref = fit[j]!!.angle }
        }
        val quarter = PI / 2
        val axisTol = Math.toRadians(c.axisSnapDegrees)
        val rightTol = Math.toRadians(c.rightAngleDegrees)
        val parallelTol = Math.toRadians(c.parallelAngleDegrees)
        if (!ref.isNaN()) {
            val snapped = (ref / quarter).roundToInt() * quarter
            if (abs(ref - snapped) <= axisTol) ref = snapped
        }
        val theta = DoubleArray(pieces)
        val onGrid = IntArray(pieces) { Int.MIN_VALUE }
        for (j in 0 until pieces) if (straight[j]) {
            val raw = fit[j]!!.angle
            val rel = raw - ref
            val k = (rel / quarter).roundToInt()
            val axisK = (raw / quarter).roundToInt()
            theta[j] = when {
                abs(rel - k * quarter) <= (if (k % 2 == 0) parallelTol else rightTol) -> { onGrid[j] = k; ref + k * quarter }
                abs(raw - axisK * quarter) <= axisTol -> axisK * quarter
                else -> raw
            }
        }
        // Corner positions (pieces + 1 for an open stroke; a closed loop's last is its first again).
        val vx = DoubleArray(pieces + 1)
        val vy = DoubleArray(pieces + 1)
        for (j in 0..pieces) {
            val prev = if (j > 0) j - 1 else if (closed) pieces - 1 else -1
            val next = if (j < pieces) j else if (closed) 0 else -1
            val ox = xy[breaks[j] * 2]
            val oy = xy[breaks[j] * 2 + 1]
            val ps = prev >= 0 && straight[prev]
            val ns = next >= 0 && straight[next]
            val p: DoubleArray = when {
                ps && ns -> lineIntersection(fit[prev]!!, theta[prev], fit[next]!!, theta[next])
                    ?.takeIf { hypot(it[0] - ox, it[1] - oy) <= 0.2 * d }
                    ?: project(ox, oy, fit[next]!!, theta[next])
                ps -> project(ox, oy, fit[prev]!!, theta[prev])
                ns -> project(ox, oy, fit[next]!!, theta[next])
                else -> doubleArrayOf(ox, oy)
            }
            vx[j] = p[0]; vy[j] = p[1]
        }
        if (closed) { vx[pieces] = vx[0]; vy[pieces] = vy[0] }
        val regular = closed && straight.all { it } && regularPolygon(vx, vy, pieces, onGrid, ref, c)
        // Assemble.
        val ox = ArrayList<Double>()
        val corner = ArrayList<Boolean>()
        fun emit(x: Double, y: Double, isCorner: Boolean) {
            ox += x; ox += y; corner += isCorner
        }
        emit(vx[0], vy[0], closed)
        for (j in 0 until pieces) {
            if (!straight[j]) {
                val a = breaks[j]
                val b = breaks[j + 1]
                val q = xy.copyOfRange(a * 2, b * 2 + 2)
                val smooth = smoothPiece(q, d, c)
                // Carry the corner shifts along the piece.
                val dx0 = vx[j] - q[0]
                val dy0 = vy[j] - q[1]
                val dx1 = vx[j + 1] - q[q.size - 2]
                val dy1 = vy[j + 1] - q[q.size - 1]
                val sn = smooth.size / 2
                val cum = DoubleArray(sn)
                for (i in 1 until sn) cum[i] = cum[i - 1] + hypot(smooth[i * 2] - smooth[i * 2 - 2], smooth[i * 2 + 1] - smooth[i * 2 - 1])
                val total = cum[sn - 1].coerceAtLeast(1e-9)
                for (i in 1 until sn - 1) {
                    val f = cum[i] / total
                    emit(smooth[i * 2] + (1 - f) * dx0 + f * dx1, smooth[i * 2 + 1] + (1 - f) * dy0 + f * dy1, false)
                }
            }
            emit(vx[j + 1], vy[j + 1], !(j == pieces - 1 && !closed) || false)
        }
        // The loop's last point is its first again; an open stroke's ends are not corners.
        if (!closed) corner[corner.size - 1] = false
        val arr = DoubleArray(ox.size)
        for (i in arr.indices) arr[i] = ox[i]
        val flags = BooleanArray(corner.size) { corner[it] }
        return Shape(arr, flags, closed, exact = regular)
    }

    /**
     * Four right-angled straight sides: a rectangle, or a square when its sides are within
     * [Config.squareAspectTolerance]; three straight sides: equilateral when within
     * [Config.equalSideTolerance]. Edits the corner arrays in place.
     */
    private fun regularPolygon(vx: DoubleArray, vy: DoubleArray, pieces: Int, onGrid: IntArray, ref: Double, c: Config): Boolean {
        if (pieces == 4 && (0 until 4).all { onGrid[it] != Int.MIN_VALUE && Math.floorMod(onGrid[it] - onGrid[0], 2) == it % 2 }) {
            val w = hypot(vx[1] - vx[0], vy[1] - vy[0])
            val h = hypot(vx[2] - vx[1], vy[2] - vy[1])
            if (abs(w - h) <= c.squareAspectTolerance * max(w, h)) {
                val side = (w + h) / 2
                val mx = (vx[0] + vx[1] + vx[2] + vx[3]) / 4
                val my = (vy[0] + vy[1] + vy[2] + vy[3]) / 4
                val ux = cos(ref)
                val uy = sin(ref)
                for (i in 0 until 4) {
                    val du = (vx[i] - mx) * ux + (vy[i] - my) * uy
                    val dv = -(vx[i] - mx) * uy + (vy[i] - my) * ux
                    val su = if (du >= 0) 1 else -1
                    val sv = if (dv >= 0) 1 else -1
                    vx[i] = mx + su * side / 2 * ux - sv * side / 2 * uy
                    vy[i] = my + su * side / 2 * uy + sv * side / 2 * ux
                }
                vx[4] = vx[0]; vy[4] = vy[0]
                return true
            }
        } else if (pieces == 3) {
            val s0 = hypot(vx[1] - vx[0], vy[1] - vy[0])
            val s1 = hypot(vx[2] - vx[1], vy[2] - vy[1])
            val s2 = hypot(vx[0] - vx[2], vy[0] - vy[2])
            val mean = (s0 + s1 + s2) / 3
            if (max(s0, max(s1, s2)) - min(s0, min(s1, s2)) <= c.equalSideTolerance * mean) {
                val mx = (vx[0] + vx[1] + vx[2]) / 3
                val my = (vy[0] + vy[1] + vy[2]) / 3
                val r = mean / sqrt(3.0)
                val sign = if ((vx[1] - vx[0]) * (vy[2] - vy[0]) - (vy[1] - vy[0]) * (vx[2] - vx[0]) >= 0) 1 else -1
                var rot = atan2(vy[0] - my, vx[0] - mx)
                // Turn it so the edge nearest an axis lies on it, when that is near enough.
                var best = Double.MAX_VALUE
                var fix = 0.0
                for (i in 0 until 3) {
                    val edge = rot + sign * (2 * PI * i / 3) + sign * (2 * PI / 3 + PI) / 2 + PI / 2
                    val q = PI / 2
                    val off = edge - (edge / q).roundToInt() * q
                    if (abs(off) < best) { best = abs(off); fix = off }
                }
                if (best <= Math.toRadians(c.axisSnapDegrees)) rot -= fix
                for (i in 0 until 3) {
                    val a = rot + sign * 2 * PI * i / 3
                    vx[i] = mx + r * cos(a)
                    vy[i] = my + r * sin(a)
                }
                vx[3] = vx[0]; vy[3] = vy[0]
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- curves

    private fun smoothPasses(p: DoubleArray, count: Int, closed: Boolean, passes: Int) {
        val tmp = DoubleArray(p.size)
        repeat(passes) {
            for (i in 0 until count) {
                val pi = if (i == 0) (if (closed) count - 1 else 0) else i - 1
                val ni = if (i == count - 1) (if (closed) 0 else count - 1) else i + 1
                val pinned = !closed && (i == 0 || i == count - 1)
                for (k in 0..1) {
                    tmp[i * 2 + k] = if (pinned) p[i * 2 + k] else (p[pi * 2 + k] + 2 * p[i * 2 + k] + p[ni * 2 + k]) / 4
                }
            }
            System.arraycopy(tmp, 0, p, 0, p.size)
        }
    }

    /** An open curve piece with its jitter smoothed (ends kept), or simplified as drawn when it is already clean. */
    private fun smoothPiece(q: DoubleArray, d: Double, c: Config): DoubleArray {
        val n = q.size / 2
        val len = pathLength(q, n)
        val count = ceil(len / (c.smoothStepFraction * d)).toInt().coerceIn(4, 512)
        val res = resampleOpen(q, n, count)
        val sm = res.copyOf()
        smoothPasses(sm, count, closed = false, c.smoothPasses)
        var worst = 0.0
        for (i in 0 until count) worst = max(worst, hypot(sm[i * 2] - res[i * 2], sm[i * 2 + 1] - res[i * 2 + 1]))
        val use = if (worst <= c.settledFraction * d) q else sm
        val keep = rdp(use, 0, use.size / 2 - 1, c.curveSimplifyFraction * d)
        val out = DoubleArray(keep.size * 2)
        for (i in keep.indices) { out[i * 2] = use[keep[i] * 2]; out[i * 2 + 1] = use[keep[i] * 2 + 1] }
        return out
    }

    private fun simplifiedShape(closedXy: DoubleArray, closed: Boolean, d: Double, c: Config): Shape {
        val keep = rdp(closedXy, 0, closedXy.size / 2 - 1, c.curveSimplifyFraction * d)
        val out = DoubleArray(keep.size * 2)
        for (i in keep.indices) { out[i * 2] = closedXy[keep[i] * 2]; out[i * 2 + 1] = closedXy[keep[i] * 2 + 1] }
        return Shape(out, BooleanArray(keep.size), closed)
    }

    /** [count] points evenly spaced by arc length along the open polyline, both ends included. */
    private fun resampleOpen(xy: DoubleArray, n: Int, count: Int): DoubleArray {
        val out = DoubleArray(count * 2)
        val total = pathLength(xy, n)
        var seg = 0
        var acc = 0.0
        for (k in 0 until count) {
            val target = if (count == 1) 0.0 else total * k / (count - 1)
            while (seg < n - 2 && acc + hypot(xy[seg * 2 + 2] - xy[seg * 2], xy[seg * 2 + 3] - xy[seg * 2 + 1]) < target) {
                acc += hypot(xy[seg * 2 + 2] - xy[seg * 2], xy[seg * 2 + 3] - xy[seg * 2 + 1])
                seg++
            }
            val l = hypot(xy[seg * 2 + 2] - xy[seg * 2], xy[seg * 2 + 3] - xy[seg * 2 + 1])
            val f = if (l < 1e-12) 0.0 else ((target - acc) / l).coerceIn(0.0, 1.0)
            out[k * 2] = xy[seg * 2] + (xy[seg * 2 + 2] - xy[seg * 2]) * f
            out[k * 2 + 1] = xy[seg * 2 + 1] + (xy[seg * 2 + 3] - xy[seg * 2 + 1]) * f
        }
        return out
    }

    /** [count] points evenly spaced by arc length round the closed loop of [m] points (`xy` may hold its first point again at the end). */
    private fun resampleLoop(xy: DoubleArray, m: Int, count: Int): DoubleArray {
        val closed = DoubleArray(m * 2 + 2)
        System.arraycopy(xy, 0, closed, 0, m * 2)
        closed[m * 2] = xy[0]; closed[m * 2 + 1] = xy[1]
        val full = resampleOpen(closed, m + 1, count + 1)
        return full.copyOf(count * 2)
    }

    // ---------------------------------------------------------------- symmetry

    private fun symmetrise(s: Shape, d: Double, c: Config): Shape? {
        if (s.n < (if (s.closed) 4 else 3)) return null
        if (!s.closed) return mirrorOpen(s, d, c)
        var cur = s
        var changed = false
        mirrorClosed(cur, d, c)?.let { cur = it; changed = true }
        mirrorClosed(transpose(cur), d, c)?.let { cur = transpose(it); changed = true }
        return if (changed) cur else null
    }

    private fun transpose(s: Shape): Shape {
        val xy = DoubleArray(s.xy.size)
        for (i in 0 until s.n) { xy[i * 2] = s.xy[i * 2 + 1]; xy[i * 2 + 1] = s.xy[i * 2] }
        return Shape(xy, s.corner, s.closed)
    }

    /** RMS and largest distance from [count] boundary samples' mirror images (about x = cx) to the boundary, relative to [d]. */
    private fun mirrorErrorClosed(xy: DoubleArray, m: Int, cx: Double, d: Double): DoubleArray {
        val count = 64
        val pts = resampleLoop(xy, m, count)
        var sum = 0.0
        var worst = 0.0
        for (i in 0 until count) {
            val mx = 2 * cx - pts[i * 2]
            val my = pts[i * 2 + 1]
            var best = Double.MAX_VALUE
            for (j in 0 until m) {
                val k = (j + 1) % m
                best = min(best, segDist(mx, my, xy[j * 2], xy[j * 2 + 1], xy[k * 2], xy[k * 2 + 1]))
            }
            sum += best * best
            worst = max(worst, best)
        }
        return doubleArrayOf(sqrt(sum / count) / d, worst / d)
    }

    /** A polyline with a corner flag per point: one side of a symmetric shape. */
    private class Chain(val xy: DoubleArray, val corner: BooleanArray) {
        val n: Int get() = xy.size / 2
    }

    private fun mirrorClosed(s: Shape, d: Double, c: Config): Shape? {
        val m = s.n - 1
        if (m < 3) return null
        val box = Box.of(s.xy, m)
        val centroid = resampleLoop(s.xy, m, 64).let { p -> (0 until 64).sumOf { p[it * 2] } / 64 }
        var cx = box.cx
        var err = mirrorErrorClosed(s.xy, m, cx, d)
        val alt = mirrorErrorClosed(s.xy, m, centroid, d)
        if (alt[0] < err[0]) { cx = centroid; err = alt }
        if (err[0] > c.symmetryRmsFraction || err[1] > c.symmetryMaxFraction) return null
        if (err[1] <= c.symmetrySettledFraction) return null
        val snap = c.symmetryAxisSnapFraction * d
        // Work on a copy with the corners that lie on the axis put on it.
        val px = DoubleArray(m)
        val py = DoubleArray(m)
        for (i in 0 until m) {
            px[i] = s.xy[i * 2]
            py[i] = s.xy[i * 2 + 1]
            if (s.corner[i] && abs(px[i] - cx) <= snap) px[i] = cx
        }
        // Where the outline crosses the axis: (edge, t, y).
        val ce = ArrayList<Int>()
        val ct = ArrayList<Double>()
        val cy = ArrayList<Double>()
        for (i in 0 until m) {
            val j = (i + 1) % m
            val a = px[i] - cx
            val b = px[j] - cx
            if ((a < 0 && b >= 0) || (a >= 0 && b < 0)) {
                val t = a / (a - b)
                ce += i; ct += t; cy += py[i] + t * (py[j] - py[i])
            }
        }
        if (ce.size < 2) return null
        var ia = 0
        var ib = 1
        var far = -1.0
        for (i in ce.indices) for (j in i + 1 until ce.size) {
            val dist = abs(cy[i] - cy[j])
            if (dist > far) { far = dist; ia = i; ib = j }
        }
        val arc1 = arcChain(px, py, s.corner, m, ce[ia], ct[ia], cy[ia], ce[ib], ct[ib], cy[ib], cx)
        val arc2 = arcChain(px, py, s.corner, m, ce[ib], ct[ib], cy[ib], ce[ia], ct[ia], cy[ia], cx)
        val leftIsFirst = arc1.meanX() <= arc2.meanX()
        val left = if (leftIsFirst) arc1 else arc2
        val right = reverse(if (leftIsFirst) arc2 else arc1)
        val half = averageChains(left, right, cx, d, c, forceStart = true, forceEnd = true) ?: return null
        val hn = half.n
        val total = hn + (hn - 2) + 1
        val xy = DoubleArray(total * 2)
        val flags = BooleanArray(total)
        var k = 0
        for (i in 0 until hn) { xy[k * 2] = half.xy[i * 2]; xy[k * 2 + 1] = half.xy[i * 2 + 1]; flags[k] = half.corner[i]; k++ }
        for (i in hn - 2 downTo 1) { xy[k * 2] = 2 * cx - half.xy[i * 2]; xy[k * 2 + 1] = half.xy[i * 2 + 1]; flags[k] = half.corner[i]; k++ }
        xy[k * 2] = xy[0]; xy[k * 2 + 1] = xy[1]; flags[k] = flags[0]
        return Shape(xy, flags, closed = true)
    }

    private fun Chain.meanX(): Double {
        var s = 0.0
        for (i in 0 until n) s += xy[i * 2]
        return s / n
    }

    private fun reverse(ch: Chain): Chain {
        val n = ch.n
        val xy = DoubleArray(n * 2)
        val fl = BooleanArray(n)
        for (i in 0 until n) {
            xy[i * 2] = ch.xy[(n - 1 - i) * 2]
            xy[i * 2 + 1] = ch.xy[(n - 1 - i) * 2 + 1]
            fl[i] = ch.corner[n - 1 - i]
        }
        return Chain(xy, fl)
    }

    /** The loop from crossing (edge [i], [t]) forward to crossing (edge [j], [u]); the two crossing points lie on x = [cx]. */
    private fun arcChain(
        px: DoubleArray, py: DoubleArray, corner: BooleanArray, m: Int,
        i: Int, t: Double, ty: Double, j: Int, u: Double, uy: Double, cx: Double,
    ): Chain {
        val steps = if (j > i || (j == i && u > t)) j - i else j - i + m
        val xs = ArrayList<Double>()
        val fl = ArrayList<Boolean>()
        fun add(x: Double, y: Double, f: Boolean) {
            if (xs.isNotEmpty() && hypot(x - xs[xs.size - 2], y - xs[xs.size - 1]) < 1e-9) {
                fl[fl.size - 1] = fl[fl.size - 1] || f
            } else { xs += x; xs += y; fl += f }
        }
        add(cx, ty, false)
        for (k in 1..steps) {
            val v = (i + k) % m
            add(px[v], py[v], corner[v])
        }
        add(cx, uy, false)
        return Chain(DoubleArray(xs.size) { xs[it] }, BooleanArray(fl.size) { fl[it] })
    }

    /**
     * The average of [a] and [b] mirrored about x = [cx] (both run the same way round the shape),
     * corner to corner so corners stay corners; null when their corners do not pair up.
     */
    private fun averageChains(a: Chain, b: Chain, cx: Double, d: Double, c: Config, forceStart: Boolean, forceEnd: Boolean): Chain? {
        // Pair corners by position (a corner of one side's mirror image near a corner of the other); an unpaired one stops being a corner.
        val ia = interiorCorners(a)
        val ib = interiorCorners(b)
        val ba = ArrayList<Int>()
        val bb = ArrayList<Int>()
        var lastB = 0
        for (x in ia) {
            var best = -1
            var bestD = 0.1 * d
            for (y in ib) if (y > lastB) {
                val dd = hypot(a.xy[x * 2] - (2 * cx - b.xy[y * 2]), a.xy[x * 2 + 1] - b.xy[y * 2 + 1])
                if (dd < bestD) { bestD = dd; best = y }
            }
            if (best >= 0) { ba += x; bb += best; lastB = best }
        }
        val ca = cumulative(a.xy)
        val cb = cumulative(b.xy)
        val outX = ArrayList<Double>()
        val outF = ArrayList<Boolean>()
        val step = (c.smoothStepFraction * d).coerceAtLeast(1e-6)
        val segs = ba.size + 1
        for (sIdx in 0 until segs) {
            val a0 = if (sIdx == 0) 0 else ba[sIdx - 1]
            val a1 = if (sIdx == segs - 1) a.n - 1 else ba[sIdx]
            val b0 = if (sIdx == 0) 0 else bb[sIdx - 1]
            val b1 = if (sIdx == segs - 1) b.n - 1 else bb[sIdx]
            val lenA = ca[a1] - ca[a0]
            val lenB = cb[b1] - cb[b0]
            val count = ceil((lenA + lenB) / 2 / step).toInt().coerceIn(1, 400)
            val seg = DoubleArray((count + 1) * 2)
            for (k in 0..count) {
                val f = k.toDouble() / count
                val pa = pointAtArc(a.xy, ca, ca[a0] + f * lenA)
                val pb = pointAtArc(b.xy, cb, cb[b0] + f * lenB)
                seg[k * 2] = (pa[0] + (2 * cx - pb[0])) / 2
                seg[k * 2 + 1] = (pa[1] + pb[1]) / 2
            }
            if (sIdx == 0 && forceStart) seg[0] = cx
            if (sIdx == segs - 1 && forceEnd) seg[count * 2] = cx
            val keep = rdp(seg, 0, count, c.curveSimplifyFraction * d)
            val from = if (sIdx == 0) 0 else 1
            for (q in from until keep.size) {
                outX += seg[keep[q] * 2]; outX += seg[keep[q] * 2 + 1]
                outF += (q == 0 && sIdx > 0) || (q == keep.size - 1 && sIdx < segs - 1) || (q == 0 && false)
            }
            if (sIdx > 0) { /* the shared corner was emitted as the previous segment's last point */ }
        }
        return Chain(DoubleArray(outX.size) { outX[it] }, BooleanArray(outF.size) { outF[it] })
    }

    private fun interiorCorners(ch: Chain): IntArray {
        val l = ArrayList<Int>()
        for (i in 1 until ch.n - 1) if (ch.corner[i]) l += i
        return l.toIntArray()
    }

    private fun cumulative(xy: DoubleArray): DoubleArray {
        val n = xy.size / 2
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xy[i * 2] - xy[i * 2 - 2], xy[i * 2 + 1] - xy[i * 2 - 1])
        return cum
    }

    private fun pointAtArc(xy: DoubleArray, cum: DoubleArray, s: Double): DoubleArray {
        val n = cum.size
        if (s <= 0 || n < 2) return doubleArrayOf(xy[0], xy[1])
        if (s >= cum[n - 1]) return doubleArrayOf(xy[(n - 1) * 2], xy[(n - 1) * 2 + 1])
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        val l = cum[hi] - cum[lo]
        val f = if (l < 1e-12) 0.0 else (s - cum[lo]) / l
        return doubleArrayOf(xy[lo * 2] + (xy[hi * 2] - xy[lo * 2]) * f, xy[lo * 2 + 1] + (xy[hi * 2 + 1] - xy[lo * 2 + 1]) * f)
    }

    private fun mirrorOpen(s: Shape, d: Double, c: Config): Shape? {
        val n = s.n
        val cum = cumulative(s.xy)
        val total = cum[n - 1]
        if (total <= 0) return null
        val count = 64
        val pts = resampleOpen(s.xy, n, count)
        val box = Box.of(s.xy, n)
        val cands = doubleArrayOf((s.xy[0] + s.xy[n * 2 - 2]) / 2, box.cx, (0 until count).sumOf { pts[it * 2] } / count)
        var cx = cands[0]
        var err = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE)
        for (cand in cands) {
            var sum = 0.0
            var worst = 0.0
            for (i in 0 until count) {
                val j = count - 1 - i
                val dd = hypot(pts[i * 2] - (2 * cand - pts[j * 2]), pts[i * 2 + 1] - pts[j * 2 + 1])
                sum += dd * dd
                worst = max(worst, dd)
            }
            val e = doubleArrayOf(sqrt(sum / count) / d, worst / d)
            if (e[0] < err[0]) { err = e; cx = cand }
        }
        if (err[0] > c.symmetryRmsFraction || err[1] > c.symmetryMaxFraction) return null
        if (err[1] <= c.symmetrySettledFraction) return null
        // Split at the middle by arc length, or at a corner near it.
        var mid = total / 2
        var bestGap = c.symmetryAxisSnapFraction * total
        var midCorner = -1
        for (i in 1 until n - 1) if (s.corner[i] && abs(cum[i] - total / 2) <= bestGap) {
            bestGap = abs(cum[i] - total / 2); mid = cum[i]; midCorner = i
        }
        val first = ArrayList<Double>()
        val firstF = ArrayList<Boolean>()
        val second = ArrayList<Double>()
        val secondF = ArrayList<Boolean>()
        val mp = pointAtArc(s.xy, cum, mid)
        for (i in 0 until n) if (cum[i] < mid - 1e-9 && i != midCorner) { first += s.xy[i * 2]; first += s.xy[i * 2 + 1]; firstF += s.corner[i] }
        first += mp[0]; first += mp[1]; firstF += false
        second += mp[0]; second += mp[1]; secondF += false
        for (i in 0 until n) if (cum[i] > mid + 1e-9 && i != midCorner) { second += s.xy[i * 2]; second += s.xy[i * 2 + 1]; secondF += s.corner[i] }
        val f = Chain(DoubleArray(first.size) { first[it] }, BooleanArray(firstF.size) { firstF[it] })
        val sec = reverse(Chain(DoubleArray(second.size) { second[it] }, BooleanArray(secondF.size) { secondF[it] }))
        // The first half and the second, walked inward from the far end, run the same way; the chain's last point is the middle.
        val half = averageChains(f, sec, cx, d, c, forceStart = false, forceEnd = true) ?: return null
        val hn = half.n
        val outN = hn + hn - 1
        val xy = DoubleArray(outN * 2)
        val flags = BooleanArray(outN)
        var k = 0
        for (i in 0 until hn) { xy[k * 2] = half.xy[i * 2]; xy[k * 2 + 1] = half.xy[i * 2 + 1]; flags[k] = half.corner[i]; k++ }
        for (i in hn - 2 downTo 0) { xy[k * 2] = 2 * cx - half.xy[i * 2]; xy[k * 2 + 1] = half.xy[i * 2 + 1]; flags[k] = half.corner[i]; k++ }
        if (midCorner >= 0) flags[hn - 1] = true
        return Shape(xy, flags, closed = false)
    }
}
