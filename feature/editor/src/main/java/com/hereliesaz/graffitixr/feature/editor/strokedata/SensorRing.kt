package com.hereliesaz.graffitixr.feature.editor.strokedata

import org.json.JSONArray
import org.json.JSONObject

/**
 * The most recent [capacity] events of one sensor: the event's own timestamp, the time it reached
 * the app (both elapsedRealtime ns), and its values. Written from the sensor thread, sliced from
 * the main thread.
 *
 * Arrival times are kept because an event's own timestamp can trail the moment it was delivered by
 * seconds (a real Pixel 5 session did exactly that for every accelerometer-hub sensor): a slice by
 * timestamp alone then comes back empty, and nothing in the file says why.
 */
internal class SensorRing(val name: String, private val capacity: Int) {
    private val times = LongArray(capacity)
    private val arrivals = LongArray(capacity)
    private val values = FloatArray(capacity * MAX_VALUES)
    private val lengths = IntArray(capacity)
    private var next = 0
    private var count = 0

    /** Timestamp and arrival time of the newest event, or 0 when none arrived yet. */
    var lastTimeNs = 0L
        private set
    var lastArrivalNs = 0L
        private set

    @Synchronized
    fun add(timeNs: Long, arrivalNs: Long, v: FloatArray) {
        val n = minOf(v.size, MAX_VALUES)
        times[next] = timeNs
        arrivals[next] = arrivalNs
        v.copyInto(values, next * MAX_VALUES, 0, n)
        lengths[next] = n
        next = (next + 1) % capacity
        count = minOf(count + 1, capacity)
        lastTimeNs = timeNs
        lastArrivalNs = arrivalNs
    }

    /**
     * Events whose timestamp, or whose arrival, falls in [fromNs, toNs]; null when there are none.
     * `{t: [timestamp], a: [arrival], v: [[values]]}`.
     */
    @Synchronized
    fun slice(fromNs: Long, toNs: Long): JSONObject? {
        val t = JSONArray()
        val a = JSONArray()
        val v = JSONArray()
        for (i in 0 until count) {
            val idx = (next - count + i + capacity) % capacity
            if (times[idx] in fromNs..toNs || arrivals[idx] in fromNs..toNs) {
                t.put(times[idx])
                a.put(arrivals[idx])
                val row = JSONArray()
                for (k in 0 until lengths[idx]) row.put(values[idx * MAX_VALUES + k].toDouble())
                v.put(row)
            }
        }
        return if (t.length() == 0) null else JSONObject().put("t", t).put("a", a).put("v", v)
    }

    /** This sensor's line in a stroke's `sensorStatus`. */
    fun status(registered: Boolean, flushed: Boolean, slice: JSONObject?): JSONObject = JSONObject()
        .put("registered", registered)
        .put("flushed", flushed)
        .put("events", slice?.getJSONArray("t")?.length() ?: 0)
        // How far the newest event's own timestamp trailed its arrival: large means the stream's
        // timestamps (or its delivery) run behind, not that the sensor is missing.
        .put("lagNs", if (lastArrivalNs == 0L) JSONObject.NULL else lastArrivalNs - lastTimeNs)

    private companion object {
        /** Rotation vectors carry 5 values (quaternion + accuracy); everything else 3. */
        const val MAX_VALUES = 5
    }
}
