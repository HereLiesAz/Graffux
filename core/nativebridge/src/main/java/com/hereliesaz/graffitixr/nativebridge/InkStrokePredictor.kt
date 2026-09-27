package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * Google Ink Stroke Modeler's Kalman predictor, used directly (see InkStrokePredictorJNI.cpp for
 * why not through its StrokeModeler). Coordinates are screen pixels, timestamps Android uptime
 * milliseconds.
 *
 * [estimate] returns Ink's Kalman state at the latest sample; [predictAt] evaluates Ink's own cubic
 * from it (p + v t + a t^2/2 + j t^3/6) at any future time, so every display frame ahead is read
 * straight off the model rather than interpolated or extrapolated from a fixed curve.
 */
class InkStrokePredictor(
    /** Ink's pull toward linear motion: 0 = straight lines only, 1 = full curvature. */
    accelerationWeight: Float = 0.5f,
    jerkWeight: Float = 0.1f,
    /** Kalman measurement noise in Ink model units (1 unit = 100 px). */
    measurementNoise: Double = 0.026458,
) : AutoCloseable {
    init {
        NativeLibLoader.loadAll()
    }

    private var nativeHandle: Long = nativeCreate(accelerationWeight, jerkWeight, measurementNoise)
    private var hasInput = false

    val isAvailable: Boolean get() = nativeHandle != 0L

    fun reset(): Boolean {
        if (nativeHandle == 0L) return false
        hasInput = false
        return nativeReset(nativeHandle)
    }

    fun record(x: Float, y: Float, uptimeMillis: Long, pressure: Float): Boolean {
        if (nativeHandle == 0L) return false
        val ok = nativeRecord(nativeHandle, x, y, uptimeMillis, pressure.coerceIn(0f, 1f))
        if (ok) hasInput = true
        return ok
    }

    /** Ink's Kalman estimate at [uptimeMillis] (the latest sample). Pixels; per-second rates. */
    data class Estimate(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val ax: Float,
        val ay: Float,
        val jx: Float,
        val jy: Float,
        val uptimeMillis: Long,
        val pressure: Float,
    )

    data class Prediction(
        val x: Float,
        val y: Float,
        val uptimeMillis: Long,
        val pressure: Float,
    )

    /** Null until Ink's Kalman filters are stable (a few samples into the stroke). */
    fun estimate(): Estimate? {
        val v = if (nativeHandle != 0L && hasInput) nativeEstimate(nativeHandle) else null
        return v?.takeIf { it.size >= ESTIMATE_FIELDS }?.let {
            Estimate(
                x = it[0].toFloat(), y = it[1].toFloat(),
                vx = it[2].toFloat(), vy = it[3].toFloat(),
                ax = it[4].toFloat(), ay = it[5].toFloat(),
                jx = it[6].toFloat(), jy = it[7].toFloat(),
                uptimeMillis = it[8].toLong(),
                pressure = it[9].toFloat().takeIf { p -> p.isFinite() && p >= 0f } ?: 1f,
            )
        }
    }

    /** Ink's cubic evaluated at [targetUptimeMillis]; null until the estimate is stable. */
    fun predictAt(targetUptimeMillis: Long): Prediction? = estimate()?.let { e ->
        val t = ((targetUptimeMillis - e.uptimeMillis).coerceAtLeast(0L) / MILLIS_PER_SECOND).toFloat()
        val t2 = t * t / 2f
        val t3 = t * t * t / THIRD_ORDER_FACTORIAL
        Prediction(
            x = e.x + e.vx * t + e.ax * t2 + e.jx * t3,
            y = e.y + e.vy * t + e.ay * t2 + e.jy * t3,
            uptimeMillis = targetUptimeMillis,
            pressure = e.pressure,
        )
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            hasInput = false
        }
    }

    private external fun nativeCreate(accelerationWeight: Float, jerkWeight: Float, measurementNoise: Double): Long
    private external fun nativeReset(handle: Long): Boolean
    private external fun nativeRecord(handle: Long, x: Float, y: Float, uptimeMillis: Long, pressure: Float): Boolean
    private external fun nativeEstimate(handle: Long): DoubleArray?
    private external fun nativeDestroy(handle: Long)
}

private const val ESTIMATE_FIELDS = 10 // nativeEstimate's layout, see InkStrokePredictorJNI.cpp
private const val MILLIS_PER_SECOND = 1000.0
private const val THIRD_ORDER_FACTORIAL = 6f // the 3! of the cubic term j t^3/3!
