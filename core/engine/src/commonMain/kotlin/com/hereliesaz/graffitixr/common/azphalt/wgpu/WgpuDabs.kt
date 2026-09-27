package com.hereliesaz.graffitixr.common.azphalt.wgpu

import com.hereliesaz.graffitixr.common.azphalt.ArgbColor
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.Dab
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Packs [Dab]s into the GPU stamp engines' 16-float `GpuDab` record (StampEngine.h) and converts
 * between straight ARGB ints and the engines' premultiplied RGBA8 layer bytes. Pure math, shared by
 * every target; used with the wgpu engine's JVM wrapper, WgpuStampEngine (jvmSharedMain).
 */
object WgpuDabs {
    private const val FLOATS = 16

    /**
     * Resolved round dabs with the exact per-dab colour and strength
     * [com.hereliesaz.graffitixr.common.azphalt.RoundStampCompositor] uses: colour from
     * [ArgbColor.resolveDabColor], strength `alpha(colour) * dab.alpha * flow * flowMultiplier`
     * clamped to 0..1 (carried in the dab's alpha, with colour alpha and flow at 1 so the shader's
     * product reproduces the clamp), and the dab's own hardness in the per-dab hardness slot.
     */
    fun resolvedRound(
        dabs: List<Dab>,
        colorArgb: Int,
        secondaryColorArgb: Int,
        colorSource: BrushColorSource,
        flow: Float,
    ): FloatArray {
        val out = FloatArray(dabs.size * FLOATS)
        val baseFlow = flow.coerceIn(0f, 1f)
        dabs.forEachIndexed { i, d ->
            val color = ArgbColor.resolveDabColor(colorArgb, secondaryColorArgb, colorSource, d)
            val strength = (ArgbColor.alpha(color) / 255f * d.alpha * baseFlow * max(d.flowMultiplier, 0f))
                .coerceIn(0f, 1f)
            val o = i * FLOATS
            out[o] = d.x
            out[o + 1] = d.y
            out[o + 2] = d.radius
            out[o + 3] = strength
            out[o + 4] = d.angleDeg
            out[o + 5] = ArgbColor.red(color) / 255f
            out[o + 6] = ArgbColor.green(color) / 255f
            out[o + 7] = ArgbColor.blue(color) / 255f
            out[o + 8] = 1f // colour alpha: folded into strength
            out[o + 9] = 1f // flow: folded into strength
            out[o + 10] = 1f // resolved
            out[o + 11] = d.hardness.coerceIn(0f, 1f)
            out[o + 12] = d.contactDepth
            out[o + 13] = 1f // reservoirLoad
            out[o + 14] = 1f // depositionRate
            out[o + 15] = 0f // substrateResponse
        }
        return out
    }

    /** Straight ARGB ints -> premultiplied RGBA8 bytes (Android `ARGB_8888` memory layout). */
    fun premultipliedRgba(argb: IntArray, out: ByteArray = ByteArray(argb.size * 4)): ByteArray {
        for (i in argb.indices) {
            val c = argb[i]
            val a = ArgbColor.alpha(c)
            val o = i * 4
            out[o] = premul(ArgbColor.red(c), a).toByte()
            out[o + 1] = premul(ArgbColor.green(c), a).toByte()
            out[o + 2] = premul(ArgbColor.blue(c), a).toByte()
            out[o + 3] = a.toByte()
        }
        return out
    }

    /** Premultiplied RGBA8 bytes -> straight ARGB ints, for pixels `from until to` (default: all). */
    fun straightArgb(
        rgba: ByteArray,
        out: IntArray = IntArray(rgba.size / 4),
        from: Int = 0,
        to: Int = out.size,
    ): IntArray {
        for (i in from until to) {
            val o = i * 4
            val a = rgba[o + 3].toInt() and 0xFF
            out[i] = if (a == 0) {
                0
            } else {
                ArgbColor.argb(
                    a,
                    unpremul(rgba[o].toInt() and 0xFF, a),
                    unpremul(rgba[o + 1].toInt() and 0xFF, a),
                    unpremul(rgba[o + 2].toInt() and 0xFF, a),
                )
            }
        }
        return out
    }

    private fun premul(c: Int, a: Int): Int = (c * a / 255f).roundToInt()

    private fun unpremul(c: Int, a: Int): Int = (c * 255f / a).roundToInt().coerceIn(0, 255)
}
