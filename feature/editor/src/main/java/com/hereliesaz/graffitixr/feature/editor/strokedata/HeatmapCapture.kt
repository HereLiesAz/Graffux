package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs

/** What [StrokeDataRecorder] needs from a heatmap capture: frames for a window, and a status. */
interface HeatmapSource {
    /** Frames whose time falls in [fromNs, toNs] (uptime ns, the MotionEvent clock), capped. */
    fun slice(fromNs: Long, toNs: Long): HeatmapSlice?
    fun status(): HeatmapStatus
}

/**
 * Where the capture stands. [state]: `off`, `starting`, `unavailable` (no su, no helper binary),
 * `denied` (su refused or exited before the helper spoke), `v4l2` / `sec_delta` (streaming),
 * `error` (the helper reported one), `stopped`.
 */
data class HeatmapStatus(
    val state: String,
    val detail: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frames: Long = 0,
    /** Uptime − helper CLOCK_MONOTONIC at HELLO (pipe latency included); see [HeatmapCapture]. */
    val clockCheckNs: Long? = null,
) {
    val streaming: Boolean get() = state == HeatmapRecordReader.SOURCE_V4L2 || state == HeatmapRecordReader.SOURCE_SEC
    val settled: Boolean get() = state != STARTING

    fun toJson(): JSONObject = JSONObject()
        .put("state", state)
        .put("detail", detail)
        .put("w", width)
        .put("h", height)
        .put("frames", frames)
        .put("clockCheckNs", clockCheckNs ?: JSONObject.NULL)

    /** One line for Settings. */
    fun describe(): String = when {
        streaming -> "Root granted: $state, ${width}×$height grid. $detail".trim()
        state == UNAVAILABLE -> "Unavailable: $detail"
        state == DENIED -> "Root denied or su failed: $detail"
        state == ERROR -> "Helper error: $detail"
        else -> "$state $detail".trim()
    }

    companion object {
        const val OFF = "off"
        const val STARTING = "starting"
        const val UNAVAILABLE = "unavailable"
        const val DENIED = "denied"
        const val ERROR = "error"
        const val STOPPED = "stopped"
    }
}

/** Starts the helper; returns its process. Replaceable for tests. */
fun interface HeatmapLauncher {
    @Throws(IOException::class)
    fun launch(): Process
}

/**
 * Raw touch heatmap for stroke-data recording, root only (Settings → Raw touch heatmap). Runs the
 * native helper (libgraffux_heatmap.so in nativeLibraryDir) through `su`, reads its binary records
 * on a background thread and keeps the frames in a [HeatmapRing].
 *
 * Clocks: the helper stamps frames with CLOCK_MONOTONIC. On Android `SystemClock.uptimeMillis()` and
 * MotionEvent times ARE CLOCK_MONOTONIC, so no conversion is needed in principle. It is still
 * checked: at HELLO the app's uptime minus the helper's stamp (pipe latency, well under a
 * millisecond normally) is kept as [HeatmapStatus.clockCheckNs]; only if it exceeds
 * [CLOCK_MISMATCH_NS] -- a device where the clocks really differ -- is it applied as an offset.
 * A frame's time is the driver's buffer timestamp when that is CLOCK_MONOTONIC (closest to the
 * scan), else the helper's read time; the read time is kept as the arrival.
 */
