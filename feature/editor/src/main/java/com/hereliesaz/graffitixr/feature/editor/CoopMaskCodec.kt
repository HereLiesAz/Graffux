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

    /** The mask, or null when [deflated] is corrupt or doesn't hold exactly [size] bytes. */
    fun inflate(deflated: ByteArray, size: Int): ByteArray? {
        if (size <= 0) return null
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

    private const val CHUNK = 8192
    private const val RATIO_GUESS = 16
}
