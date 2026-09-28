package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset

/**
 * The per-child half of [LayoutOps.applyResize]: where one constrained child goes, and what size it
 * becomes, when its frame's box changes. Kept apart from [LayoutOps] so each stays small.
 *
 * Geometry here treats a layer's [Layer.offset] as the CENTRE of its box — that is how layers render
 * (shapes are centred on the layer origin) — and its extent as [layoutDeclaredSize].
 */
internal object ConstraintSolver {

    /** One axis of a frame's box: where it starts and how long it is. */
    private data class Span(val origin: Float, val extent: Float)

    /** Applies this layer's constraints for a frame resize from [old] to [new], each axis on its own. */
    fun constrain(layer: Layer, old: Rect, new: Rect): Layer {
        val (w, h) = layer.layoutDeclaredSize
        val (cx, fx) = axis(
            layer.constraints.horizontal, layer.offset.x, w, Span(old.x, old.width), Span(new.x, new.width),
        )
        val (cy, fy) = axis(
            layer.constraints.vertical, layer.offset.y, h, Span(old.y, old.height), Span(new.y, new.height),
        )
        return resize(layer, fx, fy).copy(offset = Offset(cx, cy))
    }

    /**
     * The box a frame hands its own children: its local rect, or — for an auto-layout frame, which
     * positions children from its offset (see [LayoutOps.applyAutoLayout]) — the rect it lays out
     * into. Null for anything without a declared layout box.
     */
    fun frameBox(layer: Layer): Rect? = when {
        layer.shapes.isNotEmpty() || layer.layoutWidth <= 0f || layer.layoutHeight <= 0f -> null
        layer.autoLayout.direction != LayoutDirection.NONE ->
            Rect(layer.offset.x, layer.offset.y, layer.layoutWidth, layer.layoutHeight)
        else -> LayoutOps.localFrameRect(layer.layoutWidth, layer.layoutHeight)
    }

    /**
     * Scales [layer]'s size by [fx] horizontally and [fy] vertically, writing it to wherever this
     * kind of layer keeps its size:
     *  - a vector layer: its shapes' width / height (and a path's points and handles), so a
     *    non-uniform stretch really is non-uniform. The layer's own `scale` is left alone.
     *  - a frame or any layer with a declared layout size: [Layer.layoutWidth] / [Layer.layoutHeight].
     *  - anything else (a raster, text or imported image layer): only the uniform [Layer.scale]
     *    exists, so the smaller of the axis factors that changed is applied. The layer keeps its
     *    aspect and sits centred in the box its constraints asked for.
     */
    private fun resize(layer: Layer, fx: Float, fy: Float): Layer = when {
        fx == 1f && fy == 1f -> layer
        layer.shapes.isNotEmpty() -> layer.copy(shapes = layer.shapes.map { resize(it, fx, fy) })
        layer.layoutWidth > 0f || layer.layoutHeight > 0f ->
            layer.copy(layoutWidth = layer.layoutWidth * fx, layoutHeight = layer.layoutHeight * fy)
        else -> layer.copy(scale = layer.scale * uniformFactor(fx, fy))
    }

    private fun uniformFactor(fx: Float, fy: Float): Float = when {
        fx != 1f && fy != 1f -> minOf(fx, fy)
        fx != 1f -> fx
        else -> fy
    }

    private fun resize(shape: VectorShape, fx: Float, fy: Float): VectorShape {
        val w = shape.width * fx
        val h = shape.height * fy
        fun List<Float>.scaled() = mapIndexed { i, v -> if (i % 2 == 0) v * fx else v * fy }
        return shape.copy(
            width = w,
            height = h,
            cornerRadius = shape.cornerRadius.coerceAtMost(minOf(w, h) / 2f).coerceAtLeast(0f),
            points = shape.points.scaled(),
            handlesIn = shape.handlesIn.scaled(),
            handlesOut = shape.handlesOut.scaled(),
        )
    }

    /**
     * One axis of constraint solving. [centre] and [size] are the child's box on this axis; the
     * frame's box went from [old] to [new]. Returns the child's new centre and the factor its extent
     * changes by — 1 except for STRETCH and SCALE. A child with no measurable size (a raster layer
     * with no declared size) can still SCALE, since that factor is the frame's own ratio, but has
     * nothing for STRETCH to divide by and only moves.
     */
    private fun axis(anchor: ConstraintAnchor, centre: Float, size: Float, old: Span, new: Span): Pair<Float, Float> {
        val start = centre - size / 2f
        val startGap = start - old.origin
        val endGap = (old.origin + old.extent) - (start + size)
        return when (anchor) {
            ConstraintAnchor.START -> (new.origin + startGap + size / 2f) to 1f
            ConstraintAnchor.END -> (new.origin + new.extent - endGap - size / 2f) to 1f
            ConstraintAnchor.CENTER ->
                (new.origin + new.extent / 2f + centre - (old.origin + old.extent / 2f)) to 1f
            ConstraintAnchor.STRETCH -> if (size <= 0f) {
                (new.origin + startGap) to 1f
            } else {
                val newSize = (new.extent - startGap - endGap).coerceAtLeast(0f)
                (new.origin + startGap + newSize / 2f) to (newSize / size)
            }
            ConstraintAnchor.SCALE -> if (old.extent <= 0f) {
                (new.origin + startGap + size / 2f) to 1f
            } else {
                val ratio = new.extent / old.extent
                (new.origin + startGap * ratio + size * ratio / 2f) to ratio
            }
        }
    }
}

/**
 * A layer's size for layout purposes. Vector layers measure their shapes; everything else falls
 * back to its scaled declared extent, since a raster layer's true pixel size isn't available in
 * this pure module.
 */
internal val Layer.layoutDeclaredSize: Pair<Float, Float>
    get() {
        val shape = shapes.firstOrNull()
        if (shape != null) return (shape.width * scale) to (shape.height * scale)
        return (layoutWidth * scale) to (layoutHeight * scale)
    }
