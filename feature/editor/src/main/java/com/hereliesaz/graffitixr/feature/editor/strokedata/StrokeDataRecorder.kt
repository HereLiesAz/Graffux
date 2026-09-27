package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records strokes for training Graffux's own stroke-prediction model (tools/stroke-model). For every
 * stroke: each raw input sample of every pointer with every axis the hardware reports -- position,
 * pressure, contact size and ellipse, orientation and tilt of the finger or stylus, tool type,
 * stylus hover before contact -- plus the phone's motion sensors around the stroke, on one clock.
 * Schema: tools/stroke-model/SCHEMA.md.
 *
 * MotionEvents come from the canvas (main thread). Sensor events arrive on a dedicated thread. A
 * finished stroke waits [SENSOR_TAIL_NS] for the post-stroke tail, then asks the sensor stack to
 * [SensorManager.flush] anything still buffered and slices only once every registered sensor has
 * reported [onFlushCompleted] (or [FLUSH_TIMEOUT_MS] passes). Only then is it handed to [sink].
 */
class StrokeDataRecorder(
    private val sensorManager: SensorManager?,
    /** Canvas/brush/device context stamped on each stroke (brush, zoom, display Hz, ...). */
    private val context: () -> JSONObject,
    private val sink: (JSONObject) -> Unit,
    /** elapsedRealtime ns, the sensor clock. Replaceable for tests. */
    private val elapsedRealtimeNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** Delivers sensor callbacks on their own thread; false (tests) delivers on the main looper. */
    private val sensorThread: Boolean = true,
) : SensorEventListener2 {

    /** Raw touch heatmap (root only, opt-in); null when off. Set before the first stroke. */
    var heatmap: HeatmapSource? = null

    private val rings = SENSORS.associate { (type, name) -> type to SensorRing(name, RING_CAPACITY) }
    private val registeredTypes = LinkedHashSet<Int>()
    private var registered = false
    private var thread: HandlerThread? = null
    private var stroke: StrokeBuilder? = null
    private var hoverBuilder: StrokeBuilder? = null
    /** Strokes waiting for their flush, oldest first; completions arrive in request order. */
    private val awaitingFlush = ArrayDeque<PendingStroke>()

    /** Starts the sensor streams; call while the canvas is visible. */
    fun start() {
        if (registered || sensorManager == null) return
        val handler = if (sensorThread) {
            HandlerThread("stroke-sensors").also { it.start(); thread = it }.let { Handler(it.looper) }
        } else {
            mainHandler
        }
        for ((type, _) in SENSORS) {
            val sensor = sensorManager.getDefaultSensor(type) ?: continue
            // maxReportLatencyUs = 0: ask the hub not to batch; events are wanted as they happen.
            if (sensorManager.registerListener(this, sensor, SENSOR_PERIOD_US, 0, handler)) {
                registeredTypes.add(type)
            }
        }
        registered = true
    }

    fun stop() {
        if (registered) sensorManager?.unregisterListener(this)
        registered = false
        registeredTypes.clear()
        thread?.quitSafely()
        thread = null
        stroke = null
        hoverBuilder = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        rings[event.sensor.type]?.add(event.timestamp, elapsedRealtimeNs(), event.values)
    }

    override fun onFlushCompleted(sensor: Sensor) {
        val type = sensor.type
        mainHandler.post { flushCompleted(type) }
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
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                stroke?.addEvent(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finished = stroke ?: return
                stroke = null
                finished.addEvent(event)
                finish(finished)
            }
        }
    }

    private fun finish(builder: StrokeBuilder) {
        val record = builder.toJson()
        // Sensor timestamps are elapsedRealtimeNanos; MotionEvent times are uptime. The offset
        // between the clocks is sampled here and stored so training aligns them exactly.
        val clockOffsetNs = elapsedRealtimeNs() - SystemClock.uptimeMillis() * NS_PER_MS
        record.put("clockOffsetNs", clockOffsetNs)
        record.put("context", context())
        val pending = PendingStroke(
            record = record,
            fromNs = builder.firstTimeNs - SENSOR_LEAD_NS + clockOffsetNs,
            toNs = builder.lastTimeNs + SENSOR_TAIL_NS + clockOffsetNs,
            registered = registeredTypes.toSet(),
            heatFromNs = builder.firstTimeNs - HEATMAP_LEAD_NS,
            heatToNs = builder.lastTimeNs + HEATMAP_TAIL_NS,
        )
        // Let the tail after the lift happen, then flush whatever the sensor stack still holds.
        mainHandler.postDelayed({ requestFlush(pending) }, SENSOR_TAIL_NS / NS_PER_MS)
    }

    private fun requestFlush(pending: PendingStroke) {
        val manager = sensorManager
        if (pending.registered.isEmpty() || manager == null || !registered) {
            complete(pending, FLUSH_NONE)
            return
        }
        pending.awaiting.addAll(pending.registered)
        awaitingFlush.addLast(pending)
        if (!manager.flush(this)) {
            complete(pending, FLUSH_UNSUPPORTED)
            return
        }
        mainHandler.postDelayed({ complete(pending, FLUSH_TIMEOUT) }, FLUSH_TIMEOUT_MS)
    }

    private fun flushCompleted(type: Int) {
        val pending = awaitingFlush.firstOrNull { type in it.awaiting } ?: return
        pending.awaiting.remove(type)
        pending.flushed.add(type)
        if (pending.awaiting.isEmpty()) complete(pending, FLUSH_COMPLETED)
    }

    private fun complete(pending: PendingStroke, flush: String) {
        if (pending.done) return
        pending.done = true
        awaitingFlush.remove(pending)
        val sensors = JSONObject()
        val status = JSONObject()
        for ((type, ring) in rings) {
            val slice = ring.slice(pending.fromNs, pending.toNs)
            slice?.let { sensors.put(ring.name, it) }
            status.put(ring.name, ring.status(type in pending.registered, type in pending.flushed, slice))
        }
        pending.record.put("sensors", sensors)
        pending.record.put("sensorsRegistered", JSONArray(pending.registered.mapNotNull { rings[it]?.name }))
        pending.record.put("sensorStatus", status)
        pending.record.put("flush", flush)
        heatmap?.let { source ->
            // Uptime ns, like the samples. Capped per stroke in HeatmapCapture.slice.
            source.slice(pending.heatFromNs, pending.heatToNs)?.let { pending.record.put("heatmap", it.toJson()) }
            pending.record.put("heatmapStatus", source.status().toJson())
        }
        sink(pending.record)
    }

    private class PendingStroke(
        val record: JSONObject,
        val fromNs: Long,
        val toNs: Long,
        val registered: Set<Int>,
        val heatFromNs: Long,
        val heatToNs: Long,
    ) {
        val awaiting = HashSet<Int>()
        val flushed = HashSet<Int>()
        var done = false
    }

    companion object {
        /** Bumped whenever the record layout changes; the dataset loader checks it. */
        const val SCHEMA_VERSION = 3

        private const val NS_PER_MS = 1_000_000L
        private const val SENSOR_LEAD_NS = 500 * NS_PER_MS
        private const val SENSOR_TAIL_NS = 150 * NS_PER_MS
        /** Heatmap window around a stroke; the tail fits inside [SENSOR_TAIL_NS], so it has arrived. */
        const val HEATMAP_LEAD_NS = 100 * NS_PER_MS
        const val HEATMAP_TAIL_NS = 50 * NS_PER_MS
        /** How long a stroke waits for [onFlushCompleted] before slicing what it has. */
        const val FLUSH_TIMEOUT_MS = 1_000L
        // 200 Hz: the fastest rate Android grants without HIGH_SAMPLING_RATE_SENSORS.
        private const val SENSOR_PERIOD_US = 5_000
        // ~19 s at the ~215 Hz a Pixel 5 actually delivers: the lead, a long stroke and the flush wait.
        private const val RING_CAPACITY = 4_096
        private const val MAX_HOVER_SAMPLES = 240

        const val FLUSH_COMPLETED = "completed"
        const val FLUSH_TIMEOUT = "timeout"
        const val FLUSH_UNSUPPORTED = "unsupported"
        const val FLUSH_NONE = "none"

        private val SENSORS = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_GAME_ROTATION_VECTOR to "gameRotationVector",
            Sensor.TYPE_ROTATION_VECTOR to "rotationVector",
            Sensor.TYPE_GRAVITY to "gravity",
            Sensor.TYPE_LINEAR_ACCELERATION to "linearAcceleration",
            Sensor.TYPE_MAGNETIC_FIELD to "magneticField",
        )
    }
}
