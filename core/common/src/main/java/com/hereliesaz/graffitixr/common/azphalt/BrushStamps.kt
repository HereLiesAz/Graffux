package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// Single-precision so the per-dab trig stays in Float and hits the fast atan2/cos/sin overloads —
// no Float→Double→Float round-tripping on the hot path.
private const val RAD_TO_DEG = 57.29578f
private const val DEG_TO_RAD = 0.017453292f

/**
 * A single concrete stamp instance the renderer draws: its centre ([x],[y] in the stroke's units), its
 * [radius] in the same units, per-dab [alpha] (`0..1`, already folding in the brush opacity), and the
 * [angleDeg] the stamp is rotated to. A round tip ignores [angleDeg]; a shaped stamp honours it.
 *
 * The trailing fields are resolved sensor dynamics. They default to identities so old callers and
 * brushes remain source- and pixel-compatible. Size/opacity/scatter/rotation are already baked into
 * the first five fields; flow and colour dynamics are carried alongside for renderers that support
 * them without forcing the geometry engine to know about Android colour APIs.
 */
data class Dab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
    val flowMultiplier: Float = 1f,
    val hueShiftDeg: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val valueMultiplier: Float = 1f,
)

/**
 * The stamp-spacing core of a raster brush: turns a recorded stroke poly-line into the ordered list of
 * dab centres a stamp brush lays down. This is the "how does a serious raster painter do it" heart of
 * a stroke — the tip is stamped by travelled arc length rather than input-event count, so a quick flick
 * and a slow drag paint the same density unless the brush deliberately maps speed to spacing.
 *
 * Pure and Android-free (interleaved `[x0,y0,x1,y1,…]`, same convention as [com.hereliesaz.graffitixr
 * .common.model.PathSimplify]) so the spacing maths is unit-tested; the editor's Android renderer takes
 * these centres, applies per-dab dynamics, and rasterizes the stamp at each.
 */
object BrushStamps {

    /**
     * Resample [points] into dab centres [stepPx] apart along the stroke's arc length. The first point
     * is always emitted; subsequent dabs are interpolated at each multiple of [stepPx] of travelled
     * distance, so coverage is even independent of the input's point density. Degenerate input (fewer
     * than 2 points) returns the single point (or empty); a non-positive [stepPx] is treated as a tiny
     * step so callers never divide by zero.
     *
     * @param stepPx dab spacing in the SAME units as [points] — i.e. `brush.spacing * diameterPx`.
     */
    fun place(points: List<Float>, stepPx: Float): List<Float> {
        val n = points.size / 2
        if (n == 0) return emptyList()
        if (n == 1) return listOf(points[0], points[1])
        val step = if (stepPx > 0f) stepPx else 0.01f

        val out = ArrayList<Float>()
        // First dab sits on the stroke start.
        out.add(points[0]); out.add(points[1])

        var nextAt = step          // arc-length at which the next dab is due
        var travelled = 0f         // arc-length consumed up to the start of the current segment
        for (i in 0 until n - 1) {
            val ax = points[2 * i]; val ay = points[2 * i + 1]
            val bx = points[2 * i + 2]; val by = points[2 * i + 3]
            val segLen = hypot(bx - ax, by - ay)
            if (segLen == 0f) continue
            // Emit every dab whose arc-length target falls within this segment.
            while (nextAt <= travelled + segLen) {
                val t = (nextAt - travelled) / segLen
                out.add(ax + (bx - ax) * t)
                out.add(ay + (by - ay) * t)
                nextAt += step
            }
            travelled += segLen
        }
        return out
    }

    /**
     * Legacy/static expansion used by brushes with no sensor routes. Kept byte-for-byte in spirit so
     * existing brushes do not change just because the richer engine exists beside them.
     */
    fun dabs(points: List<Float>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        val diameter = diameterPx.coerceAtLeast(0f)
        // A zero-width tip paints nothing; bail before place()'s tiny-step guard spawns a huge dab count.
        if (diameter <= 0f) return emptyList()
        val baseRadius = diameter / 2f
        val centres = place(points, brush.spacing * diameter)
        val count = centres.size / 2
        if (count == 0) return emptyList()

        val rng = Random(seed)
        val out = ArrayList<Dab>(count)
        for (i in 0 until count) {
            val cx = centres[2 * i]; val cy = centres[2 * i + 1]
            // Local heading from adjacent centres (forward for the first dab, backward otherwise), so a
            // scatter offset is perpendicular to travel and followStroke tracks the stroke direction.
            val headingDeg = headingAt(centres, i, count)

            // Draw every random the same way each dab, in the same order, so removing one dynamic never
            // shifts another's stream — determinism holds regardless of which brush params are active.
            val sizeR = rng.nextFloat()
            val opacR = rng.nextFloat()
            val scatR = rng.nextFloat()

            val radius = baseRadius * (1f - brush.sizeJitter * sizeR)
            val alpha = (brush.opacity * (1f - brush.opacityJitter * opacR)).coerceIn(0f, 1f)

            var x = cx; var y = cy
            if (brush.scatter > 0f && diameter > 0f) {
                val mag = brush.scatter * diameter * (scatR * 2f - 1f)   // ±scatter·diameter
                val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                x += mag * cos(perpRad)
                y += mag * sin(perpRad)
            }
            val angle = brush.angle + if (brush.followStroke) headingDeg else 0f
            out.add(Dab(x, y, radius, alpha, angle))
        }
        return out
    }

