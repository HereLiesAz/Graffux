package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * Thin per-stroke owner for Google's Ink Stroke Modeler Kalman predictor. Coordinates are passed in
 * screen pixels and timestamps in Android uptime milliseconds; the native bridge normalizes pixels
 * before feeding the unit-agnostic model and converts its prediction back again.
 */
class InkStrokePredictor : AutoCloseable {
    init {
        NativeLibLoader.loadAll()
    }

    private var nativeHandle: Long = nativeCreate()
    private var hasInput = false

    val isAvailable: Boolean get() = nativeHandle != 0L

    fun reset(): Boolean {
        if (nativeHandle == 0L) return false
        hasInput = false
        return nativeReset(nativeHandle)
    }

    fun record(x: Float, y: Float, uptimeMillis: Long, pressure: Float): Boolean {
        if (nativeHandle == 0L) return false
        val down = !hasInput
        val ok = nativeRecord(
            nativeHandle,
            x,
            y,
            uptimeMillis,
            pressure.coerceIn(0f, 1f),
            down,
        )
        if (ok) hasInput = true
        return ok
    }

    data class Prediction(
        val x: Float,
        val y: Float,
        val uptimeMillis: Long,
        val pressure: Float,
    )

    fun predict(): Prediction? {
        if (nativeHandle == 0L || !hasInput) return null
        val values = nativePredict(nativeHandle) ?: return null
        if (values.size < 4) return null
        // A double round-trips uptimeMillis exactly (a jfloat can't past ~4.6h of device uptime --
        // see nativePredict's doc comment); position/pressure just widen back down from it losslessly.
        return Prediction(
            x = values[0].toFloat(),
            y = values[1].toFloat(),
            uptimeMillis = values[2].toLong(),
            pressure = values[3].toFloat().takeIf { it.isFinite() && it >= 0f } ?: 1f,
        )
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            hasInput = false
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeReset(handle: Long): Boolean
    private external fun nativeRecord(
        handle: Long,
        x: Float,
        y: Float,
        uptimeMillis: Long,
        pressure: Float,
        isDown: Boolean,
    ): Boolean
    private external fun nativePredict(handle: Long): DoubleArray?
    private external fun nativeDestroy(handle: Long)
}
