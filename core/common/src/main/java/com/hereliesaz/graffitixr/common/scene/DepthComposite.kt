@file:Suppress("CyclomaticComplexMethod", "ReturnCount", "MaxLineLength")

package com.hereliesaz.graffitixr.common.scene

import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.isFrame

/**
 * Which layers the GPU depth compositor draws, and where in the stack its pass sits
 * (spec/3d-space.md, "Per-pixel depth").
 *
 * The depth pass draws every member as a textured plane (or tube mesh) into one depth buffer, so
 * planes that cross occlude each other pixel by pixel. A depth buffer has no notion of a blend mode
 * or a clipping mask, so a layer needing one stays in the ordinary stack, drawn by HWUI through its
 * homography. Membership:
 * - a 3D strokes or 3D model layer, always, at its full placement through any groups (its tubes need depth
 *   against the planes, and a group's 3D placement moves them);
 * - a paint, text or vector layer that is on the scene path, composites normally (SrcOver, no clip
 *   to below, no adjustment) and sits only in groups that do too (normal blend, full opacity, and
 *   for a frame neither a clip nor a visible fill, which would otherwise paint over the child).
 *
 * With nothing on the scene path and no 3D strokes layer the plan is empty: the 2D fast path is
 * untouched. The pass is drawn once, at the stack position of the first top-level subtree holding a
 * member; layers not in it keep their stack order around it.
 */
data class DepthComposite(
    /** The members, in stack order (bottom first). */
    val memberIds: List<String>,
    /** The top-level layer at whose position the pass draws; null when the plan is empty. */
    val anchorTopLevelId: String?,
) {
    val isEmpty: Boolean get() = memberIds.isEmpty()

    operator fun contains(id: String): Boolean = id in memberSet

    private val memberSet: Set<String> by lazy { memberIds.toSet() }

    companion object {
        val EMPTY = DepthComposite(emptyList(), null)
        private const val ALPHA_SHIFT = 24

        fun plan(layers: List<Layer>, camera: SceneCamera): DepthComposite {
            val sceneIds = SceneRenderPath.sceneLayerIds(layers, camera)
            if (sceneIds.isEmpty() && layers.none { it.spatial != null || it.model != null }) return EMPTY
            val byId = layers.associateBy { it.id }
            fun ancestors(layer: Layer): List<Layer> {
                val out = ArrayList<Layer>()
                var p = layer.parentId?.let(byId::get)
                val seen = HashSet<String>()
                while (p != null && seen.add(p.id)) {
                    out += p
                    p = p.parentId?.let(byId::get)
                }
                return out
            }
            fun visible(layer: Layer) = layer.isVisible && ancestors(layer).all { it.isVisible }
            // A translucent group composites its children as one isolated image, which per-member
            // alpha cannot reproduce where they overlap; a filled frame draws its fill over any
            // child taken out of its subtree. Their descendants stay in the stack.
            fun plainGroup(g: Layer) = g.blendMode == BlendMode.SrcOver && g.opacity >= 1f &&
                !(g.isFrame && (g.clipsContent || ((g.frameFillArgb ?: 0L) ushr ALPHA_SHIFT) != 0L))
            val members = layers.filter { layer ->
                when {
                    layer.isContainer || !visible(layer) -> false
                    layer.spatial != null || layer.model != null -> true
                    layer.id !in sceneIds -> false
                    layer.adjustment != null -> false
                    layer.blendMode != BlendMode.SrcOver || layer.clipToLayerBelow -> false
                    layer.bitmap == null && layer.shapes.isEmpty() -> false
                    else -> ancestors(layer).all(::plainGroup)
                }
            }
            if (members.isEmpty()) return EMPTY
            fun topLevel(layer: Layer): String = ancestors(layer).lastOrNull()?.id ?: layer.id
            val tops = members.map(::topLevel).toSet()
            val anchor = layers.firstOrNull { it.parentId == null && it.id in tops }?.id
                ?: topLevel(members.first())
            return DepthComposite(members.map { it.id }, anchor)
        }

        /** The opacity a member composites with: its own times every enclosing group's. */
        fun effectiveOpacity(layers: List<Layer>, id: String): Float {
            val byId = layers.associateBy { it.id }
            var alpha = 1f
            var current = byId[id]
            val seen = HashSet<String>()
            while (current != null && seen.add(current.id)) {
                alpha *= current.opacity
                current = current.parentId?.let(byId::get)
            }
            return alpha
        }

        /**
         * Where a layer's bitmap ([bitmapWidth] x [bitmapHeight]) is drawn in its full-canvas box of
         * [boxWidth] x [boxHeight]: left, top, right, bottom. The layer's frame fits the box and the
         * bitmap sits at its origin in it, exactly as the canvas draws it.
         */
        fun imageRect(layer: Layer, bitmapWidth: Int, bitmapHeight: Int, boxWidth: Float, boxHeight: Float): FloatArray? {
            val frame = layer.frame(bitmapWidth, bitmapHeight)
            if (frame.width <= 0 || frame.height <= 0) return null
            val fit = minOf(boxWidth / frame.width, boxHeight / frame.height)
            val left = (boxWidth - frame.width * fit) / 2f + frame.originX * fit
            val top = (boxHeight - frame.height * fit) / 2f + frame.originY * fit
            return floatArrayOf(left, top, left + bitmapWidth * fit, top + bitmapHeight * fit)
        }

        /**
         * Painter's order for members: farthest centre first. The depth test makes crossing planes
         * exact; the order makes translucent pixels blend over what is behind them.
         */
        fun backToFront(depths: Map<String, Float>, ids: List<String>): List<String> =
            ids.sortedByDescending { depths[it] ?: 0f }
    }
}
