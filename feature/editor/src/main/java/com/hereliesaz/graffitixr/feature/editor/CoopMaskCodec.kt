package com.hereliesaz.graffitixr.feature.editor

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** An adjustment mask's bytes on the co-op wire (`Op.AdjustmentMaskData`): plain deflate. */
internal object CoopMaskCodec {

    fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / RATIO_GUESS + CHUNK)
            val buf = ByteArray(CHUNK)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * A peer's [width] x [height] mask, or null when either side is out of range, the product would
     * exceed [MAX_MASK_BYTES], or the bytes don't inflate to exactly that size. Both numbers come off
     * the wire, so they are bounded before anything is allocated (an Int product overflows, and an
     * honest-looking 40000 x 40000 would ask for 1.6 GB).
     */
    fun inflateMask(deflated: ByteArray, width: Int, height: Int): ByteArray? {
        val size = width.toLong() * height
        val inRange = width in 1..MAX_MASK_SIDE && height in 1..MAX_MASK_SIDE &&
            size <= MAX_MASK_BYTES && deflated.size <= MAX_MASK_BYTES
        return if (inRange) inflate(deflated, size.toInt()) else null
    }

    /** The mask, or null when [deflated] is corrupt or doesn't hold exactly [size] bytes. */
    fun inflate(deflated: ByteArray, size: Int): ByteArray? {
        if (size <= 0 || size > MAX_MASK_BYTES) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(deflated)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inflater.finished()) {
                val read = inflater.inflate(out, n, size - n)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += read
            }
            out.takeIf { n == size && inflater.finished() }
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /** Longest side a peer's mask may claim: past the largest canvas this app makes. */
    const val MAX_MASK_SIDE = 8192
    /** Most bytes a peer's mask may inflate to (one byte per pixel): 8192 x 8192. */
    const val MAX_MASK_BYTES = MAX_MASK_SIDE * MAX_MASK_SIDE

    private const val CHUNK = 8192
    private const val RATIO_GUESS = 16
}
