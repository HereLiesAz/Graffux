package com.hereliesaz.graffitixr.feature.editor.strokedata

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The most recent heatmap frames of one grid size, indexed by time on the MotionEvent (uptime)
 * clock. Written by the helper's reader thread, sliced when a stroke completes.
 */
internal class HeatmapRing(val grid: HeatmapGrid, capacity: Int) {
    private val cellsPerFrame = grid.cells
    private val capacity = capacity.coerceAtLeast(1)
    private val times = LongArray(this.capacity)
    private val arrivals = LongArray(this.capacity)
    private val cells = ShortArray(this.capacity * cellsPerFrame)
    private var next = 0
    private var count = 0

    /** Adds a frame; [timeNs] and [arrivalNs] are already on the uptime clock. */
    @Synchronized
    fun add(timeNs: Long, arrivalNs: Long, frame: ShortArray) {
        if (frame.size != cellsPerFrame) return
        times[next] = timeNs
        arrivals[next] = arrivalNs
        frame.copyInto(cells, next * cellsPerFrame)
        next = (next + 1) % capacity
        count = minOf(count + 1, capacity)
    }

    @get:Synchronized
    val size: Int get() = count

    /**
     * Frames whose time falls in [fromNs, toNs], oldest first, at most [maxFrames]; null when none.
     * Keeps the EARLIEST frames when capped: the onset is what the model reads.
     */
    @Synchronized
    fun slice(fromNs: Long, toNs: Long, maxFrames: Int): HeatmapSlice? {
        val picked = ArrayList<Int>()
        var matched = 0
        for (i in 0 until count) {
            val idx = (next - count + i + capacity) % capacity
            if (times[idx] in fromNs..toNs) {
                matched++
                if (picked.size < maxFrames) picked.add(idx)
            }
        }
        if (picked.isEmpty()) return null
        val t = LongArray(picked.size)
        val a = LongArray(picked.size)
        val out = ShortArray(picked.size * cellsPerFrame)
        picked.forEachIndexed { k, idx ->
            t[k] = times[idx]
            a[k] = arrivals[idx]
            cells.copyInto(out, k * cellsPerFrame, idx * cellsPerFrame, (idx + 1) * cellsPerFrame)
        }
        return HeatmapSlice(grid, t, a, out, truncated = matched > picked.size)
    }

    companion object {
        /** Ring memory bound, whatever the grid size. */
        const val MAX_RING_BYTES = 4 shl 20
        const val MAX_RING_FRAMES = 2_048

        fun capacityFor(width: Int, height: Int): Int =
            (MAX_RING_BYTES / (maxOf(width * height, 1) * 2)).coerceIn(1, MAX_RING_FRAMES)
    }
}

/** A stroke's heatmap frames: `t` (frame time) and `a` (arrival), both uptime ns, and the cells. */
class HeatmapSlice(
    val grid: HeatmapGrid,
    val t: LongArray,
    val a: LongArray,
    val cells: ShortArray,
    val truncated: Boolean,
) {
    val frames: Int get() = t.size

    /** SCHEMA.md `heatmap`: frames are base64 of packed little-endian int16, row-major, frame after frame. */
    fun toJson(): JSONObject {
        val bytes = ByteBuffer.allocate(cells.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(cells)
        return JSONObject()
            .put("source", grid.source)
            .put("w", grid.width)
            .put("h", grid.height)
            .put("dtype", "int16le")
            .put("t", JSONArray(t.toList()))
            .put("a", JSONArray(a.toList()))
            .put("frames", Base64.getEncoder().encodeToString(bytes.array()))
            .put("truncated", truncated)
    }
}
