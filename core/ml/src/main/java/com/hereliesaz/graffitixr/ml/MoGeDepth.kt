@file:Suppress("MagicNumber", "LongParameterList", "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth",
    "ThrowsCount", "ComplexCondition", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.ml

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What a MoGe-2 style model needs on top of the plain depth contract (see [DepthModelSpecs]): a second
 * scalar input, and a point map plus mask and scale instead of one depth plane.
 *
 * @property numTokens value fed to the `num_tokens` input (speed/quality trade; 1200 fast, 1800 default).
 */
data class PointMapSpec(
    val numTokens: Int = DEFAULT_NUM_TOKENS,
    val numTokensName: String? = "num_tokens",
    val pointsName: String = "points",
    val maskName: String? = "mask",
    val scaleName: String? = "scale",
) {
    init {
        require(numTokens > 0) { "numTokens must be positive" }
    }

    companion object {
        const val DEFAULT_NUM_TOKENS = 1800
    }
}

/**
 * A MoGe-2 answer for one image: [points] is `[H,W,3]` row-major (camera axes, x right, y down, z
 * forward, affine: the true depth is `(z + shift) * scale`), [mask] is `[H,W]` validity probability or
 * null, [scale] the metric scale.
 */
class RawPointMap(
    val points: FloatArray,
    val mask: FloatArray?,
    val scale: Float,
    val width: Int,
    val height: Int,
)

/**
 * Runs a point-map model on [input] (from [DepthPipeline.preprocess]); the point-map counterpart of
 * [DepthModelRunner].
 */
fun interface PointMapRunner {
    fun run(input: FloatArray, box: Letterbox, spec: DepthModelSpec): RawPointMap
}

/** The fitted affine correction: `depth = (z + shift) * scale`, plus the focal that went with it. */
class MoGeSolution(val shift: Float, val focalRelative: Float)

/**
 * The host-side MoGe-2 post-processing (azphalt listing README, "Running it"): the `points` map is
 * affine, so the unknown z [MoGeSolution.shift] (and, if the camera's focal is not known, the focal) are
 * fitted so that `f * xy / (z + shift)` reproduces each pixel's normalised image coordinate `uv`.
 * Free of `android.*` so it is unit-tested on the JVM.
 *
 * Failure policy: input the model should never produce (wrong sizes, no usable pixel, non-finite scale)
 * throws [DepthEstimationException.UnexpectedOutput]; a bad caller-supplied focal throws
 * [DepthEstimationException.InvalidInput]. A fit that cannot improve on shift 0 (e.g. a flat or
 * zero-xy scene with a known focal) keeps the starting shift rather than failing. No path returns NaN.
 */
object MoGeDepth {
    private const val GRID = 64
    private const val MASK_THRESHOLD = 0.5f
    private const val MIN_SAMPLES = 16
    private const val MIN_DEPTH = 1e-6
    private const val MAX_ITERATIONS = 60
    private const val FAR_FLOOR_FRACTION = 1e-3f
    private const val FAR_FLOOR_ABS = 1e-6f

    /**
     * Fits the shift (and the focal when [focalRelative] is null).
     * [focalRelative] = `focal_px / (0.5 * hypot(W, H))`.
     */
    fun solve(points: FloatArray, mask: FloatArray?, width: Int, height: Int, focalRelative: Float?): MoGeSolution {
        checkSizes(points, mask, width, height)
        if (focalRelative != null && !(focalRelative.isFinite() && focalRelative > 0f)) {
            throw DepthEstimationException.InvalidInput("The focal length must be a positive number")
        }
        val s = sample(points, mask, width, height)
        if (s.count < MIN_SAMPLES) {
            throw DepthEstimationException.UnexpectedOutput(
                "The model marked too little of the image as valid geometry (${s.count} usable samples)",
            )
        }
        var shift = if (s.minZ > MIN_DEPTH) 0.0 else MIN_DEPTH * 2 - s.minZ
        var f = focalRelative?.toDouble() ?: closedFormFocal(s, shift)
        if (!(f.isFinite() && f > 0.0)) {
            throw DepthEstimationException.UnexpectedOutput("The model's points carry no usable image-plane geometry")
        }
        val fit = focalRelative == null
        var cost = cost(s, f, shift)
        var lambda = 1e-3
        repeat(MAX_ITERATIONS) {
            val step = step(s, f, shift, fit, lambda) ?: return@repeat
            val nf = if (fit) f + step.first else f
            val ns = shift + step.second
            val ok = nf.isFinite() && nf > 0.0 && ns.isFinite() && s.minZ + ns > MIN_DEPTH
            val nc = if (ok) cost(s, nf, ns) else Double.NaN
            if (ok && nc.isFinite() && nc < cost) {
                val small = abs(step.second) < 1e-9 * (1 + abs(shift)) && abs(step.first) < 1e-9 * (1 + abs(f))
                f = nf; shift = ns; cost = nc; lambda = max(lambda / 3, 1e-12)
                if (small) return finish(shift, f)
            } else {
                lambda *= 4
                if (lambda > 1e12) return finish(shift, f)
            }
        }
        return finish(shift, f)
    }

    private fun finish(shift: Double, f: Double): MoGeSolution {
        if (!(shift.isFinite() && f.isFinite())) {
            throw DepthEstimationException.UnexpectedOutput("The shift fit did not produce a finite result")
        }
        return MoGeSolution(shift.toFloat(), f.toFloat())
    }

    /**
     * `depth = (z + shift) * scale` for every pixel; `NaN` is never returned: pixels that are masked out,
     * non-finite, or not in front of the camera after the shift are `0f` (meaning "no depth").
     */
    fun metricDepth(points: FloatArray, mask: FloatArray?, solution: MoGeSolution, scale: Float): FloatArray {
        if (!(scale.isFinite() && scale > 0f)) {
            throw DepthEstimationException.UnexpectedOutput(
                "The model's metric scale is not a positive number ($scale)",
            )
        }
        val n = points.size / 3
        val out = FloatArray(n)
        for (i in 0 until n) {
            if (mask != null && !(mask[i] > MASK_THRESHOLD)) continue
            val z = points[i * 3 + 2]
            if (!z.isFinite() || !points[i * 3].isFinite() || !points[i * 3 + 1].isFinite()) continue
            val d = (z + solution.shift) * scale
            if (d.isFinite() && d > 0f) out[i] = d
        }
        return out
    }

    /**
     * Depth (0 = none, from [metricDepth]) -> [DepthMap] (relative inverse depth, larger = nearer, all
     * finite). No-depth pixels sit at the far plane (the smallest inverse depth present), like
     * [DepthPipeline.toNearerLarger]. @throws DepthEstimationException.UnexpectedOutput when no pixel has depth.
     */
    fun toDepthMap(depth: FloatArray, width: Int, height: Int): DepthMap {
        var hi = 0f
        for (d in depth) if (d.isFinite() && d > 0f) hi = max(hi, d)
        if (hi <= 0f) throw DepthEstimationException.UnexpectedOutput("The depth model produced no usable depth")
        val floor = max(hi * FAR_FLOOR_FRACTION, FAR_FLOOR_ABS)
        val inv = FloatArray(depth.size) {
            if (depth[it].isFinite() && depth[it] > 0f) 1f / max(depth[it], floor) else 0f
        }
        var far = Float.POSITIVE_INFINITY
        for (i in depth.indices) if (depth[i] > 0f) far = min(far, inv[i])
        for (i in depth.indices) if (!(depth[i] > 0f)) inv[i] = far
        return DepthMap(width, height, inv)
    }

    /** Solve + metric depth + inversion in one call. */
    fun estimate(
        points: FloatArray, mask: FloatArray?, width: Int, height: Int, scale: Float, focalRelative: Float?,
    ): DepthMap {
        val solution = solve(points, mask, width, height, focalRelative)
        return toDepthMap(metricDepth(points, mask, solution, scale), width, height)
    }

    // ---- internals ----

    private class Samples(
        val x: DoubleArray, val y: DoubleArray, val z: DoubleArray, val ux: DoubleArray, val uy: DoubleArray,
        val count: Int, val minZ: Double,
    )

    private fun checkSizes(points: FloatArray, mask: FloatArray?, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || points.size != width * height * 3 ||
            (mask != null && mask.size != width * height)
        ) {
            throw DepthEstimationException.UnexpectedOutput(
                "The point map has ${points.size} values (mask ${mask?.size}) for ${width}x$height",
            )
        }
    }

    /** 64 x 64 nearest-neighbour subsample at `floor(i * N / 64)`, valid pixels only, with uv per the listing. */
    private fun sample(points: FloatArray, mask: FloatArray?, width: Int, height: Int): Samples {
        val rows = min(GRID, height)
        val cols = min(GRID, width)
        val cap = rows * cols
        val x = DoubleArray(cap)
        val y = DoubleArray(cap)
        val z = DoubleArray(cap)
        val ux = DoubleArray(cap)
        val uy = DoubleArray(cap)
        val diag = hypot(width.toDouble(), height.toDouble())
        var n = 0
        var minZ = Double.POSITIVE_INFINITY
        for (j in 0 until rows) {
            val r = floor(j.toDouble() * height / rows).toInt()
            for (k in 0 until cols) {
                val c = floor(k.toDouble() * width / cols).toInt()
                val i = r * width + c
                if (mask != null && !(mask[i] > MASK_THRESHOLD)) continue
                val px = points[i * 3]
                val py = points[i * 3 + 1]
                val pz = points[i * 3 + 2]
                if (!(px.isFinite() && py.isFinite() && pz.isFinite())) continue
                x[n] = px.toDouble(); y[n] = py.toDouble(); z[n] = pz.toDouble()
                ux[n] = (c - (width - 1) / 2.0) * 2 / diag
                uy[n] = (r - (height - 1) / 2.0) * 2 / diag
                minZ = min(minZ, z[n])
                n++
            }
        }
        return Samples(x, y, z, ux, uy, n, minZ)
    }

    /** `f` minimising the residual for a fixed [shift]: `sum(a . uv) / sum(a . a)`, `a = xy / (z + shift)`. */
    private fun closedFormFocal(s: Samples, shift: Double): Double {
        var num = 0.0
        var den = 0.0
        for (i in 0 until s.count) {
            val d = s.z[i] + shift
            val ax = s.x[i] / d
            val ay = s.y[i] / d
            num += ax * s.ux[i] + ay * s.uy[i]
            den += ax * ax + ay * ay
        }
        return if (den > 0.0) num / den else Double.NaN
    }

    private fun cost(s: Samples, f: Double, shift: Double): Double {
        var c = 0.0
        for (i in 0 until s.count) {
            val d = s.z[i] + shift
            val rx = f * s.x[i] / d - s.ux[i]
            val ry = f * s.y[i] / d - s.uy[i]
            c += rx * rx + ry * ry
        }
        return c
    }

    /** One damped Gauss-Newton step (df, dshift), or null when the normal equations are singular. */
    private fun step(s: Samples, f: Double, shift: Double, fitFocal: Boolean, lambda: Double): Pair<Double, Double>? {
        var aff = 0.0; var afs = 0.0; var ass = 0.0; var bf = 0.0; var bs = 0.0
        for (i in 0 until s.count) {
            val d = s.z[i] + shift
            val ax = s.x[i] / d
            val ay = s.y[i] / d
            val rx = f * ax - s.ux[i]
            val ry = f * ay - s.uy[i]
            val dfx = ax
            val dfy = ay
            val dsx = -f * ax / d
            val dsy = -f * ay / d
            aff += dfx * dfx + dfy * dfy
            afs += dfx * dsx + dfy * dsy
            ass += dsx * dsx + dsy * dsy
            bf += dfx * rx + dfy * ry
            bs += dsx * rx + dsy * ry
        }
        if (!fitFocal) {
            val a = ass * (1 + lambda)
            return if (a > 0.0 && a.isFinite() && bs.isFinite()) 0.0 to (-bs / a) else null
        }
        val a11 = aff * (1 + lambda)
        val a22 = ass * (1 + lambda)
        val det = a11 * a22 - afs * afs
        if (!(det > 0.0 && det.isFinite())) return null
        return ((-bf * a22 + bs * afs) / det) to ((-bs * a11 + bf * afs) / det)
    }
}
