package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records strokes for training Graffux's own stroke-prediction model (tools/stroke-model). For every
 * stroke: each raw input sample with every axis the hardware reports -- position, pressure, contact
 * size and ellipse, orientation and tilt of the finger or stylus, tool type, stylus hover before
 * contact -- plus the phone's motion sensors around the stroke, on one clock. Schema:
 * tools/stroke-model/SCHEMA.md.
 *
 * MotionEvents come from the canvas (main thread); sensors arrive on their own callbacks. A finished
 * stroke is handed to [sink] as one JSON object once the post-stroke sensor tail has arrived.
 */
class StrokeDataRecorder(
    private val sensorManager: SensorManager?,
    /** Canvas/brush/device context stamped on each stroke (brush, zoom, display Hz, ...). */
    private val context: () -> JSONObject,
    private val sink: (JSONObject) -> Unit,
) : SensorEventListener {

    private val rings = SENSORS.associate { (type, name) -> type to SensorRing(name) }
    private var registered = false
    private var stroke: StrokeBuilder? = null
    private var hoverBuilder: StrokeBuilder? = null

    /** Starts the sensor streams; call while the canvas is visible. */
    fun start() {
        if (registered || sensorManager == null) return
        for ((type, _) in SENSORS) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, SENSOR_PERIOD_US)
            }
        }
        registered = true
    }

    fun stop() {
        if (registered) sensorManager?.unregisterListener(this)
        registered = false
        stroke = null
        hoverBuilder = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        rings[event.sensor.type]?.add(event.timestamp, event.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Every MotionEvent the canvas sees, before any gesture handling. Main thread. */
    fun onMotionEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                // Only the approach just before contact matters; a long hover starts over.
                val current = hoverBuilder?.takeIf { it.sampleCount < MAX_HOVER_SAMPLES }
                val hover = current ?: StrokeBuilder(hovering = true).also { hoverBuilder = it }
                hover.addEvent(event)
            }
            MotionEvent.ACTION_HOVER_EXIT -> hoverBuilder?.addEvent(event)
            MotionEvent.ACTION_DOWN -> {
                // The approach (stylus hover) belongs to the stroke it leads into.
                stroke = StrokeBuilder(hovering = false, approach = hoverBuilder).also { it.addEvent(event) }
                hoverBuilder = null
            }
            MotionEvent.ACTION_MOVE -> stroke?.addEvent(event)
            MotionEvent.ACTION_POINTER_DOWN -> stroke?.markMultiTouch()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finished = stroke ?: return
                stroke = null
                finished.addEvent(event)
                finished.cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                finish(finished)
            }
        }
    }

    private fun finish(builder: StrokeBuilder) {
        val record = builder.toJson()
        // Sensor timestamps are elapsedRealtimeNanos; MotionEvent times are uptime. The offset
        // between the clocks is sampled here and stored so training aligns them exactly.
        val clockOffsetNs = SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * NS_PER_MS
        record.put("clockOffsetNs", clockOffsetNs)
        record.put("context", context())
        val fromNs = builder.firstTimeNs - SENSOR_LEAD_NS + clockOffsetNs
        val toNs = builder.lastTimeNs + SENSOR_TAIL_NS + clockOffsetNs
        // Collect the sensor tail after the lift before slicing.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            val sensors = JSONObject()
            rings.values.forEach { ring -> ring.slice(fromNs, toNs)?.let { sensors.put(ring.name, it) } }
            record.put("sensors", sensors)
            sink(record)
        }, SENSOR_TAIL_NS / NS_PER_MS)
    }

    /** One stroke's samples, column by column. */
    private class StrokeBuilder(val hovering: Boolean, val approach: StrokeBuilder? = null) {
        val cols = LinkedHashMap<String, MutableList<Number>>().apply { COLUMNS.forEach { put(it, ArrayList()) } }
        var toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        var deviceName = ""
        var cancelled = false
        var multiTouch = false
        var firstTimeNs = 0L
        var lastTimeNs = 0L
        val sampleCount: Int get() = cols.getValue("t").size

        fun markMultiTouch() {
            multiTouch = true
        }

        fun addEvent(e: MotionEvent) {
            if (e.pointerCount == 0) return
            val p = 0  // the stroke's pointer is the first down; a second pointer is flagged, not traced
            toolType = e.getToolType(p)
            deviceName = e.device?.name.orEmpty()
            for (h in 0 until e.historySize) addSample(e, p, h, historical = true, action = MotionEvent.ACTION_MOVE)
            addSample(e, p, 0, historical = false, action = e.actionMasked)
        }

        private fun addSample(e: MotionEvent, p: Int, h: Int, historical: Boolean, action: Int) {
            fun axis(a: Int) = if (historical) e.getHistoricalAxisValue(a, p, h) else e.getAxisValue(a, p)
            val timeNs = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    if (historical) e.getHistoricalEventTimeNanos(h) else e.eventTimeNanos
                else -> (if (historical) e.getHistoricalEventTime(h) else e.eventTime) * NS_PER_MS
            }
            if (firstTimeNs == 0L) firstTimeNs = timeNs
            lastTimeNs = timeNs
            cols.getValue("t").add(timeNs)
            cols.getValue("action").add(action)
            cols.getValue("x").add(axis(MotionEvent.AXIS_X))
            cols.getValue("y").add(axis(MotionEvent.AXIS_Y))
            cols.getValue("pressure").add(axis(MotionEvent.AXIS_PRESSURE))
            cols.getValue("size").add(axis(MotionEvent.AXIS_SIZE))
            cols.getValue("touchMajor").add(axis(MotionEvent.AXIS_TOUCH_MAJOR))
            cols.getValue("touchMinor").add(axis(MotionEvent.AXIS_TOUCH_MINOR))
            cols.getValue("toolMajor").add(axis(MotionEvent.AXIS_TOOL_MAJOR))
            cols.getValue("toolMinor").add(axis(MotionEvent.AXIS_TOOL_MINOR))
            cols.getValue("orientation").add(axis(MotionEvent.AXIS_ORIENTATION))
            cols.getValue("tilt").add(axis(MotionEvent.AXIS_TILT))
            cols.getValue("distance").add(axis(MotionEvent.AXIS_DISTANCE))
            cols.getValue("buttons").add(e.buttonState)
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", if (hovering) "hover" else "stroke")
            put("tool", TOOL_NAMES[toolType] ?: "unknown")
            put("inputDevice", deviceName)
            put("cancelled", cancelled)
            put("multiTouch", multiTouch)
            put("samples", JSONObject().apply { cols.forEach { (k, v) -> put(k, JSONArray(v)) } })
            approach?.takeIf { it.lastTimeNs > 0L }?.let { put("hover", it.toJson()) }
        }
    }

    /** The last [RING_NS] of one sensor: timestamp plus its values. */
    private class SensorRing(val name: String) {
        private val times = LongArray(RING_CAPACITY)
        private val values = arrayOfNulls<FloatArray>(RING_CAPACITY)
        private var next = 0
        private var count = 0

        @Synchronized
        fun add(timeNs: Long, v: FloatArray) {
            times[next] = timeNs
            values[next] = v.copyOf()
            next = (next + 1) % RING_CAPACITY
            count = minOf(count + 1, RING_CAPACITY)
        }

        @Synchronized
        fun slice(fromNs: Long, toNs: Long): JSONObject? {
            val t = JSONArray()
            val v = JSONArray()
            for (i in 0 until count) {
                val idx = (next - count + i + RING_CAPACITY) % RING_CAPACITY
                if (times[idx] in fromNs..toNs) {
                    t.put(times[idx])
                    v.put(JSONArray(values[idx]!!.map { it.toDouble() }))
                }
            }
            return if (t.length() == 0) null else JSONObject().put("t", t).put("v", v)
        }
    }

    companion object {
        /** Bumped whenever the record layout changes; the dataset loader checks it. */
        const val SCHEMA_VERSION = 1

        private const val NS_PER_MS = 1_000_000L
        private const val SENSOR_LEAD_NS = 500 * NS_PER_MS
        private const val SENSOR_TAIL_NS = 150 * NS_PER_MS
        // 200 Hz: the fastest rate Android grants without HIGH_SAMPLING_RATE_SENSORS.
        private const val SENSOR_PERIOD_US = 5_000
        private const val RING_CAPACITY = 2_048
        private const val MAX_HOVER_SAMPLES = 240

        private val SENSORS = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_GAME_ROTATION_VECTOR to "gameRotationVector",
            Sensor.TYPE_ROTATION_VECTOR to "rotationVector",
            Sensor.TYPE_GRAVITY to "gravity",
            Sensor.TYPE_LINEAR_ACCELERATION to "linearAcceleration",
            Sensor.TYPE_MAGNETIC_FIELD to "magneticField",
        )

        private val COLUMNS = listOf(
            "t", "action", "x", "y", "pressure", "size", "touchMajor", "touchMinor",
            "toolMajor", "toolMinor", "orientation", "tilt", "distance", "buttons",
        )

        private val TOOL_NAMES = mapOf(
            MotionEvent.TOOL_TYPE_FINGER to "finger",
            MotionEvent.TOOL_TYPE_STYLUS to "stylus",
            MotionEvent.TOOL_TYPE_ERASER to "eraser",
            MotionEvent.TOOL_TYPE_MOUSE to "mouse",
        )
    }
}
