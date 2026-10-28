package com.hereliesaz.graffitixr.common.photo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Crop tab's geometry: the aspect presets, the transform a [DocumentCrop] applies to the whole
 * document (straighten, perspective, quarter turns, flips) and Auto level. Pure math, so the
 * canvas, the export and the desktop app all use the same matrix.
 */
object CropGeometry {

    /** One aspect chip: its label and width / height (null for Free; Original is the document's). */
    data class Aspect(val id: String, val label: String, val ratio: Float?)

    val ASPECTS: List<Aspect> = listOf(
        Aspect("free", "Free", null),
        Aspect("original", "Original", null),
        Aspect("1:1", "1:1", 1f),
        Aspect("4:5", "4:5", 4f / 5f),
        Aspect("3:2", "3:2", 3f / 2f),
        Aspect("16:9", "16:9", 16f / 9f),
        Aspect("9:16", "9:16", 9f / 16f),
    )

    fun aspect(id: String): Aspect = ASPECTS.firstOrNull { it.id == id } ?: ASPECTS.first()

    /** The width / height [aspect] asks for on a [docW] x [docH] document; null for Free. */
    fun ratioOf(aspect: String, docW: Int, docH: Int): Float? = when (aspect) {
        "free" -> null
        "original" -> docW.toFloat() / docH.coerceAtLeast(1)
        else -> aspect(aspect).ratio
    }

    /** Most a perspective slider at ±100 tilts the far edge: 40% narrower or wider. */
    private const val PERSPECTIVE_MAX = 0.4f

    /**
     * The 3x3 matrix (row-major, as Android's `Matrix.setValues` takes it) that draws the document
     * as [crop] has it, about the artboard centre ([cx], [cy]), the artboard being [w] x [h]:
     * perspective (a keystone: vertical narrows or widens the top, horizontal the left), then the
     * flips, then the quarter turns and the straighten angle. The crop rectangle is applied after,
     * as a clip of the result.
     */
    fun matrix(crop: DocumentCrop, cx: Float, cy: Float, w: Float, h: Float): FloatArray {
        val py = crop.vertical / 100f * PERSPECTIVE_MAX / (h / 2f).coerceAtLeast(1f)
        val px = crop.horizontal / 100f * PERSPECTIVE_MAX / (w / 2f).coerceAtLeast(1f)
        val persp = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, px, py, 1f)
        val flip = floatArrayOf(if (crop.flipH) -1f else 1f, 0f, 0f, 0f, if (crop.flipV) -1f else 1f, 0f, 0f, 0f, 1f)
        val a = (crop.quarterTurns * 90f + crop.straighten) * (PI / 180.0).toFloat()
        val c = cos(a)
        val s = sin(a)
        val rot = floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        val toOrigin = floatArrayOf(1f, 0f, -cx, 0f, 1f, -cy, 0f, 0f, 1f)
        val back = floatArrayOf(1f, 0f, cx, 0f, 1f, cy, 0f, 0f, 1f)
        return mul(back, mul(rot, mul(flip, mul(persp, toOrigin))))
    }

    fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3
        val col = i % 3
        a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col]
    }

    /** [m] applied to the point ([x], [y]). */
    fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val wq = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / wq to (m[3] * x + m[4] * y + m[5]) / wq
    }

    /**
     * [crop]'s rectangle reshaped to [ratio] (width / height, in document pixels of a [docW] x
     * [docH] document): the largest such rectangle centred where the current one is.
     */
    fun withAspect(crop: DocumentCrop, aspect: String, docW: Int, docH: Int): DocumentCrop {
        val ratio = ratioOf(aspect, docW, docH) ?: return crop.copy(aspect = aspect)
        val cw = (crop.right - crop.left) * docW
        val ch = (crop.bottom - crop.top) * docH
        val (nw, nh) = if (cw / ch > ratio) ch * ratio to ch else cw to cw / ratio
        val mx = (crop.left + crop.right) / 2f
        val my = (crop.top + crop.bottom) / 2f
        return crop.copy(
            left = mx - nw / docW / 2f, right = mx + nw / docW / 2f,
            top = my - nh / docH / 2f, bottom = my + nh / docH / 2f,
            aspect = aspect,
        )
    }

    /**
     * A quarter turn clockwise: the content turns, and the crop turns with it, so a portrait crop
     * becomes the same crop lying down (the frame then may reach past the old artboard).
     */
    fun rotateQuarter(crop: DocumentCrop, docW: Int, docH: Int): DocumentCrop {
        val cx = 0.5f
        val cy = 0.5f
        // The rectangle in document pixels about the centre, turned 90 degrees clockwise.
        val l = (crop.left - cx) * docW
        val r = (crop.right - cx) * docW
        val t = (crop.top - cy) * docH
        val b = (crop.bottom - cy) * docH
        return crop.copy(
            left = cx + (-b) / docW, right = cx + (-t) / docW,
            top = cy + l / docH, bottom = cy + r / docH,
            quarterTurns = (crop.quarterTurns + 1) % 4,
        )
    }

    /**
     * Auto level: the straighten angle (degrees, within ±[maxDegrees]) that brings the photo's
     * dominant near-horizontal and near-vertical edges level. [luma] is a [w] x [h] grey image.
     * Each pixel's Sobel gradient votes, by its strength, for its edge's deviation from the nearest
     * of horizontal or vertical; the peak of those votes (half-degree bins) is the tilt, and the
     * answer undoes it. 0 when no edge stands out.
     */
    fun autoLevel(luma: FloatArray, w: Int, h: Int, maxDegrees: Float = 15f): Float {
        val binsPerDegree = 2
        val bins = FloatArray((2 * maxDegrees * binsPerDegree).roundToInt() + 1)
        var total = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun l(dx: Int, dy: Int) = luma[(y + dy) * w + x + dx]
            val gx = (l(1, -1) + 2 * l(1, 0) + l(1, 1)) - (l(-1, -1) + 2 * l(-1, 0) + l(-1, 1))
            val gy = (l(-1, 1) + 2 * l(0, 1) + l(1, 1)) - (l(-1, -1) + 2 * l(0, -1) + l(1, -1))
            val mag = hypot(gx, gy)
            if (mag < MIN_EDGE) continue
            // The edge runs perpendicular to its gradient; fold its direction into -45..45 of the
            // nearest axis.
            var edge = (atan2(gy, gx) * (180.0 / PI)).toFloat() + 90f
            edge = ((edge % 90f) + 90f) % 90f
            if (edge > 45f) edge -= 90f
            if (abs(edge) > maxDegrees) continue
            bins[((edge + maxDegrees) * binsPerDegree).roundToInt()] += mag
            total += mag
        }
        if (total <= 0f) return 0f
        val peak = bins.indices.maxBy { bins[it] }
        if (bins[peak] < total * MIN_PEAK_SHARE) return 0f
        val tilt = peak.toFloat() / binsPerDegree - maxDegrees
        return -tilt
    }

    private const val MIN_EDGE = 0.25f
    private const val MIN_PEAK_SHARE = 0.08f
}
