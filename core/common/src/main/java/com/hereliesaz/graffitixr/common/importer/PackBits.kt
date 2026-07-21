// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/importer/PackBits.kt
package com.hereliesaz.graffitixr.common.importer

/**
 * PackBits run-length decoding — the byte-wise RLE scheme Photoshop uses for RLE-compressed channel
 * scanlines (compression flag 1). Pure and allocation-light so it can be unit-tested and reused by
 * both the PSD reader and any other PackBits-based path.
 *
 * The control byte `n` (read as a signed byte) drives each run:
 *  - `0..127`  → copy the next `n + 1` bytes literally.
 *  - `-1..-127` → repeat the next single byte `1 - n` times (2..128 copies).
 *  - `-128`     → skip (no output).
 */
object PackBits {

    /**
     * Decodes exactly [dstCount] bytes into [dst] starting at [dstOffset], reading control/data
     * bytes from [src] starting at [srcOffset]. Returns the number of **source** bytes consumed, so
     * callers decoding consecutive scanlines can advance their read cursor.
     *
     * Stops as soon as [dstCount] bytes are produced (the standard scanline-driven decode); a run
     * that would overshoot [dstCount] is truncated rather than allowed to write past the end.
     * Throws [IndexOutOfBoundsException] if [src] is exhausted before [dstCount] bytes are produced,
     * which for a well-formed stream cannot happen.
     */
    fun decode(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, dstCount: Int): Int {
        var s = srcOffset
        var d = dstOffset
        val dEnd = dstOffset + dstCount
        while (d < dEnd) {
            val n = src[s++].toInt() // signed control byte
            when {
                n >= 0 -> {
                    // Literal run of (n + 1) bytes.
                    var count = n + 1
                    while (count-- > 0 && d < dEnd) dst[d++] = src[s++]
                }
                n != -128 -> {
                    // Replicate the next byte (1 - n) times.
                    val value = src[s++]
                    var count = 1 - n
                    while (count-- > 0 && d < dEnd) dst[d++] = value
                }
                // n == -128: no-op.
            }
        }
        return s - srcOffset
    }
}
