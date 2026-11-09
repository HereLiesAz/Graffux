package com.hereliesaz.graffitixr.feature.editor.threed

import com.hereliesaz.graffitixr.common.model.EditorUiState

/**
 * What a dab on a model's surface looks like: the 2D brush's settings, resolved the way the 2D
 * pipeline resolves them, so the Tool Options sliders mean the same thing on a model as on a photo.
 *
 * - [hardness] is `1 - feathering`: 0 feathering is a hard edge, 1 is fully soft.
 * - [flow] is per-dab build-up, and only a stamp brush has it ("ignored by the built-in brush").
 * - [opacity] caps the whole stroke's coverage however much it overlaps itself. It applies to the
 *   brush; the eraser takes it only as a stamp eraser does.
 */
class SurfaceBrush(
    val color: Int,
    val hardness: Float,
    val flow: Float,
    val opacity: Float,
    val erase: Boolean,
) {
    companion object {
        /** The brush [state]'s sliders describe, painting [color] (or erasing, ignoring its colour). */
        fun of(state: EditorUiState, color: Int, erase: Boolean): SurfaceBrush {
            val stampBrush = state.activeBrushName != null
            return SurfaceBrush(
                color = color,
                hardness = 1f - state.brushFeathering.coerceIn(0f, 1f),
                flow = if (stampBrush) state.brushFlow.coerceIn(0f, 1f) else 1f,
                opacity = if (!erase || stampBrush) state.brushOpacity.coerceIn(0f, 1f) else 1f,
                erase = erase,
            )
        }
    }
}
