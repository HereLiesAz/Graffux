package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.DEFAULT_PEN_ANGLE_DEG
import com.hereliesaz.graffitixr.common.azphalt.wrapPenAngle
import com.hereliesaz.graffitixr.common.model.Tool
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The nib angle, held the way a real pen is: fixed in the world. [userAngleDeg] is the angle the
 * user set, as seen on screen with the device where it was when it was last levelled; [twistDeg] is
 * how far the device has since turned about its screen normal (counter-clockwise positive, net of
 * the UI's own rotation). Turning the device turns the paper under the pen, so the nib's angle on
 * screen — and on the layer — changes by the twist.
 *
 * Locked ([lockedLayerDeg] set), the nib keeps its angle to the canvas instead: neither turning the
 * device nor rotating the canvas view changes what it draws, and on screen it turns with the canvas.
 */
@Stable
class NibAngleState(initialDeg: Float = DEFAULT_PEN_ANGLE_DEG, initialLockDeg: Float? = null) {
    var userAngleDeg by mutableFloatStateOf(initialDeg)
    var twistDeg by mutableFloatStateOf(0f)
        internal set

    /** The nib's angle on the layer while locked to the canvas; null while it follows the world. */
    var lockedLayerDeg by mutableStateOf(initialLockDeg)
        private set

    /** The canvas view's rotation on screen (clockwise degrees), kept current by the editor. */
    var viewRotationDeg by mutableFloatStateOf(0f)

    val locked: Boolean get() = lockedLayerDeg != null

    /** Set by [relevel]: the tracker takes its next reading as the new zero twist. */
    internal var relevelRequested = true

    /** The nib's edge direction on screen, degrees like a stroke heading (90 = down). */
    val screenAngleDeg: Float get() = lockedLayerDeg?.let { wrapPenAngle(it + viewRotationDeg) }
        ?: nibScreenAngle(userAngleDeg, twistDeg)

    /** Points the nib at [deg] on screen as the device is held now (and the canvas sits now, if locked). */
    fun setScreenAngle(deg: Float) {
        if (lockedLayerDeg != null) {
            lockedLayerDeg = nibLayerAngle(deg, viewRotationDeg)
        } else {
            userAngleDeg = wrapPenAngle(deg - twistDeg)
        }
    }

    /** Locks the nib to the canvas at its current angle, or releases it to the world where it points. */
    fun toggleLock() {
        val onScreen = screenAngleDeg
        if (lockedLayerDeg == null) {
            lockedLayerDeg = nibLayerAngle(onScreen, viewRotationDeg)
        } else {
            lockedLayerDeg = null
            userAngleDeg = wrapPenAngle(onScreen - twistDeg)
        }
    }

    /** Takes the device's current attitude as level, keeping the nib where it points on screen. */
    fun relevel() {
        if (locked) return
        userAngleDeg = screenAngleDeg
        twistDeg = 0f
        relevelRequested = true
    }
}

/** The nib angle on screen for a user angle and a device twist (both degrees). */
internal fun nibScreenAngle(userAngleDeg: Float, twistDeg: Float): Float = wrapPenAngle(userAngleDeg + twistDeg)

/** The nib angle on the layer, for a canvas view rotated [viewRotationDeg] clockwise on screen. */
fun nibLayerAngle(screenAngleDeg: Float, viewRotationDeg: Float): Float = wrapPenAngle(screenAngleDeg - viewRotationDeg)

/**
 * The device's twist about its screen normal since [reference], from two rotation matrices (device
 * to world, row-major), counter-clockwise positive, less the change in UI rotation ([uiQuarterTurns]
 * against [referenceQuarterTurns], each a [Surface] rotation: the UI turning a quarter with the
 * device puts the artwork back the right way up, so that quarter is not a twist).
 */
internal fun deviceTwistDeg(
    reference: FloatArray,
    current: FloatArray,
    uiQuarterTurns: Int = 0,
    referenceQuarterTurns: Int = 0,
): Float {
    // delta = reference^T · current: the current device axes in the reference device frame.
    val d00 = (0 until AXES).sumOf { (reference[at(it, 0)] * current[at(it, 0)]).toDouble() }
    val d10 = (0 until AXES).sumOf { (reference[at(it, 1)] * current[at(it, 0)]).toDouble() }
    val twist = Math.toDegrees(atan2(d10, d00)).toFloat()
    return wrapPenAngle(twist - QUARTER_TURN_DEG * (uiQuarterTurns - referenceQuarterTurns))
}

private const val QUARTER_TURN_DEG = 90f
private const val AXES = 3

/** Index of row [row], column [col] in a row-major 3×3 matrix. */
private fun at(row: Int, col: Int): Int = row * AXES + col

/** Keeps the angle the user set, and any lock, across configuration changes; the twist is re-measured. */
val NibAngleSaver: Saver<NibAngleState, List<Float>> = Saver(
    save = { listOfNotNull(it.userAngleDeg, it.lockedLayerDeg) },
    restore = { NibAngleState(it[0], it.getOrNull(1)) },
)

/** Tools that paint with the brush, so a nib-angle brush in hand offers the dial. */
internal val BRUSH_TOOLS: Set<Tool> = setOf(Tool.BRUSH, Tool.ERASER)

/** The dial sits below the screen's top chrome. */
internal val NibDialTopInset = 72.dp

/** Whether [this] brush's stroke program reads the nib angle, so the editor offers the dial. */
fun AzphaltBrush.usesPenAngle(): Boolean {
    val p = program ?: return false
    return PEN_ANGLE_INPUT.containsMatchIn(p.init) || PEN_ANGLE_INPUT.containsMatchIn(p.step)
}

private val PEN_ANGLE_INPUT = Regex("""\bpenAngle\b""")

/** The nib angle the canvas records with each sample; null outside the editor. */
val LocalNibAngle = staticCompositionLocalOf<NibAngleState?> { null }

/**
 * Keeps [state]'s twist current from the rotation-vector sensor while [enabled]. The first reading
 * (and the first after [NibAngleState.relevel]) is the level the twist is measured from.
 */
@Suppress("FunctionNaming")
@Composable
fun TrackNibTwist(state: NibAngleState, view: View, enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, view, enabled, state) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.let {
            it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }
        if (!enabled || sensorManager == null || sensor == null) {
            onDispose { }
        } else {
            val reference = FloatArray(MATRIX_SIZE)
            val current = FloatArray(MATRIX_SIZE)
            var referenceTurns = 0
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(current, event.values)
                    val turns = view.display?.rotation ?: Surface.ROTATION_0
                    if (state.relevelRequested) {
                        current.copyInto(reference)
                        referenceTurns = turns
                        state.relevelRequested = false
                    }
                    state.twistDeg = deviceTwistDeg(reference, current, turns, referenceTurns)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                sensorManager.unregisterListener(listener)
                state.relevelRequested = true
            }
        }
    }
}

