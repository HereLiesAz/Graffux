package com.hereliesaz.graffitixr.feature.editor.strokedata

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A heatmap grid: where it comes from (`v4l2` / `sec_delta`) and its columns × rows. */
data class HeatmapGrid(val source: String, val width: Int, val height: Int) {
    val cells: Int get() = width * height
}

/**
 * One record from the heatmap helper's stdout (core/nativebridge/src/main/cpp/heatmap/
 * heatmap_helper.c, which has the byte layout). All times are the helper's CLOCK_MONOTONIC ns.
 */
internal sealed class HeatmapRecord {
    /** The helper chose [grid] and will stream its int16 frames. */
    data class Hello(val grid: HeatmapGrid, val monoNs: Long, val detail: String) : HeatmapRecord()

    /**
     * One frame. [monoNs] is when the helper read it; [bufferNs] the driver's own timestamp (0 when
     * it has none), on CLOCK_MONOTONIC when [bufferMonotonic]. [cells] is row-major, width wide.
     */
    class Frame(
        val grid: HeatmapGrid,
        val monoNs: Long,
        val bufferNs: Long,
        val bufferMonotonic: Boolean,
        val sequence: Long,
        val cells: ShortArray,
    ) : HeatmapRecord()

    /** Fatal: the helper exits after it. */
    data class Error(val monoNs: Long, val text: String) : HeatmapRecord()

    /** Informational, e.g. why V4L2 was skipped before falling back. */
    data class Info(val monoNs: Long, val text: String) : HeatmapRecord()
}

/** Reads [HeatmapRecord]s from the helper's stdout. Not thread-safe; one reader thread. */
internal class HeatmapRecordReader(private val input: InputStream) {
    private val header = ByteArray(HEADER_BYTES)

    /** The next record; null at a clean end of stream. Throws [IOException] on a corrupt stream. */
    fun next(): HeatmapRecord? {
        if (!readFully(header, allowEof = true)) return null
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) corrupt("bad magic")
        val type = h.get(OFF_TYPE).toInt() and BYTE_MASK
        val version = h.get(OFF_VERSION).toInt() and BYTE_MASK
        if (version != VERSION) corrupt("unsupported record version $version")
        val length = h.getInt(OFF_LENGTH).toLong() and UINT_MASK
        if (length > MAX_PAYLOAD) corrupt("payload length $length")
        val bytes = ByteArray(length.toInt())
        readFully(bytes, allowEof = false)
        val p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (type) {
            TYPE_HELLO -> {
                need(p, HELLO_FIXED)
                HeatmapRecord.Hello(grid(p), p.getLong(OFF_HELLO_MONO), text(bytes, HELLO_FIXED))
            }
            TYPE_FRAME -> frame(p)
            TYPE_ERROR -> { need(p, TEXT_FIXED); HeatmapRecord.Error(p.getLong(0), text(bytes, TEXT_FIXED)) }
            TYPE_INFO -> { need(p, TEXT_FIXED); HeatmapRecord.Info(p.getLong(0), text(bytes, TEXT_FIXED)) }
            else -> next() // Unknown record types are skipped: newer helpers may add some.
        }
    }

    private fun grid(p: ByteBuffer) = HeatmapGrid(
        sourceName(p.get(OFF_SOURCE)),
        p.getShort(OFF_WIDTH).toInt() and USHORT_MASK,
        p.getShort(OFF_HEIGHT).toInt() and USHORT_MASK,
    )

    private fun frame(p: ByteBuffer): HeatmapRecord.Frame {
        need(p, FRAME_FIXED)
        if ((p.get(OFF_DTYPE).toInt() and BYTE_MASK) != DTYPE_INT16) corrupt("unsupported dtype ${p.get(OFF_DTYPE)}")
        val grid = grid(p)
        val cells = ShortArray(grid.cells)
        need(p, FRAME_FIXED + cells.size * Short.SIZE_BYTES)
        p.position(FRAME_FIXED)
        p.asShortBuffer().get(cells)
        return HeatmapRecord.Frame(
            grid = grid,
            monoNs = p.getLong(OFF_FRAME_MONO),
            bufferNs = p.getLong(OFF_FRAME_BUFFER),
            bufferMonotonic = (p.getShort(OFF_FLAGS).toInt() and FLAG_BUFFER_MONOTONIC) != 0,
            sequence = p.getInt(OFF_SEQUENCE).toLong() and UINT_MASK,
            cells = cells,
        )
    }

    private fun readFully(buf: ByteArray, allowEof: Boolean): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0 && allowEof) return false
                throw EOFException("truncated record")
            }
            off += n
        }
        return true
    }

    private fun need(p: ByteBuffer, n: Int) {
        if (p.capacity() < n) corrupt("short payload ${p.capacity()} < $n")
    }

    private fun corrupt(message: String): Nothing = throw IOException(message)

    companion object {
        const val VERSION = 1
        const val TYPE_HELLO = 1
        const val TYPE_FRAME = 2
        const val TYPE_ERROR = 3
        const val TYPE_INFO = 4
        const val DTYPE_INT16 = 1
        const val SOURCE_V4L2 = "v4l2"
        const val SOURCE_SEC = "sec_delta"
        private val MAGIC = "GHM1".toByteArray(Charsets.US_ASCII)

        // Record header: magic[4] | type u8 | version u8 | reserved u16 | payload length u32.
        private const val HEADER_BYTES = 12
        private const val OFF_TYPE = 4
        private const val OFF_VERSION = 5
        private const val OFF_LENGTH = 8
        // HELLO and FRAME payloads start: source u8 | dtype u8 | width u16 | height u16 | flags u16.
        private const val OFF_SOURCE = 0
        private const val OFF_DTYPE = 1
        private const val OFF_WIDTH = 2
        private const val OFF_HEIGHT = 4
        private const val OFF_FLAGS = 6
        private const val OFF_HELLO_MONO = 8
        private const val HELLO_FIXED = 16
        private const val OFF_FRAME_MONO = 8
        private const val OFF_FRAME_BUFFER = 16
        private const val OFF_SEQUENCE = 24
        private const val FRAME_FIXED = 28
        // ERROR / INFO: monotonic ns i64 | utf8 text.
        private const val TEXT_FIXED = 8
        private const val FLAG_BUFFER_MONOTONIC = 1
        private const val MAX_PAYLOAD = 1L shl 20
        private const val BYTE_MASK = 0xff
        private const val USHORT_MASK = 0xffff
        private const val UINT_MASK = 0xffff_ffffL
        private const val SOURCE_ID_V4L2 = 1
        private const val SOURCE_ID_SEC = 2

        fun sourceName(b: Byte): String = when (b.toInt()) {
            SOURCE_ID_V4L2 -> SOURCE_V4L2
            SOURCE_ID_SEC -> SOURCE_SEC
            else -> "unknown"
        }

        private fun text(b: ByteArray, o: Int) = String(b, o, b.size - o, Charsets.UTF_8)
    }
}
