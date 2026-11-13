package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * The visible paint layers that carry brush strokes and each one's plane in the scene (spec/3d-space.md, "Snap to
 * stroked planes"): [ids], the box [centres] and unit [normals], index for index.
 *
 * It is good for a request ([isCurrent]) whose layer list is the same instance as [layers] (identity, so any layer edit
 * replaces it: visibility, lock, transform, bitmap), whose canvas is [size], and in which every layer's stroke state is
 * the one in [strokeState]. A layer's stroke state is two numbers in one: its recorded stroke count and whether strokes
 * were baked into its base this session. It is kept per layer, in layer order, and compared one by one, so there is no
 * hash to collide and no allocation on a hit. A finished pixel scan clears the cache too (the view-model drops it).
 */
internal class StrokedPlanes(
    val layers: List<Layer>,
    val size: IntSize,
    private val strokeState: IntArray,
    val ids: List<String>,
    val centres: List<FloatArray>,
    val normals: List<FloatArray>,
) {
    /** Whether this still answers for [requested] layers on a canvas of [requestedSize], given each state now. */
    fun isCurrent(requested: List<Layer>, requestedSize: IntSize, stateNow: (layerId: String) -> Int): Boolean =
        requested === layers && requestedSize == size &&
            requested.indices.all { stateNow(requested[it].id) == strokeState[it] }

    companion object {
        /** Each layer's stroke state, in layer order: the stroke count times two, plus one if strokes were baked. */
        fun stateOf(layers: List<Layer>, stateNow: (layerId: String) -> Int): IntArray =
            IntArray(layers.size) { stateNow(layers[it].id) }
    }
}
