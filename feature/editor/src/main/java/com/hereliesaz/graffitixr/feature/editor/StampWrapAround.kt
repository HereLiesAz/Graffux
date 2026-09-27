package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.Dab

/**
 * Wrap-around (seamless-tile) mode for stamp brushes: every dab repeated at the 3x3 neighbouring
 * tile offsets, so paint crossing one edge re-enters from the opposite one. The stamp-brush
 * counterpart of the legacy round brush's own 3x3 expansion (EditorViewModel's live curve feed and
 * ImageProcessor.drawStroke). Shared by the live preview and DrawingEngine's commit/replay so the
 * two paint identical dabs. A dual-brush [Dab.mask] carries absolute coordinates and moves with it.
 */
internal fun wrapTiledDabs(dabs: List<Dab>, width: Int, height: Int): List<Dab> {
    if (dabs.isEmpty()) return dabs
    val w = width.toFloat()
    val h = height.toFloat()
    val out = ArrayList<Dab>(dabs.size * TILE_COPIES)
    for (dab in dabs) {
        for (dx in -1..1) for (dy in -1..1) {
            val ox = dx * w
            val oy = dy * h
            out.add(
                dab.copy(
                    x = dab.x + ox,
                    y = dab.y + oy,
                    mask = dab.mask?.let { it.copy(x = it.x + ox, y = it.y + oy) },
                ),
            )
        }
    }
    return out
}

private const val TILE_COPIES = 9
