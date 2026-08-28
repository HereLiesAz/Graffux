package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned pixel bounds a set of dabs could have painted, in bitmap space (roadmap item 16:
 * dirty-region tracking). [left]/[top] are inclusive, [right]/[bottom] exclusive -- the same
 * convention `android.graphics.Rect` uses.
 *
 * This is a foundational, read-only utility: it computes bounds only, and nothing in the app
 * consumes it yet. It does NOT touch undo storage -- [com.hereliesaz.graffitixr.feature.editor
 * .EditHistory]/`LayerStore` remain whole-bitmap-snapshot based, unchanged by this. Tile-based
 * undo storage (the other half of item 16) is a much larger, correctness-sensitive rewrite of
 * that storage layer and is NOT started; see the roadmap doc for why that wasn't attempted here.
 */
data class DirtyRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(other: DirtyRegion): DirtyRegion = DirtyRegion(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    /** Intersects with `[0, width) x [0, height)`. May be [isEmpty] if entirely outside. */
    fun clampTo(canvasWidth: Int, canvasHeight: Int): DirtyRegion = DirtyRegion(
        left.coerceIn(0, canvasWidth),
        top.coerceIn(0, canvasHeight),
        right.coerceIn(0, canvasWidth),
        bottom.coerceIn(0, canvasHeight),
    )

    companion object {
        /**
         * The union bound of every dab in [dabs], each padded by its own radius -- and, if
         * present, its secondary (masked/dual) tip's radius too, so a masked-brush stroke's full
         * painted extent is covered, not just its primary tip. Null when [dabs] is empty.
         *
         * Radius alone (not radius*tipRatio, nor rotation) is used as the per-dab pad: any actual
         * painted shape -- round, squished by tipRatio, or rotated -- is inscribed within a circle
         * of that radius from the dab's center, so this is always a conservative superset of the
         * true painted pixels, the same bounding-box convention
         * `VulkanStampEngine::stampDabs()`'s dispatch-region optimization already uses natively.
         */
        fun fromDabs(dabs: List<Dab>): DirtyRegion? {
            if (dabs.isEmpty()) return null
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (d in dabs) {
                left = min(left, d.x - d.radius)
                top = min(top, d.y - d.radius)
                right = max(right, d.x + d.radius)
                bottom = max(bottom, d.y + d.radius)
                d.mask?.let { m ->
                    left = min(left, m.x - m.radius)
                    top = min(top, m.y - m.radius)
                    right = max(right, m.x + m.radius)
                    bottom = max(bottom, m.y + m.radius)
                }
            }
            return DirtyRegion(
                floor(left).toInt(),
                floor(top).toInt(),
                ceil(right).toInt(),
                ceil(bottom).toInt(),
            )
        }
    }
}
