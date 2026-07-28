package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable

/**
 * Figma's constraints and auto-layout, as pure geometry.
 *
 * Both answer the same question — "where does this child go when its frame changes?" — from opposite
 * directions. **Constraints** pin a child to its frame's edges and let it react to a resize.
 * **Auto-layout** takes the frame's children and positions them itself, in a row or a column.
 *
 * A layer's [Layer.offset] is relative to the canvas centre (see VectorShape's doc), so everything
 * here works in that same space: a frame's rect is derived from its own offset plus a declared size,
 * and children are placed as offsets within it. Nothing here touches how anything renders — this
 * produces new offsets, and the existing renderers draw them.
 */

/** How a child sticks to its frame on one axis when the frame resizes. */
@Serializable
enum class ConstraintAnchor {
    /** Keeps its distance from the frame's start edge (left / top). */
    START,
    /** Keeps its distance from the frame's end edge (right / bottom). */
    END,
    /** Keeps its distance from the frame's centre. */
    CENTER,
    /** Keeps both edge distances, so the child grows with the frame. */
    STRETCH,
    /** Keeps its position and size proportional to the frame. */
    SCALE,
}

/** A child's per-axis constraints. */
@Serializable
data class Constraints(
    val horizontal: ConstraintAnchor = ConstraintAnchor.START,
    val vertical: ConstraintAnchor = ConstraintAnchor.START,
)

@Serializable
enum class LayoutDirection { NONE, HORIZONTAL, VERTICAL }

@Serializable
enum class LayoutAlign { START, CENTER, END }

/** A frame's auto-layout settings. [direction] NONE means the frame does not lay out its children. */
@Serializable
data class AutoLayout(
    val direction: LayoutDirection = LayoutDirection.NONE,
    val gap: Float = 0f,
    val paddingStart: Float = 0f,
    val paddingEnd: Float = 0f,
    val paddingTop: Float = 0f,
    val paddingBottom: Float = 0f,
    /** Alignment on the axis the layout does NOT stack along. */
    val align: LayoutAlign = LayoutAlign.START,
)

/** A rectangle in canvas space: [x],[y] is the top-left corner. */
data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

object LayoutOps {

    /**
     * Repositions [frameId]'s children after the frame resized from [old] to [new].
     *
     * Auto-layout wins when the frame declares one: it fully owns its children's placement, so
     * asking constraints to also weigh in would give two answers for the same position. That
     * precedence matches Figma, where turning on auto-layout takes the constraint controls away.
     */
    fun applyResize(
        layers: List<Layer>,
        frameId: String,
        old: Rect,
        new: Rect,
    ): List<Layer> {
        val frame = layers.firstOrNull { it.id == frameId } ?: return layers
        if (frame.autoLayout.direction != LayoutDirection.NONE) {
            return applyAutoLayout(layers, frameId, new)
        }
        if (old.width <= 0f || old.height <= 0f) return layers
        return layers.map { child ->
            if (child.parentId != frameId) child else child.constrainedTo(old, new)
        }
    }

    /**
     * Stacks [frameId]'s children along its auto-layout axis. Children keep their declared sizes;
     * only their positions are computed. Invisible children are skipped and take no space, which is
     * what makes toggling one off collapse the stack instead of leaving a hole.
     */
    fun applyAutoLayout(layers: List<Layer>, frameId: String, frame: Rect): List<Layer> {
        val frameLayer = layers.firstOrNull { it.id == frameId } ?: return layers
        val layout = frameLayer.autoLayout
        if (layout.direction == LayoutDirection.NONE) return layers

        val children = layers.filter { it.parentId == frameId && it.isVisible }
        if (children.isEmpty()) return layers

        val innerX = frame.x + layout.paddingStart
        val innerY = frame.y + layout.paddingTop
        val innerW = (frame.width - layout.paddingStart - layout.paddingEnd).coerceAtLeast(0f)
        val innerH = (frame.height - layout.paddingTop - layout.paddingBottom).coerceAtLeast(0f)

        val placed = HashMap<String, Offset>(children.size)
        var cursor = 0f
        children.forEach { child ->
            val size = child.declaredSize
            if (layout.direction == LayoutDirection.HORIZONTAL) {
                val y = innerY + crossOffset(layout.align, innerH, size.second)
                placed[child.id] = Offset(innerX + cursor, y)
                cursor += size.first + layout.gap
            } else {
                val x = innerX + crossOffset(layout.align, innerW, size.first)
                placed[child.id] = Offset(x, innerY + cursor)
                cursor += size.second + layout.gap
            }
        }
        return layers.map { layer -> placed[layer.id]?.let { layer.copy(offset = it) } ?: layer }
    }

