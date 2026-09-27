package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The app half of the heatmap helper: its binary records, clock alignment, the per-stroke slice and
 * cap, su outcomes, and the recorder attaching frames to strokes. Helper output is synthesized with
 * [HelperOutput], which mirrors heatmap_helper.c's layout; [REAL_HELPER_ERROR_OUTPUT] is the real
 * helper's bytes (built on the host) so the two cannot silently disagree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeatmapCaptureTest {

    /** Writes records exactly as heatmap_helper.c does. */
    private class HelperOutput {
        val bytes = ByteArrayOutputStream()

        private fun record(type: Int, payload: ByteArray) {
            val h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            h.put('G'.code.toByte()).put('H'.code.toByte()).put('M'.code.toByte()).put('1'.code.toByte())
            h.put(type.toByte()).put(1).putShort(0).putInt(payload.size)
            bytes.write(h.array())
            bytes.write(payload)
        }

        fun hello(source: Int, w: Int, h: Int, monoNs: Long, detail: String = "test") = apply {
            val d = detail.toByteArray()
            val p = ByteBuffer.allocate(16 + d.size).order(ByteOrder.LITTLE_ENDIAN)
            p.put(source.toByte()).put(1).putShort(w.toShort()).putShort(h.toShort()).putShort(0).putLong(monoNs).put(d)
            record(1, p.array())
        }

        /** The driver's buffer timestamp, whether it is CLOCK_MONOTONIC, and the frame sequence. */
        data class Buf(val ns: Long = 0, val mono: Boolean = false, val seq: Int = 0)

        fun frame(w: Int, h: Int, monoNs: Long, buf: Buf = Buf(), fill: (Int) -> Int) = apply {
            val p = ByteBuffer.allocate(28 + w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
            p.put(2).put(1).putShort(w.toShort()).putShort(h.toShort()).putShort(if (buf.mono) 1 else 0)
            p.putLong(monoNs).putLong(buf.ns).putInt(buf.seq)
            for (i in 0 until w * h) p.putShort(fill(i).toShort())
            record(2, p.array())
        }

        fun error(text: String) = apply {
            val t = text.toByteArray()
            record(3, ByteBuffer.allocate(8 + t.size).order(ByteOrder.LITTLE_ENDIAN).putLong(0).put(t).array())
        }

        fun raw(type: Int, payload: ByteArray) = apply { record(type, payload) }

        fun stream(): InputStream = ByteArrayInputStream(bytes.toByteArray())
    }

    private class FakeProcess(
        private val out: InputStream,
        private val err: String = "",
        private val code: Int = 0,
    ) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = ByteArrayInputStream(err.toByteArray())
        override fun waitFor(): Int = code
        override fun exitValue(): Int = code
        override fun destroy() = Unit
    }

    private val base = 5_000_000_000L
    private fun capture(now: Long = base) = HeatmapCapture(launcher = { error("unused") }, nowUptimeNs = { now })

    @Test
    fun `parser reads every record type and skips unknown ones`() {
        val out = HelperOutput()
            .hello(source = 2, w = 3, h = 2, monoNs = 42, detail = "sec_delta x_num=3 y_num=2")
            .raw(type = 9, payload = byteArrayOf(1, 2, 3))
            .frame(3, 2, monoNs = 100, buf = HelperOutput.Buf(ns = 90, mono = true, seq = 7)) { it - 3 }
            .error("DQBUF: EIO")
        val r = HeatmapRecordReader(out.stream())
        val hello = r.next() as HeatmapRecord.Hello
        assertEquals(HeatmapGrid("sec_delta", 3, 2), hello.grid)
        assertEquals(42L, hello.monoNs)
        assertEquals("sec_delta x_num=3 y_num=2", hello.detail)
        val f = r.next() as HeatmapRecord.Frame
        assertEquals(100L, f.monoNs)
        assertEquals(90L, f.bufferNs)
        assertTrue(f.bufferMonotonic)
        assertEquals(7L, f.sequence)
        assertArrayEquals(shortArrayOf(-3, -2, -1, 0, 1, 2), f.cells)
        assertEquals("DQBUF: EIO", (r.next() as HeatmapRecord.Error).text)
        assertNull(r.next())
    }

    @Test
    fun `parser reads the real helper's bytes`() {
        val bytes = REAL_HELPER_ERROR_OUTPUT.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val r = HeatmapRecordReader(ByteArrayInputStream(bytes))
        val info = r.next() as HeatmapRecord.Info
        assertTrue(info.text, info.text.startsWith("v4l2 unavailable: open /nonexistent: ENOENT"))
        val err = r.next() as HeatmapRecord.Error
        assertTrue(err.text, err.text.contains("sec_delta: open /sys/devices/virtual/sec/tsp/cmd: ENOENT"))
        assertTrue(err.monoNs > info.monoNs)
        assertNull(r.next())
    }

    @Test(expected = EOFException::class)
    fun `a truncated record is an error, not a clean end`() {
        val full = HelperOutput().frame(2, 2, monoNs = 1) { it }.bytes.toByteArray()
        HeatmapRecordReader(ByteArrayInputStream(full.copyOf(full.size - 3))).next()
    }

    @Test(expected = IOException::class)
    fun `garbage is rejected`() {
        HeatmapRecordReader(ByteArrayInputStream(ByteArray(20) { 7 })).next()
    }

    @Test
    fun `frames are sliced by time with the stroke's window`() {
        val c = capture()
        val out = HelperOutput().hello(1, 2, 2, monoNs = base)
        for (k in 0 until 10) out.frame(2, 2, monoNs = base + k * 10_000_000L) { k }
        c.consume(out.stream())
        assertEquals("v4l2", c.status().state)
        assertEquals(0L, c.status().clockCheckNs)
        val s = c.slice(base + 20_000_000L, base + 50_000_000L)
        assertNotNull(s)
        s!!
        assertEquals(4, s.frames)
        assertEquals(base + 20_000_000L, s.t[0])
        assertEquals(2.toShort(), s.cells[0])
        assertEquals(5.toShort(), s.cells[s.cells.size - 1])
        assertFalse(s.truncated)
        assertNull(c.slice(base + 200_000_000L, base + 300_000_000L))
    }

    @Test
    fun `a monotonic driver timestamp is the frame time and the read time is the arrival`() {
        val c = capture()
        val monoBuf = HelperOutput.Buf(ns = base + 2_000_000L, mono = true)
        val read = base + 9_000_000L
        c.consume(HelperOutput().hello(1, 1, 1, base).frame(1, 1, monoNs = read, buf = monoBuf) { 1 }.stream())
        val s = c.slice(base, base + 5_000_000L)!!
        assertEquals(base + 2_000_000L, s.t[0])
        assertEquals(base + 9_000_000L, s.a[0])
        // A non-monotonic buffer stamp (e.g. realtime) is ignored.
        val d = capture()
        val realtimeBuf = HelperOutput.Buf(ns = 123, mono = false)
        d.consume(HelperOutput().hello(1, 1, 1, base).frame(1, 1, monoNs = read, buf = realtimeBuf) { 1 }.stream())
        assertEquals(base + 9_000_000L, d.slice(base, base + 10_000_000L)!!.t[0])
    }

    @Test
    fun `clocks are aligned only when they demonstrably differ`() {
        assertEquals(0L, HeatmapCapture.alignmentOffset(300_000L))
        assertEquals(0L, HeatmapCapture.alignmentOffset(-HeatmapCapture.CLOCK_MISMATCH_NS))
        assertEquals(10_000_000_000L, HeatmapCapture.alignmentOffset(10_000_000_000L))
        // Helper clock 10 s behind uptime: its frames are shifted onto the uptime clock.
        val c = capture(now = base)
        val behind = base - 10_000_000_000L
        c.consume(HelperOutput().hello(1, 1, 1, monoNs = behind).frame(1, 1, monoNs = behind) { 3 }.stream())
        assertEquals(10_000_000_000L, c.status().clockCheckNs)
        assertEquals(base, c.slice(base - 1, base + 1)!!.t[0])
    }

    @Test
    fun `a stroke's frames are capped, keeping the onset`() {
        val c = capture()
        val out = HelperOutput().hello(1, 4, 4, base)
        val n = HeatmapCapture.MAX_FRAMES_PER_STROKE + 50
        for (k in 0 until n) out.frame(4, 4, monoNs = base + k * 1_000_000L) { k }
        c.consume(out.stream())
        val s = c.slice(base, base + n * 1_000_000L)!!
        assertEquals(HeatmapCapture.MAX_FRAMES_PER_STROKE, s.frames)
        assertTrue(s.truncated)
        assertEquals(base, s.t[0])
        // Big grids are capped by bytes instead.
        val big = capture()
        val w = 100
        val h = 80
        val o2 = HelperOutput().hello(1, w, h, base)
        for (k in 0 until 60) o2.frame(w, h, monoNs = base + k * 1_000_000L) { 0 }
        big.consume(o2.stream())
        val expected = HeatmapCapture.MAX_BYTES_PER_STROKE / (w * h * 2)
        assertEquals(expected, big.slice(base, base + 100_000_000L)!!.frames)
    }

    @Test
    fun `slice json decodes back to the frames`() {
        val c = capture()
        c.consume(HelperOutput().hello(2, 3, 2, base).frame(3, 2, base) { if (it == 4) -300 else it * 100 }.stream())
        val j = c.slice(base, base)!!.toJson()
        assertEquals("sec_delta", j.getString("source"))
        assertEquals(3, j.getInt("w"))
        assertEquals(2, j.getInt("h"))
        assertEquals("int16le", j.getString("dtype"))
        val raw = Base64.getDecoder().decode(j.getString("frames"))
        val back = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(back)
        assertArrayEquals(shortArrayOf(0, 100, 200, 300, -300, 500), back)
    }

    @Test
    fun `a helper error becomes the status`() {
        val c = capture()
        c.consume(HelperOutput().error("v4l2: EBUSY; sec_delta: EACCES (permission denied or SELinux)").stream())
        assertEquals("error", c.status().state)
        assertTrue(c.status().detail.contains("SELinux"))
    }

    @Test
    fun `su refusing is reported as denied, a missing su as unavailable`() {
        val silent = ByteArrayInputStream(ByteArray(0))
        val denied = HeatmapCapture(launcher = { FakeProcess(silent, "Permission denied", 1) })
        denied.start()
        val s = denied.awaitSettled(5_000)
        assertEquals("denied", s.state)
        assertTrue(s.detail, s.detail.contains("exit 1") && s.detail.contains("Permission denied"))

        val missing = HeatmapCapture(launcher = { throw IOException("Cannot run program \"su\"") })
        missing.start()
        assertEquals("unavailable", missing.awaitSettled(1_000).state)
    }

    @Test
    fun `granted root streams through start`() {
        val out = HelperOutput().hello(2, 2, 2, SystemClock.uptimeMillis() * 1_000_000L).stream()
        val c = HeatmapCapture(launcher = { FakeProcess(out) })
        c.start()
        val s = c.awaitSettled(5_000)
        // The fake stream ends right after HELLO, so the reader may already have marked it stopped.
        assertTrue(s.state, s.state == "sec_delta" || s.state == "stopped")
        assertEquals(2, s.width)
    }

    @Test
    fun `the recorder attaches the stroke's frames and the capture status`() {
        val records = mutableListOf<JSONObject>()
        val windows = mutableListOf<Pair<Long, Long>>()
        val source = object : HeatmapSource {
            override fun slice(fromNs: Long, toNs: Long): HeatmapSlice {
                windows.add(fromNs to toNs)
                val grid = HeatmapGrid("v4l2", 1, 1)
                return HeatmapSlice(grid, longArrayOf(fromNs), longArrayOf(fromNs), shortArrayOf(9), false)
            }
            override fun status() = HeatmapStatus("v4l2", "test", 1, 1, 10)
        }
        val recorder = StrokeDataRecorder(
            sensorManager = null,
            context = { JSONObject() },
            sink = { records.add(it) },
            mainHandler = Handler(Looper.getMainLooper()),
            sensorThread = false,
        ).also { it.heatmap = source }
        val down = SystemClock.uptimeMillis()
        recorder.onMotionEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        recorder.onMotionEvent(MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, 5f, 1f, 0))
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(1, records.size)
        val (from, to) = windows.single()
        assertEquals(down * 1_000_000L - StrokeDataRecorder.HEATMAP_LEAD_NS, from)
        assertEquals((down + 40) * 1_000_000L + StrokeDataRecorder.HEATMAP_TAIL_NS, to)
        assertEquals("v4l2", records[0].getJSONObject("heatmap").getString("source"))
        assertEquals("v4l2", records[0].getJSONObject("heatmapStatus").getString("state"))
    }

    private companion object {
        /** `libgraffux_heatmap --device=/nonexistent </dev/null` on a host without sec sysfs. */
        const val REAL_HELPER_ERROR_OUTPUT =
            "47484d3104010000560000000c35dff7f205000076346c3220756e617661696c61626c653a206f70656e202f6e6f6e65" +
                "78697374656e743a20454e4f454e5420286e6f207375636820646576696365293b20747279696e67207365635f6465" +
                "6c746147484d310301000083000000b46adff7f205000076346c323a206f70656e202f6e6f6e6578697374656e743a" +
                "20454e4f454e5420286e6f207375636820646576696365293b207365635f64656c74613a206f70656e202f7379732f" +
                "646576696365732f7669727475616c2f7365632f7473702f636d643a20454e4f454e5420286e6f2073756368206465" +
                "7669636529"
    }
}
