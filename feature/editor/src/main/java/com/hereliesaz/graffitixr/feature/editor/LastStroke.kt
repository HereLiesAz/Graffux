package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.scene.SceneCamera

/**
 * The layer of the newest stroke and the view it was drawn in (spec/3d-space.md, "Draw plane depth"). Its plane is
 * the reference of a draw-plane depth, and only while the view [holds]: any orbit, pan, zoom or Reset view
 * since makes the reference lapse.
 */
internal data class LastStroke(
    val layerId: String,
    val camera: SceneCamera,
    val viewportZoom: Float,
    val viewportOffset: Offset,
    val viewportRotation: Float,
    /** The stroke before this one, one deep: what the reference falls back to when this one is undone or refused. */
    val prior: LastStroke? = null,
) {
    fun holds(state: EditorUiState): Boolean =
        camera == state.sceneCamera && viewportZoom == state.viewportZoom &&
            viewportOffset == state.viewportOffset && viewportRotation == state.viewportRotation

    companion object {
        fun of(layerId: String, state: EditorUiState, prior: LastStroke? = null) = LastStroke(
            layerId, state.sceneCamera, state.viewportZoom, state.viewportOffset, state.viewportRotation,
            prior?.copy(prior = null),
        )
    }
}
