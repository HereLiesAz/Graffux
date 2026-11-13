package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.scene.SceneCamera

/**
 * A stroke drawn ([command], by identity) on [layerId], and the view it was drawn in (spec/3d-space.md, "Draw plane
 * depth"). Its layer's plane is the reference of a draw-plane depth, and only while the view [holds]: any orbit, pan,
 * zoom or Reset view since makes the reference lapse. [prior] is the stroke before it (a short chain), the reference
 * again once this one is undone or dropped.
 */
data class LastStroke(
    val layerId: String,
    val command: StrokeCommand?,
    val camera: SceneCamera,
    val viewportZoom: Float,
    val viewportOffset: Offset,
    val viewportRotation: Float,
    val prior: LastStroke? = null,
) {
    fun holds(state: EditorUiState): Boolean =
        camera == state.sceneCamera && viewportZoom == state.viewportZoom &&
            viewportOffset == state.viewportOffset && viewportRotation == state.viewportRotation

    /** This chain without the strokes in [gone] (by identity): undone or dropped. Null when none is left. */
    fun without(gone: Collection<StrokeCommand>): LastStroke? {
        val rest = prior?.without(gone)
        return if (command != null && gone.any { it === command }) rest else copy(prior = rest)
    }

    /** This chain cut to [depth] strokes. */
    fun truncated(depth: Int): LastStroke = copy(prior = if (depth <= 1) null else prior?.truncated(depth - 1))

    companion object {
        const val CHAIN_DEPTH = 8

        fun of(layerId: String, command: StrokeCommand?, state: EditorUiState, prior: LastStroke?) = LastStroke(
            layerId, command, state.sceneCamera, state.viewportZoom, state.viewportOffset, state.viewportRotation,
            prior?.truncated(CHAIN_DEPTH - 1),
        )
    }
}
