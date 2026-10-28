@file:Suppress("MaxLineLength", "TooManyFunctions", "ReturnCount", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.common.model

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Design frames (Figma's frames / artboards) as pure list transforms. A frame is an ordinary
 * [Layer] of type [LayerType.FRAME]: a container whose children render inside its transform, with a
 * box of its own ([Layer.layoutWidth] x [Layer.layoutHeight], centred on the frame's origin).
 *
 * Coordinates are the editor's world space: the space a top-level layer's [Layer.offset] lives in,
 * with the canvas centre at (0, 0). A child's offset is in its parent's space, which the renderer
 * builds by applying the parent's offset, scale and Z rotation about the canvas centre (see
 * `LayerStackNode`); [worldTransform] composes that chain so geometry here and pixels on screen
 * agree. X/Y (3D) rotation is ignored, as the hit test ignores it.
 */
object FrameOps {

    /** The gap Figma leaves between frames placed side by side, in world units. */
    const val FRAME_GAP = 80f

    /** Nesting deeper than this is treated as a cycle and not followed. */
    private const val MAX_DEPTH = 32

    /** A layer's placement in world space: [Layer.offset], [Layer.scale] and [Layer.rotationZ] composed with its ancestors'. */
    data class WorldTransform(val offset: Offset, val scale: Float, val rotation: Float) {
        /** Maps a point in this layer's own space (centred on its origin) into world space. */
        fun apply(local: Offset): Offset {
            val rad = Math.toRadians(rotation.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            return Offset(
                offset.x + scale * (local.x * c - local.y * s),
                offset.y + scale * (local.x * s + local.y * c),
            )
        }

        /** The inverse of [apply]. */
        fun invert(world: Offset): Offset {
            if (scale == 0f) return Offset.Zero
            val rad = Math.toRadians(-rotation.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val d = world - offset
            return Offset((d.x * c - d.y * s) / scale, (d.x * s + d.y * c) / scale)
        }

        companion object {
            val IDENTITY = WorldTransform(Offset.Zero, 1f, 0f)
        }
    }

    /** Every frame in the stack, in stack order. */
    fun frames(layers: List<Layer>): List<Layer> = layers.filter { it.isFrame }

    /** The name a new frame gets: "Frame N", one past the highest N already used. */
    fun nextName(layers: List<Layer>): String {
        val used = layers.mapNotNull { l -> l.name.removePrefix("Frame ").toIntOrNull()?.takeIf { l.isFrame } }
        return "Frame ${(used.maxOrNull() ?: 0) + 1}"
    }

    /**
     * A new frame covering [box] (world space, top-left + size). Clips its content and is filled
     * white, as a new Figma frame is.
     */
    fun create(id: String, name: String, box: Rect, fillArgb: Long? = DEFAULT_FILL): Layer = Layer(
        id = id,
        name = name,
        type = LayerType.FRAME,
        offset = Offset(box.centerX, box.centerY),
        layoutWidth = box.width,
        layoutHeight = box.height,
        frameFillArgb = fillArgb,
        clipsContent = true,
    )

    /** White, Figma's default frame fill. */
    const val DEFAULT_FILL: Long = 0xFFFFFFFFL

    /**
     * Where a new [width] x [height] frame goes when it is added rather than drawn: to the right of
     * the rightmost top-level frame, tops aligned, [FRAME_GAP] apart; centred on the origin when
     * there is no frame yet.
     */
    fun nextPlacement(layers: List<Layer>, width: Float, height: Float): Rect {
        val rightmost = layers.filter { it.isFrame && it.parentId == null }.maxByOrNull { boxOf(layers, it).right }
            ?: return Rect(-width / 2f, -height / 2f, width, height)
        val box = boxOf(layers, rightmost)
        return Rect(box.right + FRAME_GAP, box.y, width, height)
    }

    /** [layer]'s offset, scale and Z rotation in world space. */
    fun worldTransform(layers: List<Layer>, layer: Layer): WorldTransform {
        val byId = layers.associateBy { it.id }
        val chain = ArrayList<Layer>()
        var cursor: Layer? = layer
        while (cursor != null && chain.size <= MAX_DEPTH) {
            chain.add(cursor)
            cursor = cursor.parentId?.let(byId::get)?.takeIf { p -> chain.none { it.id == p.id } }
        }
        // Outermost first: each parent maps its child's offset into its own parent's space.
        var t = WorldTransform.IDENTITY
        for (l in chain.asReversed()) {
            t = WorldTransform(t.apply(l.offset), t.scale * l.scale, t.rotation + l.rotationZ)
        }
        return t
    }

    /**
     * The layers with every parent's transform folded into its children's own, so code that
     * treats each layer as top-level (the hit test, the selection outline) sees them where they
     * render. Parent links are kept.
     */
    fun flattenTransforms(layers: List<Layer>): List<Layer> {
        if (layers.none { it.parentId != null }) return layers
        return layers.map { l ->
            if (l.parentId == null) l
            else worldTransform(layers, l).let { t -> l.copy(offset = t.offset, scale = t.scale, rotationZ = t.rotation) }
        }
    }

    /**
     * A frame's box in world space (top-left + size). Exact for an unrotated frame; for a rotated
     * one it is the box before rotation, about the same centre.
     */
    fun boxOf(layers: List<Layer>, frame: Layer): Rect {
        val t = worldTransform(layers, frame)
        val w = frame.layoutWidth * t.scale
        val h = frame.layoutHeight * t.scale
        return Rect(t.offset.x - w / 2f, t.offset.y - h / 2f, w, h)
    }

    /** Whether [world] lies inside [frame]'s (possibly rotated) box. */
    fun contains(layers: List<Layer>, frame: Layer, world: Offset): Boolean {
        val local = worldTransform(layers, frame).invert(world)
        return kotlin.math.abs(local.x) <= frame.layoutWidth / 2f && kotlin.math.abs(local.y) <= frame.layoutHeight / 2f
    }

    /** The topmost visible frame whose box contains [world], or null. */
    fun frameAt(layers: List<Layer>, world: Offset, except: String? = null): Layer? =
        layers.asReversed().firstOrNull { it.isFrame && it.isVisible && it.id != except && contains(layers, it, world) }

    /** The topmost visible frame whose name label (above its top-left corner) contains [world]. */
    fun labelAt(layers: List<Layer>, world: Offset, labelHeight: Float, labelWidth: Float): Layer? =
        layers.asReversed().firstOrNull { f ->
            f.isFrame && f.isVisible && f.parentId == null && boxOf(layers, f).let { b ->
                world.x in b.x..(b.x + labelWidth) && world.y in (b.y - labelHeight)..b.y
            }
        }

    /** Whether [ancestorId] is [layer]'s parent, grandparent, ... */
    fun isDescendant(layers: List<Layer>, layer: Layer, ancestorId: String): Boolean {
        val byId = layers.associateBy { it.id }
        var parent = layer.parentId
        var depth = 0
        while (parent != null && depth++ <= MAX_DEPTH) {
            if (parent == ancestorId) return true
            parent = byId[parent]?.parentId
        }
        return false
    }

    /**
     * Moves [layerId] under [newParentId] (null: the top level) without moving it on screen: its
     * offset, scale and rotation are re-expressed in the new parent's space. A move into itself or
     * into one of its own descendants is refused.
     */
    fun reparent(layers: List<Layer>, layerId: String, newParentId: String?): List<Layer> {
        val layer = layers.firstOrNull { it.id == layerId } ?: return layers
        if (layer.parentId == newParentId) return layers
        if (newParentId != null) {
            val parent = layers.firstOrNull { it.id == newParentId } ?: return layers
            if (parent.id == layerId || isDescendant(layers, parent, layerId)) return layers
        }
        val world = worldTransform(layers, layer)
        val parentT = newParentId?.let { id -> layers.firstOrNull { it.id == id } }
            ?.let { worldTransform(layers, it) } ?: WorldTransform.IDENTITY
        val local = parentT.invert(world.offset)
        val moved = layer.copy(
            parentId = newParentId,
            offset = local,
            scale = if (parentT.scale == 0f) layer.scale else world.scale / parentT.scale,
            rotationZ = world.rotation - parentT.rotation,
        )
        return layers.map { if (it.id == layerId) moved else it }
    }

    /**
     * Figma's drop rule, run after [movedId] was dragged: a layer whose centre ends inside a frame
     * (not its own, and not one of its descendants) moves into the topmost such frame; a layer in a
     * frame whose centre ends outside it moves out to the top level. Nothing moves on screen.
     */
    fun reparentAfterMove(layers: List<Layer>, movedId: String): List<Layer> {
        val moved = layers.firstOrNull { it.id == movedId } ?: return layers
        if (moved.isAdjustment) return layers
        val centre = worldTransform(layers, moved).offset
        val target = layers.asReversed().firstOrNull { f ->
            f.isFrame && f.isVisible && f.id != movedId && !isDescendant(layers, f, movedId) &&
                contains(layers, f, centre)
        }
        val parent = moved.parentId?.let { id -> layers.firstOrNull { it.id == id } }
        return when {
            target != null && target.id != moved.parentId &&
                // A layer already in a frame stays put while its centre is still inside that frame.
                (parent == null || !parent.isFrame || !contains(layers, parent, centre)) ->
                reparent(layers, movedId, target.id)
            target == null && parent != null && parent.isFrame && !contains(layers, parent, centre) ->
                reparent(layers, movedId, parent.parentId)
            else -> layers
        }
    }

    /**
     * Figma's "draw a frame over things": every top-level layer (not a frame, not an adjustment
     * layer) whose centre lies inside the new frame [frameId] moves into it, staying where it is
     * on screen.
     */
    fun adoptContents(layers: List<Layer>, frameId: String): List<Layer> {
        val frame = layers.firstOrNull { it.id == frameId && it.isFrame } ?: return layers
        var out = layers
        layers.filter { it.parentId == null && it.id != frameId && !it.isFrame && !it.isAdjustment }
            .filter { contains(layers, frame, it.offset) }
            .forEach { out = reparent(out, it.id, frameId) }
        return out
    }

    /**
     * Moves [layerId] by [worldDelta] on screen (world units), whatever its parent's scale and
     * rotation: the delta is re-expressed in the parent's space before it is added to the offset.
     */
    fun moveBy(layers: List<Layer>, layerId: String, worldDelta: Offset): List<Layer> {
        val layer = layers.firstOrNull { it.id == layerId } ?: return layers
        val parentT = layer.parentId?.let { id -> layers.firstOrNull { it.id == id } }
            ?.let { worldTransform(layers, it) } ?: WorldTransform.IDENTITY
        val local = parentT.invert(parentT.offset + worldDelta)
        return layers.map { if (it.id == layerId) it.copy(offset = it.offset + local) else it }
    }

    /**
     * Sets [frameId]'s box to [box] (world space; size before the frame's own scale). The frame's
     * children follow their constraints, or its auto layout, through [LayoutOps.applyResize].
     */
    fun setBox(layers: List<Layer>, frameId: String, box: Rect): List<Layer> {
        val frame = layers.firstOrNull { it.id == frameId } ?: return layers
        val parentT = frame.parentId?.let { id -> layers.firstOrNull { it.id == id } }
            ?.let { worldTransform(layers, it) } ?: WorldTransform.IDENTITY
        val centre = parentT.invert(Offset(box.centerX, box.centerY))
        val ws = worldTransform(layers, frame).scale.takeIf { it != 0f } ?: 1f
        val w = box.width / ws
        val h = box.height / ws
        val oldLocal = LayoutOps.localFrameRect(frame.layoutWidth, frame.layoutHeight)
        val newLocal = LayoutOps.localFrameRect(w, h)
        val resized = layers.map { if (it.id == frameId) it.copy(offset = centre, layoutWidth = w, layoutHeight = h) else it }
        return if (oldLocal == newLocal) resized else LayoutOps.applyResize(resized, frameId, oldLocal, newLocal)
    }
}
