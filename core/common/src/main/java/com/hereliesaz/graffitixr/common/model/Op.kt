package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * The set of layer mutations that propagate over the co-op wire from host to guest.
 * Coarse-grained: brush strokes propagate only on completion, not per-sample.
 *
 * Editor mutations not mapping to one of these are not synced. New mutation types
 * require adding an Op variant.
 */
@Serializable
sealed class Op {
    @Serializable
    data class LayerAdd(val layer: Layer) : Op()

    @Serializable
    data class LayerRemove(val layerId: String) : Op()

    @Serializable
    data class LayerReorder(val newOrder: List<String>) : Op()

    @Serializable
    data class LayerTransform(val layerId: String, val matrix: List<Float>) : Op()

    @Serializable
    data class LayerPropsChange(val layerId: String, val props: LayerProps) : Op()

    @Serializable
    data class StrokeComplete(val layerId: String, val stroke: BrushStroke) : Op()

    /**
     * A layer's size as geometry rather than transform: its vector shapes and its declared layout
     * size. Sent for a resized frame and for every child its constraints resized, alongside
     * [LayerTransform] for their moved offsets — neither field fits in the transform encoding.
     */
    @Serializable
    data class LayerGeometry(
        val layerId: String,
        val shapes: List<VectorShape>,
        val layoutWidth: Float,
        val layoutHeight: Float,
    ) : Op()

    /**
     * A layer moved into or out of a container (a frame drop, a frame drawn over artwork, a frame
     * ungrouped). Sent alongside [LayerTransform], which carries the offset re-expressed in the new
     * parent's space.
     */
    @Serializable
    data class LayerParent(val layerId: String, val parentId: String?) : Op()

    @Serializable
    data class TextContentChange(val layerId: String, val text: String) : Op()

    /**
     * Wholesale replacement of a layer's pixels (PNG-encoded), for mutations that don't map to a
     * replayable [StrokeComplete] — Liquify warps and undo/redo of a layer's bitmap. The guest
     * decodes [png] and uses it as the layer's new base, dropping any local stroke history.
     */
    @Serializable
    data class LayerBitmapReplace(val layerId: String, val png: ByteArray) : Op() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LayerBitmapReplace) return false
            return layerId == other.layerId && png.contentEquals(other.png)
        }
        override fun hashCode(): Int = 31 * layerId.hashCode() + png.contentHashCode()
    }

    /**
     * The pixels of an adjustment layer's mask at [revision] (the Local tab paints them), which
     * [LayerPropsChange] names but cannot carry: one byte per world pixel, [width] x [height],
     * deflated. Sent before the props that first name the revision, and again when a mask stroke
     * finishes. A peer that predates this op drops it (an unknown type) and renders that adjustment
     * everywhere, as every peer did before it existed.
     */
    @Serializable
    data class AdjustmentMaskData(
        val layerId: String,
        val revision: Int,
        val width: Int,
        val height: Int,
        val deflated: ByteArray,
    ) : Op() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdjustmentMaskData) return false
            return layerId == other.layerId && revision == other.revision && width == other.width &&
                height == other.height && deflated.contentEquals(other.deflated)
        }
        override fun hashCode(): Int =
            (((layerId.hashCode() * 31 + revision) * 31 + width) * 31 + height) * 31 + deflated.contentHashCode()
    }
}
