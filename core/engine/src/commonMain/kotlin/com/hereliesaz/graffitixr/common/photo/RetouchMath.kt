package com.hereliesaz.graffitixr.common.photo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Heal and Red-eye layers' pixel work, on premultiplied RGBA8 (an Android `ARGB_8888`
 * bitmap's memory), [w] x [h]. Each writes only its spots into `out` (transparent elsewhere), so
 * the layer drawn over the photo changes nothing but them.
 *
 * Heal is the Clone tool's idea (copy from an offset source) made content-aware: the source is
 * picked by matching the ring of pixels round the spot ([autoSource]), and the copy is shifted by
 * the difference of the two rings' mean colours, so a patch from a lighter area lands at the
 * target's brightness. Spots are done in order, each reading the photo as it is under them (the
 * layers below), not earlier patches.
 */
object RetouchMath {

    /** Candidate directions round the spot, and how far out (in radii) a source is sought. */
    private const val DIRECTIONS = 16
    private val DISTANCES = floatArrayOf(2.2f, 2.8f, 3.6f)
    private const val RING_SAMPLES = 32
    private const val RING_AT = 1.15f

    private fun px(src: ByteArray, w: Int, h: Int, x: Int, y: Int, out: FloatArray): Boolean {
        if (x !in 0 until w || y !in 0 until h) return false
        val i = (y * w + x) * 4
        for (k in 0..3) out[k] = (src[i + k].toInt() and 0xFF) / 255f
        return true
    }

    /** Mean premultiplied colour on a ring of radius [r] round ([x], [y]); null when it leaves the image. */
    fun ringMean(src: ByteArray, w: Int, h: Int, x: Float, y: Float, r: Float): FloatArray? {
        val acc = FloatArray(4)
        val c = FloatArray(4)
        for (k in 0 until RING_SAMPLES) {
            val a = 2.0 * PI * k / RING_SAMPLES
            if (!px(src, w, h, (x + r * cos(a)).roundToInt(), (y + r * sin(a)).roundToInt(), c)) return null
            for (j in 0..3) acc[j] += c[j]
        }
        return FloatArray(4) { acc[it] / RING_SAMPLES }
    }

    /**
     * Where a spot at ([x], [y]) of radius [r] borrows from: of the candidates 2.2 to 3.6 radii
     * away in sixteen directions (wholly inside the image), the one whose ring of pixels best
     * matches the spot's own ring, sample for sample. Falls back to straight up.
     */
    fun autoSource(src: ByteArray, w: Int, h: Int, x: Float, y: Float, r: Float): Pair<Float, Float> {
        val ringR = r * RING_AT
        val target = ringSamples(src, w, h, x, y, ringR)
        var best: Pair<Float, Float>? = null
        var bestScore = Float.MAX_VALUE
        for (dist in DISTANCES) for (k in 0 until DIRECTIONS) {
            val a = 2.0 * PI * k / DIRECTIONS
            val cx = x + dist * r * cos(a).toFloat()
            val cy = y + dist * r * sin(a).toFloat()
            if (cx - ringR < 0 || cy - ringR < 0 || cx + ringR >= w || cy + ringR >= h) continue
            val cand = ringSamples(src, w, h, cx, cy, ringR) ?: continue
            val score = if (target == null) dist else {
                var s = 0f
                for (i in target.indices) s += (target[i] - cand[i]).let { it * it }
                s
            }
            if (score < bestScore) {
                bestScore = score
                best = cx to cy
            }
        }
        return best ?: (x to (y - 2.5f * r).coerceAtLeast(r))
    }

    private fun ringSamples(src: ByteArray, w: Int, h: Int, x: Float, y: Float, r: Float): FloatArray? {
        val out = FloatArray(RING_SAMPLES * 3)
        val c = FloatArray(4)
        for (k in 0 until RING_SAMPLES) {
            val a = 2.0 * PI * k / RING_SAMPLES
            if (!px(src, w, h, (x + r * cos(a)).roundToInt(), (y + r * sin(a)).roundToInt(), c)) return null
            for (j in 0..2) out[k * 3 + j] = c[j]
        }
        return out
    }

