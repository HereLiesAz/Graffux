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
    /**
     * Every layer the depth pass paints on a group surface (spec/3d-space.md, "Group surfaces").
     * Adjustment layers, and blend-mode or clipped layers on a flat surface, are not in it: they
     * draw in the ordinary stack.
     */
    val surfaceIds: Set<String> = emptySet(),
    /**
     * The translucent surface groups the pass composites as one unit (spec/3d-space.md, "Group
     * surfaces"): their layers draw into an offscreen target first, which then takes the group's
     * opacity. A group at full opacity is not here: its layers draw straight into the scene.
     */
    val isolatedGroups: List<IsolatedGroup> = emptyList(),
) {
    /**
     * A profiled group whose layers composite as one image. [memberIds] are the paint layers drawn
     * on its surface, in stack order (bottom first); [opacity] is the group's own opacity times that
     * of every group above it, which is what the merged image is drawn with. Inside, each layer
     * keeps its own opacity times that of the groups between it and this one ([opacityBelow]).
     */
    data class IsolatedGroup(val groupId: String, val memberIds: List<String>, val opacity: Float)

    val isEmpty: Boolean get() = memberIds.isEmpty()

    operator fun contains(id: String): Boolean = id in memberSet

    private val memberSet: Set<String> by lazy { memberIds.toSet() }

    /** The isolated group [memberId] draws inside, or null when it draws straight into the scene. */
    fun isolationOf(memberId: String): IsolatedGroup? = isolatedGroups.firstOrNull { memberId in it.memberIds }

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
            // Paint the depth pass draws on a surface. Not an adjustment layer: it has no image of
            // its own and works on what is beneath it on screen, so it stays in the stack. Nor, on
            // a FLAT surface (one plane), a layer needing a blend mode or a clip, which a depth
            // buffer cannot do: it draws through its plane in the stack, exactly as before surfaces.
            // The same goes for a layer in a group with a blend mode: the group blends with what is
            // under it on screen, which only the stack can reach. (On a curved surface there is no
            // stack path, so the group's blend is not applied.)
            val onSurface = SurfaceMath.surfaceLayerIds(layers).filterTo(HashSet()) { id ->
                val layer = byId.getValue(id)
                val needsStack = layer.blendMode != BlendMode.SrcOver || layer.clipToLayerBelow ||
                    ancestors(layer).any { it.blendMode != BlendMode.SrcOver }
                layer.adjustment == null &&
                    !(needsStack && SurfaceMath.ownerOf(byId, id)?.profile?.isFlat == true)
            }
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
                    // Paint on a surface has no plane to draw it by: the pass draws it on the
                    // surface's mesh, in stack order, whatever its blend mode (normal) or group.
                    layer.id in onSurface ->
                        layer.bitmap != null || layer.shapes.isNotEmpty() ||
                            // On a multi-view surface the paint is in its per-view images.
                            layers.any { it.surfaceViewOf == layer.id && it.bitmap != null }
                    layer.blendMode != BlendMode.SrcOver || layer.clipToLayerBelow -> false
                    layer.bitmap == null && layer.shapes.isEmpty() -> false
                    else -> ancestors(layer).all(::plainGroup)
                }
            }
            if (members.isEmpty()) return if (onSurface.isEmpty()) EMPTY else DepthComposite(emptyList(), null, onSurface)
            fun topLevel(layer: Layer): String = ancestors(layer).lastOrNull()?.id ?: layer.id
            val tops = members.map(::topLevel).toSet()
            val anchor = layers.firstOrNull { it.parentId == null && it.id in tops }?.id
                ?: topLevel(members.first())
            // Paint on a surface whose group is translucent: the group's layers merge first, then
            // fade together, as on the flat canvas. Ink and models are not paint on the surface.
            val isolated = LinkedHashMap<String, MutableList<String>>()
            for (m in members.filter { it.id in onSurface && it.spatial == null && it.model == null }) {
                val group = SurfaceMath.ownerOf(byId, m.id)?.group?.takeIf { effectiveOpacity(byId, it.id) < 1f } ?: continue
                isolated.getOrPut(group.id) { ArrayList() } += m.id
            }
            return DepthComposite(
                members.map { it.id }, anchor, onSurface,
                isolated.map { (id, ids) -> IsolatedGroup(id, ids, effectiveOpacity(byId, id)) },
            )
        }

        private fun effectiveOpacity(byId: Map<String, Layer>, id: String): Float {
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
         * A layer's opacity inside the isolated group [groupId]: its own times that of the groups
         * between it and [groupId] (the group itself, and those above it, are drawn once, over the
         * merged image).
         */
        fun opacityBelow(layers: List<Layer>, id: String, groupId: String): Float {
            val byId = layers.associateBy { it.id }
            var alpha = 1f
            var current = byId[id]
            val seen = HashSet<String>()
            while (current != null && current.id != groupId && seen.add(current.id)) {
                alpha *= current.opacity
                current = current.parentId?.let(byId::get)
            }
            return alpha
        }

        /** The opacity a member composites with: its own times every enclosing group's. */
        fun effectiveOpacity(layers: List<Layer>, id: String): Float = effectiveOpacity(layers.associateBy { it.id }, id)

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
