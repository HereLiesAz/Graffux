// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/AlignOps.kt
package com.hereliesaz.graffitixr.feature.editor

/** How to align a layer against the artboard. */
enum class AlignMode { LEFT, H_CENTER, RIGHT, TOP, V_CENTER, BOTTOM }

/**
 * Pure alignment geometry: the world-space offset delta that moves a layer's bounding box so the
 * chosen edge or centre lines up with the artboard. Free of Android/Compose types so it's fully
 * unit-testable; the caller adds the returned delta to the layer's offset.
 */
object AlignOps {

    /**
     * @param box the layer's current bounding box as `[left, top, right, bottom]`.
     * @param artboard the artboard rect as `[left, top, width, height]` (as [artboardRect] returns).
     * @return the `(dx, dy)` to add to the layer's offset for the requested [mode].
     */
    fun delta(mode: AlignMode, box: FloatArray, artboard: FloatArray): Pair<Float, Float> {
        val abL = artboard[0]
        val abT = artboard[1]
        val abR = abL + artboard[2]
        val abB = abT + artboard[3]
        val cx = (box[0] + box[2]) / 2f
        val cy = (box[1] + box[3]) / 2f
        return when (mode) {
            AlignMode.LEFT -> (abL - box[0]) to 0f
            AlignMode.H_CENTER -> ((abL + abR) / 2f - cx) to 0f
            AlignMode.RIGHT -> (abR - box[2]) to 0f
            AlignMode.TOP -> 0f to (abT - box[1])
            AlignMode.V_CENTER -> 0f to ((abT + abB) / 2f - cy)
            AlignMode.BOTTOM -> 0f to (abB - box[3])
        }
    }
}
