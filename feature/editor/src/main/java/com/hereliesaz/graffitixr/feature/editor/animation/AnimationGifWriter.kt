package com.hereliesaz.graffitixr.feature.editor.animation

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.AnimatedGifEncoder
import com.hereliesaz.graffitixr.common.model.AnimationLoopMode
import java.io.File
import java.io.FileOutputStream

/**
 * Writes an animation to a GIF one frame at a time, pulling each from [frameAt] only when it's
 * about to be encoded so at most a single full-size frame bitmap is ever resident. Frames must all
 * be the same dimensions — [AnimatedGifEncoder] sizes itself from the first one and would otherwise
 * silently rescale (and leak) the rest.
 */
object AnimationGifWriter {

    /**
     * @param frameAt produces the frame at an index, or null to skip it. Ownership transfers to the
     *   encoder, which recycles each frame as the next arrives — callers must not reuse the bitmap.
     * @return the number of frames actually written (0 if nothing could be encoded).
     */
    fun write(
        file: File,
        frameCount: Int,
        frameDurationMs: Int,
        loopMode: AnimationLoopMode,
        frameAt: (Int) -> Bitmap?,
    ): Int {
        if (frameCount <= 0) return 0
        // Ping-pong plays forward then back without repeating the two endpoints, which is what makes
        // the bounce read as continuous rather than stuttering on the ends.
        val order = when (loopMode) {
            AnimationLoopMode.PING_PONG ->
                (0 until frameCount) + ((frameCount - 2) downTo 1)
            else -> (0 until frameCount).toList()
        }
        var written = 0
        FileOutputStream(file).use { stream ->
            val gif = AnimatedGifEncoder().apply {
                setDelay(frameDurationMs)
                // setRepeat is only honoured for iter >= 0, and the -1 default writes no Netscape
                // loop extension at all — which is exactly "play once".
                if (loopMode != AnimationLoopMode.ONCE) setRepeat(0)
            }
            if (!gif.start(stream)) return 0
            for (index in order) {
                val frame = frameAt(index) ?: continue
                if (gif.addFrame(frame)) written++
            }
            gif.finish()
        }
        return written
    }
}
