package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Slot maths for the radial QuickMenu: which wedge a finger is pointing at.
 *
 * Pure geometry, kept out of the composable so the selection rule can be tested at exact wedge
 * boundaries — the place a radial menu is most likely to feel wrong and least likely to be caught
 * by eye.
 *
 * Slot 0 sits at the top and they run clockwise, so a menu of six reads 12 / 2 / 4 / 6 / 8 / 10
 * o'clock. Angles come from `atan2` in screen coordinates, where +y points *down*, which is what
 * makes clockwise the natural direction here.
 */
object RadialMenuGeometry {

    /** Inside this radius (px) the gesture is still ambiguous — releasing there dismisses. */
    const val DEAD_ZONE_PX = 40f

    /**
     * The slot [point] selects relative to [center], or null when the finger is still inside the
     * dead zone. [slots] must be positive; the wedge width is 360°/[slots].
     */
    fun slotAt(center: Offset, point: Offset, slots: Int = 6, deadZonePx: Float = DEAD_ZONE_PX): Int? {
        if (slots <= 0) return null
        val d = point - center
        if (d.getDistance() < deadZonePx) return null
        return slotForAngle(angleDegrees(d), slots)
    }

    /** Screen-space angle of [d] in degrees: 0° is to the right, growing clockwise. */
    fun angleDegrees(d: Offset): Float =
        Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat()

    /**
     * Maps an angle to a slot index. Rounding (rather than flooring) is what centres slot 0 on
     * straight-up instead of starting it there: a finger a few degrees either side of vertical
     * picks the top slot, which is how it reads to the hand.
     */
    fun slotForAngle(angleDeg: Float, slots: Int): Int {
        val wedge = 360f / slots
        // +90 rotates "up" to zero; the extra +360 keeps the value non-negative before the modulo,
        // since Kotlin's % preserves the sign of the dividend.
        val shifted = (angleDeg + 90f + 360f) % 360f
        return (shifted / wedge).roundToInt() % slots
    }

    /** The centre angle of [slot] in degrees, for laying the wedge's label out. */
    fun slotCenterAngle(slot: Int, slots: Int = 6): Float = -90f + slot * (360f / slots)
}
