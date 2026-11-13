package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * The visible paint layers that carry brush strokes and each one's plane in the scene (spec/3d-space.md, "Snap to
 * stroked planes"): [ids], the box [centres] and unit [normals], index for index. A cache entry is good for a
 * request whose layer list is the same instance as [layers] (identity, so any layer edit invalidates it: visibility,
 * lock, transform, bitmap), whose canvas is [size], and whose per-layer stroke state still gives [stamp] (stroke
 * count and whether strokes were baked: a stroke added, undone or baked changes it). A finished pixel scan clears
 * it too.
 */
internal class StrokedPlanes(
    val layers: List<Layer>,
    val size: IntSize,
    val stamp: Long,
    val ids: List<String>,
    val centres: List<FloatArray>,
    val normals: List<FloatArray>,
) {
    companion object {
        private const val MIX = 31L

        /** A number that changes when any layer's stroke count does (a stroke added, undone or baked into pixels). */
        fun stampOf(layers: List<Layer>, strokeCount: (String) -> Int): Long =
            layers.fold(0L) { acc, layer -> acc * MIX + strokeCount(layer.id) + 1 }
    }
}
