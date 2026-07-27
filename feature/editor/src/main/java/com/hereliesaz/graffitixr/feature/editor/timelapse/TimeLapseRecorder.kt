package com.hereliesaz.graffitixr.feature.editor.timelapse

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.AnimatedGifEncoder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams downsampled canvas snapshots straight to a GIF file via [AnimatedGifEncoder] as strokes
 * commit, so memory use stays flat regardless of session length — no frame is ever held longer
 * than the single [addFrame][AnimatedGifEncoder.addFrame] call that consumes and recycles it.
 */
class TimeLapseRecorder {

    private var encoder: AnimatedGifEncoder? = null
    private var outputStream: FileOutputStream? = null
    private var lastCaptureAtMs = 0L

    var outputFile: File? = null
        private set
    var frameCount: Int = 0
        private set

    val isRecording: Boolean get() = encoder != null

    /** Opens [file] and writes the GIF header. Returns false if the file couldn't be opened. */
    fun start(file: File): Boolean {
        if (isRecording) return false
        return try {
            val stream = FileOutputStream(file)
            val gif = AnimatedGifEncoder().apply {
                setRepeat(0)
                setFrameRate(FRAME_RATE_FPS)
                setQuality(GIF_QUALITY)
            }
            if (!gif.start(stream)) {
                stream.close()
                return false
            }
            encoder = gif
            outputStream = stream
            outputFile = file
            frameCount = 0
            lastCaptureAtMs = 0L
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Adds [frame] as the next GIF frame, throttled to at most one every [MIN_INTERVAL_MS] and
     * capped at [MAX_FRAMES] total so a long session can't grow the file without bound. Returns
     * true if [frame] was consumed (and will be recycled by the encoder) — the caller must recycle
     * it themselves when this returns false.
     */
    fun captureFrame(frame: Bitmap, nowMs: Long): Boolean {
        val gif = encoder ?: return false
        if (frameCount >= MAX_FRAMES) return false
        if (frameCount > 0 && nowMs - lastCaptureAtMs < MIN_INTERVAL_MS) return false
        gif.addFrame(frame)
        frameCount++
        lastCaptureAtMs = nowMs
        return true
    }

    /** Finalizes the GIF and returns the written file, or null if nothing was ever captured. */
    fun finish(): File? {
        val gif = encoder ?: return null
        val file = outputFile
        val count = frameCount
        gif.finish()
        try {
            outputStream?.close()
        } catch (e: IOException) {
            // Best-effort close; the file is already flushed by finish() above.
        }
        encoder = null
        outputStream = null
        outputFile = null
        frameCount = 0
        return if (count > 0) file else null
    }

    companion object {
        private const val FRAME_RATE_FPS = 8f
        private const val GIF_QUALITY = 12
        private const val MIN_INTERVAL_MS = 400L
        private const val MAX_FRAMES = 600
    }
}