class HeatmapCapture(
    private val launcher: HeatmapLauncher,
    private val nowUptimeNs: () -> Long = { SystemClock.uptimeMillis() * NS_PER_MS },
    private val onStatus: (HeatmapStatus) -> Unit = {},
) : HeatmapSource {
    @Volatile private var status = HeatmapStatus(HeatmapStatus.OFF)
    @Volatile private var ring: HeatmapRing? = null
    @Volatile private var offsetNs = 0L
    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false
    private var reader: Thread? = null
    private val lock = Object()
    private var frames = 0L
    private var lastInfo = ""

    override fun status(): HeatmapStatus = status

    private fun setStatus(s: HeatmapStatus) {
        synchronized(lock) {
            status = s
            lock.notifyAll()
        }
        onStatus(s)
    }

    /** Launches the helper (the Magisk prompt appears on first use). Returns at once. */
    fun start() {
        if (process != null) return
        stopRequested = false
        setStatus(HeatmapStatus(HeatmapStatus.STARTING))
        val p = try {
            launcher.launch()
        } catch (e: IOException) {
            setStatus(HeatmapStatus(HeatmapStatus.UNAVAILABLE, "cannot run su or the helper: ${e.message}"))
            return
        }
        process = p
        val stderr = StringBuilder()
        val drainStderr = Runnable {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
            }
        }
        val stderrThread = Thread(drainStderr, "heatmap-stderr").apply { isDaemon = true; start() }
        reader = Thread({
            consume(p.inputStream)
            val code = runCatching { p.waitFor() }.getOrNull()
            // su's refusal text arrives on stderr; let the drain finish before it is reported, or
            // the status can race ahead of it and say only "exit 1".
            runCatching { stderrThread.join(STDERR_JOIN_MS) }
            if (stopRequested) {
                setStatus(status.copy(state = HeatmapStatus.STOPPED, frames = frames))
            } else if (status.state == HeatmapStatus.STARTING) {
                val err = synchronized(stderr) { stderr.toString().trim() }.take(MAX_DETAIL)
                // Magisk's su exits 1 ("Permission denied") when the user declines.
                val why = listOf("exit $code", err, lastInfo).filter { it.isNotEmpty() }.joinToString("; ")
                setStatus(HeatmapStatus(HeatmapStatus.DENIED, why))
            } else if (status.streaming) {
                setStatus(status.copy(state = HeatmapStatus.STOPPED, frames = frames))
            }
        }, "heatmap-reader").apply { isDaemon = true; start() }
    }

    /** Stops the helper: closing its stdin makes it STREAMOFF and exit; destroy() backs that up. */
    fun stop() {
        val p = process ?: return
        process = null
        stopRequested = true
        runCatching { p.outputStream.close() }
        p.destroy()
    }

    /** Blocks until the status is no longer `starting` or [timeoutMs] passes. Not on the main thread. */
    fun awaitSettled(timeoutMs: Long): HeatmapStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!status.settled) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
        return status
    }

    /** Reads records until the stream ends. Runs on the reader thread; public to tests. */
    internal fun consume(input: InputStream) {
        val records = HeatmapRecordReader(input)
        try {
            while (true) {
                when (val r = records.next() ?: break) {
                    is HeatmapRecord.Hello -> onHello(r)
                    is HeatmapRecord.Frame -> onFrame(r)
                    is HeatmapRecord.Info -> lastInfo = r.text.take(MAX_DETAIL)
                    is HeatmapRecord.Error -> setStatus(
                        HeatmapStatus(HeatmapStatus.ERROR, r.text.take(MAX_DETAIL), frames = frames),
                    )
                }
            }
        } catch (e: IOException) {
            if (status.streaming || status.state == HeatmapStatus.STARTING) {
                setStatus(HeatmapStatus(HeatmapStatus.ERROR, "stream: ${e.message}", frames = frames))
            }
        }
    }

    private fun onHello(h: HeatmapRecord.Hello) {
        val check = nowUptimeNs() - h.monoNs
        offsetNs = alignmentOffset(check)
        val g = h.grid
        ring = HeatmapRing(g, HeatmapRing.capacityFor(g.width, g.height))
        val detail = listOf(h.detail, lastInfo).filter { it.isNotEmpty() }.joinToString("; ")
        setStatus(HeatmapStatus(g.source, detail, g.width, g.height, 0, check))
    }

    private fun onFrame(f: HeatmapRecord.Frame) {
        val r = ring ?: return
        if (f.grid.width != r.grid.width || f.grid.height != r.grid.height) return
        val time = if (f.bufferMonotonic && f.bufferNs > 0) f.bufferNs else f.monoNs
        r.add(time + offsetNs, f.monoNs + offsetNs, f.cells)
        frames++
        if (frames % STATUS_EVERY_FRAMES == 0L) status = status.copy(frames = frames)
    }

    override fun slice(fromNs: Long, toNs: Long): HeatmapSlice? {
        val r = ring ?: return null
        val maxFrames = minOf(MAX_FRAMES_PER_STROKE, MAX_BYTES_PER_STROKE / maxOf(r.grid.cells * Short.SIZE_BYTES, 1))
        return r.slice(fromNs, toNs, maxOf(maxFrames, 1))
    }

    companion object {
        const val HELPER_NAME = "libgraffux_heatmap.so"
        const val NS_PER_MS = 1_000_000L
        /** Beyond this, uptime and the helper's CLOCK_MONOTONIC are treated as different clocks. */
        const val CLOCK_MISMATCH_NS = 50 * NS_PER_MS
        /** Per-stroke cap: frames, and raw bytes before base64 (whichever is smaller). */
        const val MAX_FRAMES_PER_STROKE = 360
        const val MAX_BYTES_PER_STROKE = 384 * 1024
        private const val STATUS_EVERY_FRAMES = 30L
        private const val MAX_DETAIL = 300
        private const val STDERR_JOIN_MS = 2_000L

        /** The offset to add to helper times: 0 unless the clocks demonstrably differ. */
        fun alignmentOffset(checkNs: Long): Long = if (abs(checkNs) > CLOCK_MISMATCH_NS) checkNs else 0L

        /** The helper run through `su`; [HeatmapStatus.UNAVAILABLE] if it was not packaged. */
        fun forContext(context: Context, onStatus: (HeatmapStatus) -> Unit = {}): HeatmapCapture {
            val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME)
            return HeatmapCapture(
                launcher = {
                    if (!helper.exists()) throw IOException("helper not installed at $helper")
                    ProcessBuilder("su", "-c", helper.absolutePath).start()
                },
                onStatus = onStatus,
            )
        }
    }
}