    /** 1 inside `1 − feather` of the radius, easing (smoothstep) to 0 at the edge. */
    fun falloff(d: Float, feather: Float): Float {
        val inner = (1f - feather.coerceIn(0f, 1f))
        if (d <= inner) return 1f
        if (d >= 1f) return 0f
        val t = (d - inner) / (1f - inner)
        return 1f - t * t * (3f - 2f * t)
    }

    /** Source-over of premultiplied [c] (alpha [c3]) onto out at [i]. */
    private fun over(out: ByteArray, i: Int, c: FloatArray) {
        val a = c[3]
        for (k in 0..3) {
            val dst = (out[i + k].toInt() and 0xFF) / 255f
            out[i + k] = ((c[k] + dst * (1f - a)).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
    }

    /**
     * Heals [spot] into [out]: the source disc copied over the target, faded by [HealSpot.feather]
     * and [HealSpot.opacity]; [contentAware] shifts it by the rings' mean difference (Clone mode
     * copies as it is).
     */
    fun heal(src: ByteArray, w: Int, h: Int, spot: HealSpot, contentAware: Boolean, out: ByteArray) {
        val r = spot.radius.coerceAtLeast(1f)
        val dx = spot.sourceX - spot.x
        val dy = spot.sourceY - spot.y
        val shift = FloatArray(4)
        if (contentAware) {
            val t = ringMean(src, w, h, spot.x, spot.y, r * RING_AT)
            val s = ringMean(src, w, h, spot.sourceX, spot.sourceY, r * RING_AT)
            if (t != null && s != null) for (k in 0..2) shift[k] = t[k] - s[k]
        }
        val c = FloatArray(4)
        val x0 = max(0, (spot.x - r).toInt())
        val x1 = min(w - 1, (spot.x + r).toInt() + 1)
        val y0 = max(0, (spot.y - r).toInt())
        val y1 = min(h - 1, (spot.y + r).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val ddx = x + 0.5f - spot.x
            val ddy = y + 0.5f - spot.y
            val f = falloff(sqrt(ddx * ddx + ddy * ddy) / r, spot.feather) * spot.opacity.coerceIn(0f, 1f)
            if (f <= 0f) continue
            if (!px(src, w, h, (x + dx).roundToInt(), (y + dy).roundToInt(), c)) continue
            // The shift is in straight colour; scale it by the sample's alpha to stay premultiplied.
            for (k in 0..2) c[k] = (c[k] + shift[k] * c[3]).coerceIn(0f, c[3])
            for (k in 0..3) c[k] *= f
            over(out, (y * w + x) * 4, c)
        }
    }

    /**
     * Red-eye on [spot] into [out]: inside the circle, where red clearly leads (r > 1.4 x both g and
     * b), red becomes the mean of green and blue; the edge fades over its outer 30%.
     */
    fun redEye(src: ByteArray, w: Int, h: Int, spot: RedEyeSpot, out: ByteArray) {
        val r = spot.radius.coerceAtLeast(1f)
        val c = FloatArray(4)
        for (y in max(0, (spot.y - r).toInt())..min(h - 1, (spot.y + r).toInt() + 1)) {
            for (x in max(0, (spot.x - r).toInt())..min(w - 1, (spot.x + r).toInt() + 1)) {
                val ddx = x + 0.5f - spot.x
                val ddy = y + 0.5f - spot.y
                val f = falloff(sqrt(ddx * ddx + ddy * ddy) / r, RED_EYE_FEATHER)
                if (f <= 0f || !px(src, w, h, x, y, c) || c[3] <= 0f) continue
                if (c[0] <= RED_LEAD * c[1] || c[0] <= RED_LEAD * c[2]) continue
                c[0] = (c[1] + c[2]) / 2f
                for (k in 0..3) c[k] *= f
                over(out, (y * w + x) * 4, c)
            }
        }
    }

    private const val RED_EYE_FEATHER = 0.3f
    private const val RED_LEAD = 1.4f
}
