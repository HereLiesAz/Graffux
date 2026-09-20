package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PaintMedium

/**
 * Deterministic layer-material ownership for Impasto v2.
 *
 * A materialized layer keeps the medium that first created its canonical material state. Later
 * brushes may change colour/geometry/load, but they do not retroactively change the viscosity,
 * drying, leveling or optics of paint that is already on the layer. Phase 7 can replace this
 * layer-level ownership with a richer per-media catalogue without changing replay semantics.
 */
internal class MaterialMediumReplayState(initial: PaintMedium? = null) {
    var medium: PaintMedium? = initial?.sanitized()
        private set

    fun resolve(incoming: PaintMedium): PaintMedium {
        val existing = medium
        if (existing != null) return existing
        return incoming.sanitized().also { medium = it }
    }

    fun copyForWork(): MaterialMediumReplayState = MaterialMediumReplayState(medium)
}
