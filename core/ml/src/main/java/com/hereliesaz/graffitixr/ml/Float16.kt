@file:Suppress("MagicNumber", "ReturnCount")

package com.hereliesaz.graffitixr.ml

/** IEEE 754 binary16 conversion for feeding a graph whose input tensor is float16. Round-to-nearest-even. */
internal object Float16 {
    private const val SUBNORMAL_UNIT = 5.9604645E-8f // 2^-24

    fun toHalfBits(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or if (mant != 0) 0x200 else 0).toShort() // Inf / NaN
        val e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort() // overflow -> Inf
        if (e <= 0) {
            if (e < -10) return sign.toShort() // underflow -> signed zero
            val m = mant or 0x800000
            val shift = 14 - e
            var half = m ushr shift
            val rem = m and ((1 shl shift) - 1)
            val mid = 1 shl (shift - 1)
            if (rem > mid || (rem == mid && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        var half = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++ // carry may roll into exponent, correctly
        return (sign or half).toShort()
    }

    fun toFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> java.lang.Float.floatToRawIntBits(mant * SUBNORMAL_UNIT) or sign
            exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun toHalfArray(values: FloatArray): ShortArray = ShortArray(values.size) { toHalfBits(values[it]) }
}
