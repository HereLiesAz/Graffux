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
     * @param range the frame indices to export, in Animation Assist's own frame numbering — Krita's
     *   playback range, so a subrange can be exported without touching the layer stack. Ping-pong
     *   bounces within this range rather than the full frame set.
     * @param holdCountAt Krita's hold frame: a per-frame multiple of [frameDurationMs] to hold that
     *   frame for, e.g. 3 to hold three ticks instead of one. Defaults to no hold (every frame 1x).
     * @param frameAt produces the frame at an index, or null to skip it. Ownership transfers to the
     *   encoder, which recycles each frame as the next arrives — callers must not reuse the bitmap.
     * @return the number of frames actually written (0 if nothing could be encoded).
     */
    fun write(
        file: File,
        range: IntRange,
        frameDurationMs: Int,
        loopMode: AnimationLoopMode,
        holdCountAt: (Int) -> Int = { 1 },
        frameAt: (Int) -> Bitmap?,
    ): Int {
        if (range.isEmpty()) return 0
        val indices = range.toList()
        // Ping-pong plays forward then back without repeating the two endpoints, which is what makes
        // the bounce read as continuous rather than stuttering on the ends.
        val order = when (loopMode) {
            AnimationLoopMode.PING_PONG -> indices + indices.reversed().drop(1).dropLast(1)
            else -> indices
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
                // setDelay changes it "for subsequent frames" (see AnimatedGifEncoder), so calling it
                // right before each addFrame gives this one frame its own hold-adjusted duration.
                gif.setDelay(frameDurationMs * holdCountAt(index).coerceAtLeast(1))
                if (gif.addFrame(frame)) written++
            }
            gif.finish()
        }
        return written
    }
}
