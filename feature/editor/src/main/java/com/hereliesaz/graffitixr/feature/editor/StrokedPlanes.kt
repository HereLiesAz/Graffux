package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * The visible paint layers that carry brush strokes and each one's plane in the scene (spec/3d-space.md, "Snap to
 * stroked planes"): [ids], the box [centres] and unit [normals], index for index. Built for [layers] and [size]
 * and good while every layer's stroke count is still the one in [stamp].
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
