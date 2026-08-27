package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * CPU reference implementation for Graffux's Color Smudge paint-op.
 *
 * The decomposition is intentionally similar to mature paint engines: dab placement is independent
 * of the smudge strategy, smudging and colour deposition have independent rates, and the first dab
 * seeds state rather than manufacturing colour from transparency.
 *
 * [Mode.SMEAR] is the compatibility path for Graffux's original Smudge tool. With
 * [Settings.colorRate] == 0, [Settings.opacity] == 1, [Settings.smearAlpha] == true and the historical
 * radius/rate values, it performs the same carrier-buffer operation that previously lived inside
 * ImageProcessor.smudgeAlong.
 *
 * [Mode.DULLING] is the second Color Smudge primitive: instead of carrying a spatial patch from the
 * previous dab, it samples a weighted colour around the current dab and lays that local mixture down
 * through the brush mask. That gives paint-mixing behaviour without pretending a blur is a smudge.
 *
 * This is the correctness/reference path. Once its pixel behaviour is pinned by tests, Vulkan can
 * implement the same resolved dab operation without changing brush semantics.
 */
object ColorSmudgeEngine {

    enum class Mode { SMEAR, DULLING }

    data class Settings(
        val mode: Mode = Mode.SMEAR,
        /** Amount of carried/sampled colour retained, 0..1. */
        val smudgeRate: Float = 1f,
        /** Independent foreground-paint deposition, 0 = pure smudge. */
        val colorRate: Float = 0f,
        /** Overall dab coverage multiplier. */
        val opacity: Float = 1f,
        /** Radius of the brush footprint in pixels. */
        val radiusPx: Float,
        /** Dulling sample radius relative to [radiusPx]. */
        val smudgeRadius: Float = 1f,
        val feathering: Float = 0f,
        val wrapAround: Boolean = false,
        /** Whether smudging transports alpha along with RGB. */
        val smearAlpha: Boolean = true,
        /** Foreground colour used only when [colorRate] > 0. */
        val paintColor: Int = Color.BLACK,
        val symmetryMode: SymmetryMode = SymmetryMode.NONE,
    )

    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
    ) {
        if (stroke.isEmpty() || width <= 0 || height <= 0 || pixels.size < width * height) return
        applyOne(pixels, width, height, stroke, settings)
        for (transform in symmetryTransforms(settings.symmetryMode, width.toFloat(), height.toFloat())) {
            // Sequential application is intentional and matches the historical tool: when mirrored
            // smudge footprints overlap they interact with the pixels left by earlier twins.
            applyOne(pixels, width, height, stroke.map(transform), settings.copy(symmetryMode = SymmetryMode.NONE))
        }
    }

    private fun applyOne(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Offset>,
        settings: Settings,
    ) {
        val radius = settings.radiusPx.coerceAtLeast(1f)
        val path = resample(stroke, (radius / 2f).coerceAtLeast(1f))
        if (path.isEmpty()) return
        val kernel = BrushKernel(radius, settings.feathering)
        when (settings.mode) {
            Mode.SMEAR -> smear(pixels, width, height, path, kernel, settings)
            Mode.DULLING -> dull(pixels, width, height, path, kernel, settings)
        }
    }

    /** Spatial carrier. This is Graffux's original directional smudge, extracted intact in shape. */
    private fun smear(
        pixels: IntArray,
        width: Int,
        height: Int,
        path: List<Offset>,
        kernel: BrushKernel,
        settings: Settings,
    ) {
        val rate = settings.smudgeRate.coerceIn(0f, 1f)
        val coverageScale = settings.opacity.coerceIn(0f, 1f)
        val colorRate = settings.colorRate.coerceIn(0f, 1f)
        val carrier = IntArray(kernel.size)
        val start = path.first()

        forEachKernel(kernel) { dx, dy, k, _ ->
            val sx = (start.x.toInt() + dx).coerceIn(0, width - 1)
            val sy = (start.y.toInt() + dy).coerceIn(0, height - 1)
            carrier[k] = pixels[sy * width + sx]
        }

        for (i in 1 until path.size) {
            val cx = path[i].x.toInt()
            val cy = path[i].y.toInt()
            forEachKernel(kernel) { dx, dy, k, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel

                val under = pixels[idx]
                carrier[k] = lerpArgb(under, carrier[k], rate, includeAlpha = settings.smearAlpha)
                var out = lerpArgb(
                    under,
                    carrier[k],
                    mask * coverageScale,
                    includeAlpha = settings.smearAlpha,
                )
                if (colorRate > 0f) {
                    out = lerpArgb(
                        out,
                        settings.paintColor,
                        mask * coverageScale * colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    private fun dull(
        pixels: IntArray,
        width: Int,
        height: Int,
        path: List<Offset>,
        kernel: BrushKernel,
        settings: Settings,
    ) {
        val smudgeRate = settings.smudgeRate.coerceIn(0f, 1f)
        val colorRate = settings.colorRate.coerceIn(0f, 1f)
        val coverageScale = settings.opacity.coerceIn(0f, 1f)
        val sampleRadius = (settings.radiusPx * settings.smudgeRadius.coerceAtLeast(0.05f))
            .roundToInt().coerceAtLeast(1)

        for (i in 1 until path.size) {
            val cx = path[i].x.toInt()
            val cy = path[i].y.toInt()
            val sampled = weightedAverage(
                pixels,
                width,
                height,
                cx,
                cy,
                sampleRadius,
                settings.wrapAround,
            )

            forEachKernel(kernel) { dx, dy, _, mask ->
                if (mask <= 0f) return@forEachKernel
                val idx = indexOf(cx + dx, cy + dy, width, height, settings.wrapAround)
                if (idx < 0) return@forEachKernel
                val under = pixels[idx]
                var out = lerpArgb(
                    under,
                    sampled,
                    mask * coverageScale * smudgeRate,
                    includeAlpha = settings.smearAlpha,
                )
                if (colorRate > 0f) {
                    out = lerpArgb(
                        out,
                        settings.paintColor,
                        mask * coverageScale * colorRate,
                        includeAlpha = true,
                    )
                }
                pixels[idx] = out
            }
        }
    }

    private class BrushKernel(radius: Float, feathering: Float) {
        val r: Int = radius.toInt().coerceAtLeast(1)
        val diameter: Int = r * 2 + 1
        val mask: FloatArray = FloatArray(diameter * diameter)
        val size: Int get() = mask.size

        init {
            val soft = 0.25f + feathering.coerceIn(0f, 1f) * 0.7f
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val t = hypot(dx.toFloat(), dy.toFloat()) / r
                    mask[(dy + r) * diameter + (dx + r)] = when {
                        t >= 1f -> 0f
                        t <= 1f - soft -> 1f
                        else -> {
                            val u = (1f - t) / soft
                            u * u * (3f - 2f * u)
                        }
                    }
                }
            }
        }
    }

    private inline fun forEachKernel(
        kernel: BrushKernel,
        block: (dx: Int, dy: Int, index: Int, mask: Float) -> Unit,
    ) {
        for (dy in -kernel.r..kernel.r) {
            for (dx in -kernel.r..kernel.r) {
                val k = (dy + kernel.r) * kernel.diameter + (dx + kernel.r)
                block(dx, dy, k, kernel.mask[k])
            }
        }
    }

    private fun indexOf(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        wrapAround: Boolean,
    ): Int {
        val ix: Int
        val iy: Int
        if (wrapAround) {
            ix = ((x % width) + width) % width
            iy = ((y % height) + height) % height
        } else {
            if (x !in 0 until width || y !in 0 until height) return -1
            ix = x
            iy = y
        }
        return iy * width + ix
    }

    private fun weightedAverage(
        pixels: IntArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
        wrapAround: Boolean,
    ): Int {
        var sumA = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumW = 0.0
        val rr = radius.toFloat()

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val distance = hypot(dx.toFloat(), dy.toFloat())
                if (distance > rr) continue
                val idx = indexOf(cx + dx, cy + dy, width, height, wrapAround)
                if (idx < 0) continue
                val weight = (1f - distance / rr).coerceIn(0f, 1f)
                if (weight <= 0f) continue
                val p = pixels[idx]
                sumA += (p ushr 24 and 0xFF) * weight
                sumR += (p shr 16 and 0xFF) * weight
                sumG += (p shr 8 and 0xFF) * weight
                sumB += (p and 0xFF) * weight
                sumW += weight
            }
        }

        if (sumW <= 0.0) {
            val idx = indexOf(cx, cy, width, height, wrapAround)
            return if (idx >= 0) pixels[idx] else Color.TRANSPARENT
        }
        return Color.argb(
            (sumA / sumW).roundToInt().coerceIn(0, 255),
            (sumR / sumW).roundToInt().coerceIn(0, 255),
            (sumG / sumW).roundToInt().coerceIn(0, 255),
            (sumB / sumW).roundToInt().coerceIn(0, 255),
        )
    }

    private fun lerpArgb(a: Int, b: Int, t: Float, includeAlpha: Boolean): Int {
        val f = t.coerceIn(0f, 1f)
        val aa = a ushr 24 and 0xFF
        val ba = b ushr 24 and 0xFF
        val ia = if (includeAlpha) aa + (ba - aa) * f else aa.toFloat()
        val ir = (a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * f
        val ig = (a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * f
        val ib = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * f
        return (ia.roundToInt().coerceIn(0, 255) shl 24) or
            (ir.roundToInt().coerceIn(0, 255) shl 16) or
            (ig.roundToInt().coerceIn(0, 255) shl 8) or
            ib.roundToInt().coerceIn(0, 255)
    }

    private fun resample(stroke: List<Offset>, step: Float): List<Offset> {
        if (stroke.size < 2) return stroke
        val out = ArrayList<Offset>(stroke.size * 2)
        out.add(stroke.first())
        for (i in 1 until stroke.size) {
            val a = stroke[i - 1]
            val b = stroke[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            val n = ceil(len / step).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toFloat() / n
                out.add(Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out
    }

    private fun symmetryTransforms(mode: SymmetryMode, w: Float, h: Float): List<(Offset) -> Offset> {
        val cx = w / 2f
        val cy = h / 2f
        return when (mode) {
            SymmetryMode.NONE -> emptyList()
            SymmetryMode.VERTICAL -> listOf({ p: Offset -> Offset(w - p.x, p.y) })
            SymmetryMode.HORIZONTAL -> listOf({ p: Offset -> Offset(p.x, h - p.y) })
            SymmetryMode.QUADRANT -> listOf(
                { p: Offset -> Offset(w - p.x, p.y) },
                { p: Offset -> Offset(p.x, h - p.y) },
                { p: Offset -> Offset(w - p.x, h - p.y) },
            )
            SymmetryMode.RADIAL_6 -> (1..5).map { k ->
                val rad = Math.toRadians(60.0 * k)
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                { p: Offset ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    Offset(cx + dx * c - dy * s, cy + dx * s + dy * c)
                }
            }
        }
    }
}