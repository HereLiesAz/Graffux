package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.common.azphalt.BrushDeviceAttitude
import com.hereliesaz.graffitixr.common.azphalt.BrushDeviceAttitudeSource

/**
 * Fused phone/tablet attitude in screen coordinates.
 *
 * GAME_ROTATION_VECTOR is preferred because brush intent only needs short-term relative attitude,
 * not magnetic north. TYPE_ROTATION_VECTOR is the fallback. The screen remap is essential on
 * tablets whose natural device orientation may be landscape.
 */
@Composable
internal fun rememberDeviceAttitude(
    view: View,
    enabled: Boolean,
): State<BrushDeviceAttitude> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(BrushDeviceAttitude()) }

    DisposableEffect(context, view, enabled) {
        if (!enabled) {
            state.value = BrushDeviceAttitude()
            onDispose { }
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val game = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            val earth = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val sensor = game ?: earth

            if (sensor == null) {
                state.value = BrushDeviceAttitude()
                onDispose { }
            } else {
                val source = if (sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR
                } else {
                    BrushDeviceAttitudeSource.ROTATION_VECTOR
                }
                val rawMatrix = FloatArray(9)
                val screenMatrix = FloatArray(9)
                val angles = FloatArray(3)

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.sensor.type != sensor.type) return
                        SensorManager.getRotationMatrixFromVector(rawMatrix, event.values)

                        val rotation = view.display?.rotation ?: Surface.ROTATION_0
                        val (axisX, axisY) = when (rotation) {
                            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                        }
                        if (!SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, screenMatrix)) {
                            return
                        }
                        SensorManager.getOrientation(screenMatrix, angles)

                        state.value = BrushDeviceAttitude(
                            pitchRadians = angles[1],
                            rollRadians = angles[2],
                            yawRadians = angles[0],
                            tiltConfidence = if (source == BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR) 0.95f else 0.9f,
                            // Yaw is intentionally lower-confidence than gravity-referenced pitch/roll.
                            // GAME_ROTATION_VECTOR can drift; ROTATION_VECTOR can be disturbed by local magnetics.
                            yawConfidence = if (source == BrushDeviceAttitudeSource.GAME_ROTATION_VECTOR) 0.82f else 0.78f,
                            source = source,
                        ).sanitized()
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }

                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                onDispose {
                    sensorManager.unregisterListener(listener)
                    state.value = BrushDeviceAttitude()
                }
            }
        }
    }

    return state
}
