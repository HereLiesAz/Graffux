@file:Suppress("MagicNumber") // Indices into the left, top, right, bottom box.

package com.hereliesaz.graffitixr.common.scene

import com.hereliesaz.graffitixr.common.model.LayerFrame
import kotlin.math.max
import kotlin.math.min

/** The painted pixels' bounds in a bitmap: [left], [top] inclusive, [right], [bottom] exclusive. */
data class ArtBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** What "Use a flat sheet" makes for a layer with art: where the sheet goes and the point the scene orbits. */
data class ArtSheetPlan(
    /** The sheet in scene units: the axis-aligned box of the art as seen now (sides at least [ArtSheet.MIN_SIZE]). */
    val rect: SceneRect,
)

/**
 * The art-sized flat sheet (spec/3d-space.md, "The art-sized sheet"): the flat surface a 2D layer WITH ART gets when it
 * is turned into a 3D layer. Pure Kotlin.
 */
object ArtSheet {
    /** The smallest sheet side, in scene units, so a dot of paint still gets a sheet a hand can turn. */
    const val MIN_SIZE = 16f

    /**
     * Added to each side of the sheet so float rounding in the art's transform can never
     * leave the art's first or last pixel a ulp past the sheet's edge:
     * far below a pixel, far above a ulp at canvas sizes.
     */
    const val EDGE_PAD = 0.005f

    /**
     * How far behind a flat sheet's centre the orbit point is ([OrbitPivot.ofSurfaces]): half the median of the
     * sheet's width and height. The median of
     * two values is their average, so `((w + h) / 2) / 2`.
     */
    fun pivotDepth(width: Float, height: Float): Float = (width + height) / 2f / 2f

    /**
     * A layer's art box in the layer's own box coordinates (canvas pixels, before its transform): [bounds] are the
     * painted pixels' bounds in BITMAP pixels (right and bottom exclusive), [frame] holds the bitmap's pixel (0, 0)
     * at its origin, and the frame fits the [canvasWidth] x [canvasHeight] canvas uniformly, centred (LayerFrame).
     * Returns left, top, right, bottom, or null for an unusable frame or canvas.
     */
    fun boxOf(bounds: ArtBounds, frame: LayerFrame, canvasWidth: Float, canvasHeight: Float): FloatArray? {
        val usable = frame.width > 0 && frame.height > 0 && canvasWidth > 0f && canvasHeight > 0f &&
            canvasWidth.isFinite() && canvasHeight.isFinite() && bounds.right > bounds.left &&
            bounds.bottom > bounds.top
        if (!usable) return null
        val fit = min(canvasWidth / frame.width, canvasHeight / frame.height)
        val offX = (canvasWidth - frame.width * fit) / 2f
        val offY = (canvasHeight - frame.height * fit) / 2f
        return floatArrayOf(
            (bounds.left + frame.originX) * fit + offX,
            (bounds.top + frame.originY) * fit + offY,
            (bounds.right + frame.originX) * fit + offX,
            (bounds.bottom + frame.originY) * fit + offY,
        )
    }

    /**
     * The plan for art whose box is [box] (left, top, right, bottom in the layer's box coordinates, see [boxOf]) on
     * a layer with the flat pose [pose] (its offset, scale and Z turn; a pose placed in 3D has no plan) on a
     * [canvasWidth] x [canvasHeight] canvas.
     *
     * The box's four corners go through the layer's own transform (`SceneMath.model`), which is where the art is
     * seen; the sheet is the axis-aligned box of those corners, so a layer that is only offset and scaled gets
     * its art box (grown by [EDGE_PAD] on each side) and a turned one gets the smallest axis-aligned sheet that
     * holds it. A side shorter than
     * [MIN_SIZE] grows about its centre. The orbit point is not part of the plan: [OrbitPivot] derives it from the
     * sheet. Null for a pose placed in 3D, a box or pose that is not finite, or an
     * unusable canvas.
     */
    fun plan(box: FloatArray, pose: PlanePose, canvasWidth: Float, canvasHeight: Float): ArtSheetPlan? {
        val ok = box.size == 4 && box.all { it.isFinite() } && !pose.isPlacedIn3D &&
            canvasWidth > 0f && canvasHeight > 0f &&
            pose.scale.isFinite() && pose.scale > 0f && pose.offsetX.isFinite() && pose.offsetY.isFinite() &&
            pose.rotationZ.isFinite()
        if (!ok) return null
        val m = SceneMath.model(pose, canvasWidth, canvasHeight)
        var lx = Float.MAX_VALUE
        var ly = Float.MAX_VALUE
        var hx = -Float.MAX_VALUE
        var hy = -Float.MAX_VALUE
        val corners = listOf(box[0] to box[1], box[2] to box[1], box[0] to box[3], box[2] to box[3])
        for ((u, v) in corners) {
            val p = m.apply(u, v, 0f)
            lx = min(lx, p[0]); hx = max(hx, p[0]); ly = min(ly, p[1]); hy = max(hy, p[1])
        }
        val w = max(hx - lx, MIN_SIZE)
        val h = max(hy - ly, MIN_SIZE)
        val cx = (lx + hx) / 2f
        val cy = (ly + hy) / 2f
        val rect = SceneRect(cx - w / 2f - EDGE_PAD, cy - h / 2f - EDGE_PAD, w + 2 * EDGE_PAD, h + 2 * EDGE_PAD)
        return if (listOf(rect.x, rect.y, w, h).all { it.isFinite() }) ArtSheetPlan(rect) else null
    }
}
