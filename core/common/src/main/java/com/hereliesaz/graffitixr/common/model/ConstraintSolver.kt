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
        val c = layer.constraints
        val x = axis(
            c.horizontal, Extent(layer.offset.x, w, c.collapsedWidth), Span(old.x, old.width), Span(new.x, new.width),
        )
        val y = axis(
            c.vertical, Extent(layer.offset.y, h, c.collapsedHeight), Span(old.y, old.height), Span(new.y, new.height),
        )
        val constraints = c.copy(collapsedWidth = x.collapsed, collapsedHeight = y.collapsed)
        return resize(layer, x.factor, y.factor).copy(offset = Offset(x.centre, y.centre), constraints = constraints)
    }

    /** The child's box on one axis: its centre, its extent, and any collapsed STRETCH extent. */
    private data class Extent(val centre: Float, val size: Float, val collapsed: Float?)

    /** One axis's answer: the new centre, the extent factor, and any collapsed STRETCH extent. */
    private data class AxisResult(val centre: Float, val factor: Float, val collapsed: Float? = null)

    /** The smallest extent STRETCH shrinks a child to; below it the child is held, not zeroed. */
    const val MIN_STRETCH_EXTENT = 1f

    /**
     * The box a frame hands its own children: its local (centred) rect, for constraints and
     * auto-layout alike. Null for anything without a declared layout box.
     */
    fun frameBox(layer: Layer): Rect? = when {
        layer.shapes.isNotEmpty() || layer.layoutWidth <= 0f || layer.layoutHeight <= 0f -> null
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
    /** [resize], for [LayoutOps.resizeLayer]. */
    fun resized(layer: Layer, fx: Float, fy: Float): Layer = resize(layer, fx, fy)

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
     * One axis of constraint solving. [box] is the child's box on this axis; the
     * frame's box went from [old] to [new]. Returns the child's new centre and the factor its extent
     * changes by — 1 except for STRETCH and SCALE. A child with no measurable size (a raster layer
     * with no declared size) can still SCALE, since that factor is the frame's own ratio, but has
     * nothing for STRETCH to divide by and only moves.
     *
     * STRETCH never takes a child below [MIN_STRETCH_EXTENT]: a frame smaller than the child's
     * margins holds it there and records the extent it "should" have ([Extent.collapsed]), and the next
     * resize measures the margins from that recorded extent, so growing the frame back restores
     * the original size exactly.
     */
    private fun axis(anchor: ConstraintAnchor, box: Extent, old: Span, new: Span): AxisResult {
        val (centre, size, collapsed) = box
        val start = centre - size / 2f
        val startGap = start - old.origin
        val endGap = (old.origin + old.extent) - (start + size)
        return when (anchor) {
            ConstraintAnchor.START -> AxisResult(new.origin + startGap + size / 2f, 1f)
            ConstraintAnchor.END -> AxisResult(new.origin + new.extent - endGap - size / 2f, 1f)
            ConstraintAnchor.CENTER ->
                AxisResult(new.origin + new.extent / 2f + centre - (old.origin + old.extent / 2f), 1f)
            ConstraintAnchor.STRETCH -> if (size <= 0f) {
                AxisResult(new.origin + startGap, 1f)
            } else {
                // While collapsed, the margins are those of the extent the child would have had.
                val effective = collapsed ?: size
                val effectiveEndGap = (old.origin + old.extent) - (start + effective)
                val wanted = new.extent - startGap - effectiveEndGap
                val actual = wanted.coerceAtLeast(MIN_STRETCH_EXTENT)
                AxisResult(
                    new.origin + startGap + actual / 2f,
                    actual / size,
                    collapsed = wanted.takeIf { it < MIN_STRETCH_EXTENT },
                )
            }
            ConstraintAnchor.SCALE -> if (old.extent <= 0f) {
                AxisResult(new.origin + startGap + size / 2f, 1f)
            } else {
                val ratio = new.extent / old.extent
                AxisResult(new.origin + startGap * ratio + size * ratio / 2f, ratio)
            }
        }
    }
}

/**
 * A layer's size for layout purposes. A vector layer measures its first shape (the one whose box
 * stands for the layer; later shapes are not unioned in); everything else falls
 * back to its scaled declared extent, since a raster layer's true pixel size isn't available in
 * this pure module.
 */
internal val Layer.layoutDeclaredSize: Pair<Float, Float>
    get() {
        val shape = shapes.firstOrNull()
        if (shape != null) return (shape.width * scale) to (shape.height * scale)
        return (layoutWidth * scale) to (layoutHeight * scale)
    }