    /**
     * The size the frame would need to fit its auto-laid-out children exactly — Figma's "hug
     * contents". Returns null when the frame has no auto-layout or nothing visible to hug.
     */
    fun hugContentsSize(layers: List<Layer>, frameId: String): Pair<Float, Float>? {
        val frame = layers.firstOrNull { it.id == frameId } ?: return null
        val layout = frame.autoLayout
        if (layout.direction == LayoutDirection.NONE) return null
        val children = layers.filter { it.parentId == frameId && it.isVisible }
        if (children.isEmpty()) return null

        val sizes = children.map { it.declaredSize }
        val gaps = layout.gap * (children.size - 1)
        return if (layout.direction == LayoutDirection.HORIZONTAL) {
            val w = sizes.sumOf { it.first.toDouble() }.toFloat() + gaps + layout.paddingStart + layout.paddingEnd
            val h = (sizes.maxOfOrNull { it.second } ?: 0f) + layout.paddingTop + layout.paddingBottom
            w to h
        } else {
            val w = (sizes.maxOfOrNull { it.first } ?: 0f) + layout.paddingStart + layout.paddingEnd
            val h = sizes.sumOf { it.second.toDouble() }.toFloat() + gaps + layout.paddingTop + layout.paddingBottom
            w to h
        }
    }

    private fun crossOffset(align: LayoutAlign, available: Float, size: Float): Float = when (align) {
        LayoutAlign.START -> 0f
        LayoutAlign.CENTER -> (available - size) / 2f
        LayoutAlign.END -> available - size
    }

    /** Applies this layer's constraints for a frame resize from [old] to [new]. */
    private fun Layer.constrainedTo(old: Rect, new: Rect): Layer {
        val (w, h) = declaredSize
        val x = axis(constraints.horizontal, offset.x, w, old.x, old.width, new.x, new.width)
        val y = axis(constraints.vertical, offset.y, h, old.y, old.height, new.y, new.height)
        // STRETCH and SCALE change the child's size as well as its position. Size lives on the
        // shape for a vector layer, and on `scale` otherwise, so only the uniform-scale case is
        // applied here — a non-uniform stretch of a raster layer has no field to express it.
        val scaled = when {
            constraints.horizontal == ConstraintAnchor.SCALE && old.width > 0f ->
                copy(scale = scale * (new.width / old.width))
            else -> this
        }
        return scaled.copy(offset = Offset(x.first, y.first))
    }

    /**
     * One axis of constraint solving. Returns the new position (and the new extent, for the callers
     * that can use it) given the child's [pos]/[size] and the frame's old/new origin and extent.
     */
    private fun axis(
        anchor: ConstraintAnchor,
        pos: Float,
        size: Float,
        oldOrigin: Float,
        oldExtent: Float,
        newOrigin: Float,
        newExtent: Float,
    ): Pair<Float, Float> {
        val startGap = pos - oldOrigin
        val endGap = (oldOrigin + oldExtent) - (pos + size)
        return when (anchor) {
            ConstraintAnchor.START -> (newOrigin + startGap) to size
            ConstraintAnchor.END -> (newOrigin + newExtent - endGap - size) to size
            ConstraintAnchor.CENTER -> {
                val centreGap = (pos + size / 2f) - (oldOrigin + oldExtent / 2f)
                (newOrigin + newExtent / 2f + centreGap - size / 2f) to size
            }
            ConstraintAnchor.STRETCH -> {
                val newSize = (newExtent - startGap - endGap).coerceAtLeast(0f)
                (newOrigin + startGap) to newSize
            }
            ConstraintAnchor.SCALE -> {
                if (oldExtent <= 0f) return (newOrigin + startGap) to size
                val ratio = newExtent / oldExtent
                (newOrigin + startGap * ratio) to (size * ratio)
            }
        }
    }

    /**
     * A layer's size for layout purposes. Vector layers measure their shapes; everything else falls
     * back to its scaled bitmap-independent extent, since a raster layer's true pixel size isn't
     * available in this pure module.
     */
    private val Layer.declaredSize: Pair<Float, Float>
        get() {
            val shape = shapes.firstOrNull()
            if (shape != null) return (shape.width * scale) to (shape.height * scale)
            return (layoutWidth * scale) to (layoutHeight * scale)
        }
}
