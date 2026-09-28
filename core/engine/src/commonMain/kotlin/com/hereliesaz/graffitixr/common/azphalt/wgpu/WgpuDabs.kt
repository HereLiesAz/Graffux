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
     * Float offsets inside one `GpuDab`, mirroring the `Dab` struct comments in
     * core/wgpu-engine/src/shaders/stamp.wgsl and stamp_masked.wgsl (WgpuDabsLayoutTest and the
     * Rust `layout_parity` test pin both sides to this table).
     *
     * [DAB_SLOT_HARDNESS_OR_TIP_RATIO] is shared: the round shader (stamp.wgsl) reads it as the
     * resolved dab's hardness, the masked shader (stamp_masked.wgsl) as the tip's height/width
     * ratio. The masked shader has no per-dab hardness at all -- its tip mask already encodes the
     * falloff -- so a masked packer puts `Dab.tipRatio` there and a round packer `Dab.hardness`.
     */
    const val DAB_SLOT_X = 0
    const val DAB_SLOT_Y = 1
    const val DAB_SLOT_RADIUS = 2
    const val DAB_SLOT_ALPHA = 3
    const val DAB_SLOT_ANGLE = 4
    const val DAB_SLOT_COLOR_R = 5
    const val DAB_SLOT_COLOR_G = 6
    const val DAB_SLOT_COLOR_B = 7
    const val DAB_SLOT_COLOR_A = 8
    const val DAB_SLOT_FLOW = 9
    const val DAB_SLOT_RESOLVED = 10
    const val DAB_SLOT_HARDNESS_OR_TIP_RATIO = 11
    const val DAB_SLOT_CONTACT_DEPTH = 12
    const val DAB_SLOT_RESERVOIR_LOAD = 13
    const val DAB_SLOT_DEPOSITION_RATE = 14
    const val DAB_SLOT_SUBSTRATE_RESPONSE = 15

    /** Floats per `ColorSmudgeDab` (core/wgpu-engine/src/engine.rs), = WgpuStampEngine.SMUDGE_FLOATS. */
    const val SMUDGE_FLOATS = 11

    /**
     * One Color Smudge dab, in the Rust `ColorSmudgeDab` field order. [baseColorRate],
     * [chargeDecayRate] and [pickupRate] are the stroke-wide reservoir settings, which the wgpu
     * engine carries per dab (the Android C++ engines take them as call arguments instead).
     */
    data class SmudgeDab(
        val x: Float,
        val y: Float,
        val smudgeRate: Float,
        val colorRate: Float,
        val opacity: Float,
        val smudgeRadius: Float,
        val colorRateMultiplier: Float = 1f,
        val distanceDeltaPx: Float = 0f,
        val baseColorRate: Float = 0f,
        val chargeDecayRate: Float = 0f,
        val pickupRate: Float = 0f,
    )

    /** Packs [dabs] as [SMUDGE_FLOATS] floats each, for `WgpuStampEngine.colorSmudge`. */
    fun colorSmudge(dabs: List<SmudgeDab>): FloatArray {
        val out = FloatArray(dabs.size * SMUDGE_FLOATS)
        dabs.forEachIndexed { i, d ->
            val o = i * SMUDGE_FLOATS
            out[o] = d.x
            out[o + 1] = d.y
            out[o + 2] = d.smudgeRate
            out[o + 3] = d.colorRate
            out[o + 4] = d.opacity
            out[o + 5] = d.smudgeRadius
            out[o + 6] = d.colorRateMultiplier
            out[o + 7] = d.distanceDeltaPx
            out[o + 8] = d.baseColorRate
            out[o + 9] = d.chargeDecayRate
            out[o + 10] = d.pickupRate
        }
        return out
    }

    /**
     * Resolved round dabs with the exact per-dab colour and strength
     * [com.hereliesaz.graffitixr.common.azphalt.RoundStampCompositor] uses: colour from
     * [ArgbColor.resolveDabColor], strength `alpha(colour) * dab.alpha * flow * flowMultiplier`
     * clamped to 0..1 (carried in the dab's alpha, with colour alpha and flow at 1 so the shader's
     * product reproduces the clamp), and the dab's own hardness in the per-dab hardness slot.
     *
     * For `WgpuStampEngine.stampDabs` (the round shader, stamp.wgsl) only: slot 11 is hardness
     * there. Do not feed this to `stampMaskedDabs`, which reads slot 11 as the tip ratio -- see
     * [DAB_SLOT_HARDNESS_OR_TIP_RATIO].
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
            out[o + 11] = d.hardness.coerceIn(0f, 1f) // round shader: hardness (masked: tipRatio)
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
