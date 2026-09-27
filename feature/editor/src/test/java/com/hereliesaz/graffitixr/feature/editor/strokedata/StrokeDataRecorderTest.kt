package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.TimeUnit

/**
 * The sensor half of the stroke recorder: events that reach the app late (hub FIFO batching, or a
 * stream whose timestamps trail real time) must still land in the stroke they belong to, and a
 * stroke must say which sensors were registered so "absent" is distinguishable from "lost".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrokeDataRecorderTest {
    private val accel: Sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)
    private val records = mutableListOf<JSONObject>()
    private lateinit var manager: SensorManager
    private lateinit var recorder: StrokeDataRecorder

    @Before
    fun setUp() {
        manager = mockk(relaxed = true)
        every { manager.getDefaultSensor(any()) } returns null
        every { manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) } returns accel
        every { manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns gyro
        every {
            manager.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>(), any<Int>(), any<Handler>())
        } returns true
        every { manager.flush(any()) } returns true
        recorder = StrokeDataRecorder(
            sensorManager = manager,
            context = { JSONObject() },
            sink = { records.add(it) },
            mainHandler = Handler(Looper.getMainLooper()),
            sensorThread = false,
        )
        recorder.start()
    }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    private fun nowElapsedNs() = SystemClock.elapsedRealtimeNanos()

    /** A 40 ms single-finger stroke; returns its [start, end] in elapsedRealtime ns. */
    private fun drawStroke(): Pair<Long, Long> {
        val down = SystemClock.uptimeMillis()
        val startNs = nowElapsedNs()
        recorder.onMotionEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        idle(20)
        recorder.onMotionEvent(MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_MOVE, 110f, 100f, 0))
        idle(20)
        recorder.onMotionEvent(MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, 120f, 100f, 0))
        return startNs to nowElapsedNs()
    }

    private fun deliver(sensor: Sensor, timestampNs: Long) {
        recorder.onSensorChanged(
            SensorEventBuilder.newBuilder().setSensor(sensor).setTimestamp(timestampNs)
                .setValues(floatArrayOf(0.1f, 0.2f, 9.8f)).build(),
        )
    }

    @Test
    fun `batched events delivered after the tail still land in the stroke once the flush completes`() {
        val (startNs, endNs) = drawStroke()
        idle(150)
        verify(exactly = 1) { manager.flush(recorder) }
        assertTrue("must wait for the flush, not slice at the tail", records.isEmpty())

        // The hub's FIFO hands over the whole stroke's worth of accelerometer data 300 ms late.
        idle(300)
        val stamps = (startNs until endNs step 5_000_000L).toList()
        stamps.forEach { deliver(accel, it) }
        recorder.onFlushCompleted(accel)
        idle(1)
        assertTrue("gyro has not completed its flush yet", records.isEmpty())
        recorder.onFlushCompleted(gyro)
        idle(1)

        assertEquals(1, records.size)
        val r = records.single()
        assertEquals(StrokeDataRecorder.FLUSH_COMPLETED, r.getString("flush"))
        val t = r.getJSONObject("sensors").getJSONObject("accelerometer").getJSONArray("t")
        assertEquals(stamps.size, t.length())
        val arrivals = r.getJSONObject("sensors").getJSONObject("accelerometer").getJSONArray("a")
        assertEquals(stamps.size, arrivals.length())
        val status = r.getJSONObject("sensorStatus")
        assertTrue(status.getJSONObject("accelerometer").getBoolean("registered"))
        assertTrue(status.getJSONObject("accelerometer").getBoolean("flushed"))
        assertTrue(status.getJSONObject("gyroscope").getBoolean("registered"))
        val magnetometer = status.getJSONObject("magneticField")
        assertFalse("never registered: absent, not lost", magnetometer.getBoolean("registered"))
        assertEquals(listOf("accelerometer", "gyroscope"), r.getJSONArray("sensorsRegistered").let { a ->
            (0 until a.length()).map { a.getString(it) }
        })
    }

    @Test
    fun `a flush that never completes falls back to slicing after the timeout`() {
        val (startNs, _) = drawStroke()
        deliver(accel, startNs)
        idle(150)
        assertTrue(records.isEmpty())
        idle(StrokeDataRecorder.FLUSH_TIMEOUT_MS)
        assertEquals(1, records.size)
        assertEquals(StrokeDataRecorder.FLUSH_TIMEOUT, records.single().getString("flush"))
        val accelerometer = records.single().getJSONObject("sensors").getJSONObject("accelerometer")
        assertEquals(1, accelerometer.getJSONArray("t").length())
    }

    @Test
    fun `events whose timestamps trail their arrival are kept by arrival time`() {
        val down = SystemClock.uptimeMillis()
        recorder.onMotionEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        // The stream's own clock runs 6 s behind: stamped long before the stroke, arriving during it.
        deliver(accel, nowElapsedNs() - 6_000_000_000L)
        idle(20)
        recorder.onMotionEvent(MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_UP, 5f, 0f, 0))
        idle(150)
        recorder.onFlushCompleted(accel)
        recorder.onFlushCompleted(gyro)
        idle(1)
        val r = records.single()
        assertEquals(1, r.getJSONObject("sensors").getJSONObject("accelerometer").getJSONArray("t").length())
        assertTrue(r.getJSONObject("sensorStatus").getJSONObject("accelerometer").getLong("lagNs") >= 5_000_000_000L)
    }

    @Test
    fun `second pointers are recorded as their own tracks with palm and canceled flags`() {
        recorder.stop()
        val plain = StrokeDataRecorder(null, { JSONObject() }, { records.add(it) }, sensorThread = false)
        val down = SystemClock.uptimeMillis()
        plain.onMotionEvent(event(down, down, MotionEvent.ACTION_DOWN, listOf(Touch(0, 100f))))
        plain.onMotionEvent(
            event(down, down + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(Touch(0, 101f), Touch(1, 500f, PALM))),
        )
        plain.onMotionEvent(
            event(down, down + 20, MotionEvent.ACTION_MOVE, listOf(Touch(0, 102f), Touch(1, 505f, PALM))),
        )
        plain.onMotionEvent(
            event(down, down + 30, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(Touch(0, 103f), Touch(1, 506f, PALM)), flags = MotionEvent.FLAG_CANCELED),
        )
        plain.onMotionEvent(event(down, down + 40, MotionEvent.ACTION_UP, listOf(Touch(0, 104f))))
        idle(200)

        val r = records.single()
        assertTrue(r.getBoolean("multiTouch"))
        assertEquals(0, r.getInt("pointerId"))
        val xs = r.getJSONObject("samples").getJSONArray("x")
        assertEquals(listOf(100.0, 101.0, 102.0, 103.0, 104.0), (0 until xs.length()).map { xs.getDouble(it) })
        val second = r.getJSONArray("pointers").getJSONObject(0)
        assertEquals(1, r.getJSONArray("pointers").length())
        assertEquals(1, second.getInt("pointerId"))
        assertEquals("palm", second.getString("tool"))
        assertTrue(second.getBoolean("palm"))
        assertTrue(second.getBoolean("canceled"))
        val actions = second.getJSONObject("samples").getJSONArray("action")
        assertEquals(
            listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP),
            (0 until actions.length()).map { actions.getInt(it) },
        )
        assertEquals(StrokeDataRecorder.FLUSH_NONE, r.getString("flush"))
    }

    private data class Touch(val id: Int, val x: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER)

    private fun event(down: Long, time: Long, action: Int, touches: List<Touch>, flags: Int = 0): MotionEvent {
        val props = touches.map { t ->
            MotionEvent.PointerProperties().apply { id = t.id; toolType = t.tool }
        }.toTypedArray()
        val coords = touches.map { t ->
            MotionEvent.PointerCoords().apply { x = t.x; y = 50f; touchMajor = 20f; touchMinor = 10f; pressure = 0.5f }
        }.toTypedArray()
        return MotionEvent.obtain(down, time, action, touches.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    private companion object {
        const val PALM = PointerTrack.TOOL_TYPE_PALM
    }
}