private const val MATRIX_SIZE = 9

/**
 * The nib-angle dial: the nib drawn at its angle on screen, so it holds still in the world while the
 * device turns under it (or turns with the canvas, locked). Drag around it to set the angle; tap to
 * lock the nib to the canvas or release it; long-press to take the device as it is held now as level.
 */
@Suppress("FunctionNaming")
@Composable
fun NibAngleDial(state: NibAngleState, modifier: Modifier = Modifier) {
    val angle = state.screenAngleDeg
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
            .size(DialSize)
            .testTag("nib.dial")
            .semantics {
                contentDescription = "Nib angle ${shownAngle(state)} degrees" +
                    if (state.locked) ", locked to the canvas" else ""
            }
            .pointerInput(state) {
                detectDragGestures { change, _ ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val v = change.position - c
                    if (v.getDistance() > size.width * MIN_DRAG_FRACTION) {
                        state.setScreenAngle(Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat())
                    }
                }
            }
            .pointerInput(state) {
                detectTapGestures(onTap = { state.toggleLock() }, onLongPress = { state.relevel() })
            },
    ) {
        Canvas(Modifier.size(DialSize)) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Color.Black.copy(alpha = 0.55f), r)
            drawCircle(ink.copy(alpha = 0.35f), r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
            val rad = Math.toRadians(angle.toDouble())
            val half = Offset((cos(rad) * r * NIB_REACH).toFloat(), (sin(rad) * r * NIB_REACH).toFloat())
            // The nib's edge: a broad stroke drawn edge-on, the way it would mark the paper.
            drawLine(ink, c - half, c + half, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Butt)
            drawCircle(ink, 2.dp.toPx(), c)
            // Locked to the canvas: a closed ring where the open one was.
            if (state.locked) drawCircle(ink, r - 3.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
        Text(
            "${shownAngle(state)}°",
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.8f),
        )
    }
}

/** The angle as a calligrapher reads it: the edge's rise from horizontal, 0..180. */
private fun shownAngle(state: NibAngleState): Int {
    val rise = -(state.lockedLayerDeg ?: state.userAngleDeg)
    return Math.floorMod(rise.roundToInt(), HALF_TURN)
}

private const val HALF_TURN = 180
private val DialSize = 64.dp
private const val NIB_REACH = 0.72f
private const val MIN_DRAG_FRACTION = 0.08f