    /**
     * Sensor-aware expansion. This is the Krita-shaped path: the input is a canonical stream of
     * [BrushSample]s, each dab resolves the brush's [AzphaltBrush.dynamics], and the resulting render
     * instruction is deterministic from `(samples, brush, seed)`.
     *
     * Predicted samples are discarded here by design. Prediction may extend the presentation tail,
     * but authoritative dab placement/replay is derived exclusively from real samples.
     *
     * Spacing itself may be dynamic. The next dab's arc target is advanced by the spacing multiplier
     * resolved at the current dab, so speed→spacing and pressure→spacing behave continuously rather
     * than changing input-event density. Other mapped targets alter the concrete dab directly.
     *
     * Named separately from [dabs] because JVM generic erasure makes `List<Float>` and
     * `List<BrushSample>` the same method signature.
     */
    fun dynamicDabs(samples: List<BrushSample>, diameterPx: Float, brush: AzphaltBrush, seed: Long): List<Dab> {
        val real = samples.filterNot { it.predicted }
        val diameter = diameterPx.coerceAtLeast(0f)
        if (real.isEmpty() || diameter <= 0f) return emptyList()
        if (brush.dynamics.isEmpty()) {
            val points = ArrayList<Float>(real.size * 2)
            real.forEach { points.add(it.x); points.add(it.y) }
            return dabs(points, diameter, brush, seed)
        }

        val arc = cumulativeArc(real)
        val total = arc.last()
        val baseRadius = diameter / 2f
        val rng = Random(seed)
        val out = ArrayList<Dab>()
        val startTime = real.first().uptimeMillis
        var at = 0f
        var index = 0

        // A stationary/tap stroke still gets one dab at the first sample.
        do {
            val sample = interpolateSample(real, arc, at)
            val dynamic = BrushSensorEngine.resolve(sample, brush.dynamics, startTime, seed, index)

            // Keep the legacy RNG cadence: exactly three draws for every emitted dab, regardless of
            // which sensor routes are active. RANDOM_DAB/RANDOM_STROKE use a separate hash stream.
            val sizeR = rng.nextFloat()
            val opacR = rng.nextFloat()
            val scatR = rng.nextFloat()

            val radius = baseRadius * dynamic.sizeMultiplier * (1f - brush.sizeJitter * sizeR)
            val alpha = (
                brush.opacity * dynamic.opacityMultiplier * (1f - brush.opacityJitter * opacR)
                ).coerceIn(0f, 1f)
            val headingDeg = sample.drawingAngleDeg

            var x = sample.x
            var y = sample.y
            val scatter = brush.scatter * dynamic.scatterMultiplier
            if (scatter > 0f) {
                val mag = scatter * diameter * (scatR * 2f - 1f)
                val perpRad = (headingDeg + 90f) * DEG_TO_RAD
                x += mag * cos(perpRad)
                y += mag * sin(perpRad)
            }

            val angle = brush.angle +
                (if (brush.followStroke) headingDeg else 0f) +
                dynamic.rotationOffsetDeg
            out.add(
                Dab(
                    x = x,
                    y = y,
                    radius = radius.coerceAtLeast(0f),
                    alpha = alpha,
                    angleDeg = angle,
                    flowMultiplier = dynamic.flowMultiplier,
                    hueShiftDeg = dynamic.hueShiftDeg,
                    saturationMultiplier = dynamic.saturationMultiplier,
                    valueMultiplier = dynamic.valueMultiplier,
                )
            )

            if (total <= 0f) break
            val step = (brush.spacing * diameter * dynamic.spacingMultiplier).coerceAtLeast(0.01f)
            at += step
            index++
        } while (at <= total)

        return out
    }

