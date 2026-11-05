package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.isSurfaceViewImage

/**
 * The layer-level rules for the per-view paint images of a multi-view surface (spec/3d-space.md,
 * "Paint per view"): which layers they are, how duplicating a paint layer copies them, and how the
 * paint a layer made on the front sheet before a second view existed is carried onto the hull's
 * front patch. Pure; the view-model supplies the bitmaps and files.
 */
object SurfaceViewImages {
    /** [primaryId]'s view images, in layer order. */
    fun of(layers: List<Layer>, primaryId: String): List<Layer> = layers.filter { it.surfaceViewOf == primaryId }

    /**
     * The images of [source] copied for its duplicate [copy]: each pair is the source image and its
     * copy (a new id from [newId], linked to [copy], named after it, in the copy's group). The copy's
     * pixels, stroke log and file are the caller's to make; nothing here touches a bitmap.
     */
    fun duplicates(layers: List<Layer>, source: Layer, copy: Layer, newId: () -> String): List<Pair<Layer, Layer>> =
        of(layers, source.id).map { image ->
            image to image.copy(
                id = newId(),
                name = "${copy.name} (${image.surfaceView?.name?.lowercase() ?: "view"})",
                parentId = copy.parentId,
                surfaceViewOf = copy.id,
                bitmap = null,
            )
        }

    /**
     * The raster paint layers of [groupId]'s surface that hold pixels of their own and have no front
     * image yet: what a surface that gains its first second view should carry onto the front patch
     * ([carryOffset]). A layer that already has a front image keeps it (its sheet pixels stay unseen,
     * as before).
     */
    fun carryable(layers: List<Layer>, groupId: String): List<Layer> = layers.filter { leaf ->
        !leaf.isContainer && !leaf.isSurfaceViewImage && leaf.bitmap != null && leaf.shapes.isEmpty() &&
            leaf.spatial == null && leaf.model == null && leaf.adjustment == null &&
            SurfaceMath.ownerOf(layers, leaf.id)?.group?.id == groupId &&
            of(layers, leaf.id).none { it.surfaceView == SurfaceView.FRONT }
    }

    /**
     * How far to move a carried image's offset (the owner's leaf) so its pixels show on the hull exactly where
     * they showed on the sheet (the front image keeps the leaf's transform and frame, so the pixels are
     * copied as they are). The sheet's sweep distance `t` runs from the outline's plane; the hull's front
     * image measures it from the solid's near end ([SurfaceHull.paintPointAt]), a constant shift in
     * unrolled space, which this turns into the offset change that makes it (through the groups
     * between the owner and the leaf, however they are moved or turned). Null when the hull's front
     * outline carries no surface, or the chain cannot be solved.
     */
    fun carryOffset(owner: SurfaceOwner, hull: SurfaceHull, width: Float, height: Float): Pair<Float, Float>? {
        val shifted = hull.paintPointAt(SurfaceView.FRONT, 0f, 0f) ?: return null
        val chain = owner.innerChain
        if (chain.isEmpty()) return null
        fun at(dx: Float, dy: Float): Pair<Float, Float> {
            val moved = chain.dropLast(1) + chain.last().let { it.copy(offsetX = it.offsetX + dx, offsetY = it.offsetY + dy) }
            val p = SurfaceMath.boxToUnrolled(moved, width, height).apply(0f, 0f, 0f)
            return p[0] to p[1]
        }
        val o = at(0f, 0f)
        val jx = at(1f, 0f).let { (it.first - o.first) to (it.second - o.second) }
        val jy = at(0f, 1f).let { (it.first - o.first) to (it.second - o.second) }
        val det = jx.first * jy.second - jy.first * jx.second
        if (!(kotlin.math.abs(det) > 1e-9f)) return null
        val sx = shifted.first
        val sy = shifted.second
        val dx = (sx * jy.second - jy.first * sy) / det
        val dy = (jx.first * sy - sx * jx.second) / det
        return if (dx.isFinite() && dy.isFinite()) dx to dy else null
    }
}
