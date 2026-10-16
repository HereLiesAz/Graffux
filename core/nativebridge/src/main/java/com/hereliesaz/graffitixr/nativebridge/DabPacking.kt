package com.hereliesaz.graffitixr.nativebridge

/**
 * The Kotlin -> C++ dab wire layout for `nativeStampResolvedDabs` / `nativeStampMaskedDabs`:
 * [STRIDE] floats per dab, read by `StampDynamicsJNI.cpp` (`kStride`). Both packers live here so
 * the stride exists once on the Kotlin side; `DabLayoutParityTest` pins it against the C++ reader.
 * (The desktop path packs a different, 16-float layout: see `WgpuDabs.kt` and `layout_parity.rs`.)
 *
 * Slots: x, y, radius, alpha, angleDeg, colorR, colorG, colorB, colorA, flow, hardnessOrTipRatio,
 * contactDepth, reservoirLoad, depositionRate, substrateResponse.
 */
internal object DabPacking {
    const val STRIDE = 15

    private fun r(argb: Int) = ((argb shr 16) and 0xFF) / 255f
    private fun g(argb: Int) = ((argb shr 8) and 0xFF) / 255f
    private fun b(argb: Int) = (argb and 0xFF) / 255f
    private fun a(argb: Int) = ((argb ushr 24) and 0xFF) / 255f

    fun packResolved(dabs: List<ResolvedBrushDab>): FloatArray {
        val flat = FloatArray(dabs.size * STRIDE)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * STRIDE
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
            flat[base + 5] = r(d.colorArgb)
            flat[base + 6] = g(d.colorArgb)
            flat[base + 7] = b(d.colorArgb)
            flat[base + 8] = a(d.colorArgb)
            flat[base + 9] = d.flow
            flat[base + 10] = d.hardness.coerceIn(0f, 1f)
            flat[base + 11] = d.contactDepth.coerceIn(0f, 1f)
            flat[base + 12] = d.reservoirLoad.coerceIn(0f, 1f)
            flat[base + 13] = d.depositionRate.coerceIn(0f, 1f)
            flat[base + 14] = d.substrateResponse.coerceIn(0f, 1f)
        }
        return flat
    }

    fun packMasked(dabs: List<MaskedBrushDab>): FloatArray {
        val flat = FloatArray(dabs.size * STRIDE)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * STRIDE
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
            flat[base + 5] = r(d.colorArgb)
            flat[base + 6] = g(d.colorArgb)
            flat[base + 7] = b(d.colorArgb)
            flat[base + 8] = a(d.colorArgb)
            flat[base + 9] = d.flow
            flat[base + 10] = d.tipRatio
            flat[base + 11] = d.contactDepth.coerceIn(0f, 1f)
            flat[base + 12] = d.reservoirLoad.coerceIn(0f, 1f)
            flat[base + 13] = d.depositionRate.coerceIn(0f, 1f)
            flat[base + 14] = d.substrateResponse.coerceIn(0f, 1f)
        }
        return flat
    }
}
