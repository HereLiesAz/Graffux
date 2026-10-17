package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * Whether a recorded stroke re-renders faithfully at another resolution: a plain stamp, Basic Brush or
 * Eraser stroke. Anything that reads or depends on the layer's pixel grid (selection, warp, fill, Ink,
 * smudge/liquify/clone, impasto, grain, dual tip, substrate, wrap-around) is not.
 */
internal object HighResOverlayEligibility {

    fun strokeReplayable(stroke: StrokeCommand): Boolean = !stroke.dependsOnPixelGrid() && when (stroke.tool) {
        Tool.ERASER -> true
        Tool.BRUSH -> stroke.stampBrush?.let { stroke.stampReplays(it) } ?: true
        else -> false
    }

    private fun StrokeCommand.dependsOnPixelGrid(): Boolean {
        val editsRegion = selection != null || warpHandles != null
        val bulkFill = fillSelection || clearAll
        val special = inkStroke != null || wrapAroundMode || substrateId != null
        return editsRegion || bulkFill || special
    }

    private fun StrokeCommand.stampReplays(brush: AzphaltBrush): Boolean {
        val plainBrush = brush.impastoThicknessRate <= 0f && brush.maskedBrush == null && brush.substrateResponse <= 0f
        return plainBrush && stampGrain == null && stampMaskShape == null
    }
}
