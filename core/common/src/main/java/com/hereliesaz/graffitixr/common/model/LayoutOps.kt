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
 * A layer's [Layer.offset] is relative to the canvas centre (see VectorShape's doc). Constraints
 * treat a child's offset as the centre of its box and take the frame's box in the children's own
 * space (see [LayoutOps.localFrameRect]). Auto-layout predates that and positions from the frame's
 * offset, treating a child's offset as its top-left; the two never run on the same frame (auto-layout
 * wins, see [LayoutOps.applyResize]). Nothing here touches how anything renders — this produces new
 * offsets and sizes, and the existing renderers draw them.
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

    /** Nesting deeper than this is treated as a cycle and not followed. */
    private const val MAX_FRAME_DEPTH = 32

    /**
     * A frame's box in its children's coordinate space, for a frame [width] x [height] units big.
     *
     * A frame (a [LayerType.GROUP]) draws its children inside its own transform, centred on the
     * layer origin — the group's offset, scale and rotation are applied to the whole subtree by the
     * renderer. So in the space a child's [Layer.offset] lives in, the frame's box is centred on
     * zero and is exactly the frame's declared, unscaled layout size.
     */
    fun localFrameRect(width: Float, height: Float): Rect = Rect(-width / 2f, -height / 2f, width, height)

    /**
     * Repositions (and, for STRETCH / SCALE, resizes) [frameId]'s children after the frame's box
     * went from [old] to [new]. Nested frames whose box changes as a result are resized in turn.
     *
     * Auto-layout wins when the frame declares one: it fully owns its children's placement, so
     * asking constraints to also weigh in would give two answers for the same position. That
     * precedence matches Figma, where turning on auto-layout takes the constraint controls away.
     *
     * Constraint geometry treats a child's [Layer.offset] as the CENTRE of its box, because that is
     * how every renderer and the editor's hit test draw a layer (shapes are centred on
     * the layer origin). A child's extent is its [layoutDeclaredSize].
     */
    fun applyResize(
        layers: List<Layer>,
        frameId: String,
        old: Rect,
        new: Rect,
    ): List<Layer> = applyResize(layers, frameId, old, new, depth = 0)

    private fun applyResize(layers: List<Layer>, frameId: String, old: Rect, new: Rect, depth: Int): List<Layer> {
        val frame = layers.firstOrNull { it.id == frameId }
        return when {
            frame == null -> layers
            frame.autoLayout.direction != LayoutDirection.NONE -> applyAutoLayout(layers, frameId, new)
            old.width <= 0f || old.height <= 0f || depth > MAX_FRAME_DEPTH -> layers
            else -> constrainChildren(layers, frameId, old, new, depth)
        }
    }

    private fun constrainChildren(layers: List<Layer>, frameId: String, old: Rect, new: Rect, depth: Int): List<Layer> {
        var out = layers
        layers.filter { it.parentId == frameId && it.id != frameId }.forEach { child ->
            val updated = ConstraintSolver.constrain(child, old, new)
            out = out.map { if (it.id == child.id) updated else it }
            // A child that is itself a frame and whose box changed passes the resize down.
            val oldBox = ConstraintSolver.frameBox(child)
            val newBox = ConstraintSolver.frameBox(updated)
            if (oldBox != null && newBox != null && oldBox != newBox) {
                if (out.any { it.parentId == child.id }) out = applyResize(out, child.id, oldBox, newBox, depth + 1)
            }
        }
        return out
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
            val size = child.layoutDeclaredSize
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

        val sizes = children.map { it.layoutDeclaredSize }
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

}
