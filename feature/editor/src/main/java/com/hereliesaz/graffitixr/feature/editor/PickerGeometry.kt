// SPDX-License-Identifier: Apache-2.0
// Square/disc transform adapted from ProCraft crates/core/src/pickers.rs.
// Attribution and upstream license: docs/ProCraft adoption.md.
package com.hereliesaz.graffitixr.feature.editor

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Coordinates in a unit square/disc, with positive y down. */
internal data class PickerPoint(val x: Float, val y: Float)

/** ProCraft's elliptical square-to-disc mapping preserves every HSV corner. */
internal fun pickerSquareToDisc(x: Float, y: Float): PickerPoint {
    val a = x.coerceIn(-1f, 1f)
    val b = y.coerceIn(-1f, 1f)
    return PickerPoint(a * sqrt(1f - b * b / 2f), b * sqrt(1f - a * a / 2f))
}

/** Inverse mapping, projecting out-of-disc drags to the closest point on its edge. */
internal fun pickerDiscToSquare(x: Float, y: Float): PickerPoint {
    val radius = hypot(x, y).coerceAtLeast(1f)
    val u = x / radius
    val v = y / radius
    val k = 2f * sqrt(2f)
    fun component(a: Float, b: Float): Float {
        val term = 2f + a * a - b * b
        return ((sqrt((term + k * a).coerceAtLeast(0f)) -
            sqrt((term - k * a).coerceAtLeast(0f))) / 2f).coerceIn(-1f, 1f)
    }
    return PickerPoint(component(u, v), component(v, u))
}

/** Red is at the right of the wheel, hue increases counterclockwise. */
internal fun pickerHueAt(x: Float, y: Float): Float =
    ((atan2(-y, x) * (180f / PI.toFloat())) + 360f) % 360f