    /** Geometric cumulative distance used only for locating dabs in the current render space. */
    private fun cumulativeArc(samples: List<BrushSample>): FloatArray {
        val out = FloatArray(samples.size)
        for (i in 1 until samples.size) {
            out[i] = out[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
        }
        return out
    }

    /**
     * Interpolate telemetry at geometric arc distance [target]. Position follows render-space arc;
     * recorded speed/distance/time remain sensor-space values so bitmap scaling cannot change how the
     * hand motion is interpreted.
     */
    private fun interpolateSample(samples: List<BrushSample>, arc: FloatArray, target: Float): BrushSample {
        if (samples.size == 1 || target <= 0f) {
            return samples.first().copy(predicted = false)
        }
        if (target >= arc.last()) {
            val last = samples.last()
            val prev = samples[samples.lastIndex - 1]
            return last.copy(
                drawingAngleDeg = headingDeg(prev.x, prev.y, last.x, last.y, last.drawingAngleDeg),
                predicted = false,
            )
        }

        var hi = 1
        while (hi < arc.size && arc[hi] < target) hi++
        val lo = hi - 1
        val span = (arc[hi] - arc[lo]).coerceAtLeast(1e-6f)
        val t = ((target - arc[lo]) / span).coerceIn(0f, 1f)
        val a = samples[lo]
        val b = samples[hi]
        val heading = headingDeg(a.x, a.y, b.x, b.y, a.drawingAngleDeg)

        return BrushSample(
            x = lerp(a.x, b.x, t),
            y = lerp(a.y, b.y, t),
            uptimeMillis = lerpLong(a.uptimeMillis, b.uptimeMillis, t),
            pressure = lerp(a.pressure, b.pressure, t),
            tiltRadians = lerp(a.tiltRadians, b.tiltRadians, t),
            orientationRadians = lerp(a.orientationRadians, b.orientationRadians, t),
            distancePx = lerp(a.distancePx, b.distancePx, t),
            speedPxPerMs = lerp(a.speedPxPerMs, b.speedPxPerMs, t),
            drawingAngleDeg = heading,
            predicted = false,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun lerpLong(a: Long, b: Long, t: Float): Long = a + ((b - a) * t).toLong()

    private fun headingDeg(ax: Float, ay: Float, bx: Float, by: Float, fallback: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0f && dy == 0f) return fallback
        return atan2(dy, dx) * RAD_TO_DEG
    }

    /**
     * Radial alpha coverage of a round stamp at normalized radius [rNorm] (0 = centre, 1 = edge) for a
     * given [hardness] (0..1). Fully covered out to `rNorm = hardness`, then a linear ramp to zero at
     * the edge — the profile a renderer feeds a radial gradient (`hardness` is the solid-core stop). A
     * hard brush (`hardness = 1`) is a crisp disc; a soft one (`hardness = 0`) fades from the centre.
     */
    fun stampCoverage(rNorm: Float, hardness: Float): Float {
        if (rNorm <= 0f) return 1f
        if (rNorm >= 1f) return 0f
        val h = hardness.coerceIn(0f, 1f)
        if (rNorm <= h) return 1f
        val denom = 1f - h
        if (denom <= 1e-4f) return 1f   // effectively hard; avoid divide-by-zero
        return ((1f - rNorm) / denom).coerceIn(0f, 1f)
    }

    /**
     * Procreate-style flow build-up: the new coverage after laying a dab of [flow] (0..1) over an area
     * already at [current] (0..1). Each dab adds `flow · (1 − current)`, so overlapping dabs approach —
     * but never overshoot — full opacity, and a low flow paints in gradually. `flow = 1` snaps to full.
     */
    fun buildUp(current: Float, flow: Float): Float {
        val c = current.coerceIn(0f, 1f)
        return (c + flow.coerceIn(0f, 1f) * (1f - c)).coerceIn(0f, 1f)
    }

    /** Stroke heading in degrees at dab [i] of [count], read from neighbouring centres; 0 if undefined. */
    private fun headingAt(centres: List<Float>, i: Int, count: Int): Float {
        if (count < 2) return 0f
        val (a, b) = if (i == 0) 0 to 1 else (i - 1) to i
        val dx = centres[2 * b] - centres[2 * a]
        val dy = centres[2 * b + 1] - centres[2 * a + 1]
        if (dx == 0f && dy == 0f) return 0f
        return atan2(dy, dx) * RAD_TO_DEG
    }

    /** Total arc length of a poly-line — the stroke length a caller divides by [stepPx] for a dab count. */
    fun length(points: List<Float>): Float {
        val n = points.size / 2
        var total = 0f
        for (i in 0 until n - 1) {
            total += hypot(points[2 * i + 2] - points[2 * i], points[2 * i + 3] - points[2 * i + 1])
        }
        return total
    }
}
