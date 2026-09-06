package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.BrushSample

/** Immutable input hand-off for a coalesced Azphalt preview frame. */
internal data class AzphaltPreviewSnapshot(
    val generation: Long,
    val samples: List<BrushSample>,
    val mappedPoints: FloatArray,
)
