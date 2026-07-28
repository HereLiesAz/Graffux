package com.hereliesaz.graffitixr.common.mesh

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Turning a drag across a model into dabs on its texture.
 *
 * [MeshPicker] answers where a single touch lands in UV space; this answers what to do with two
 * consecutive answers. Painting only the picked points leaves a dotted trail whenever the finger
 * moves faster than the frame rate, so the gap has to be filled — but filling it naively is worse
 * than leaving it, because of seams.
 *
 * A UV map cuts the surface into islands laid out side by side in the texture. Two points that are
 * neighbours on the model can be at opposite corners of the texture if the stroke crossed a cut, and
 * a line drawn between them paints a stripe across everything in between — parts of the model the
 * user never touched. So a large UV jump is treated as a break in the stroke rather than something
 * to interpolate through. See [SEAM_THRESHOLD].
 *
 * Pure arithmetic, no Android: the awkward part of surface painting is decided here where it can be
 * tested, and the caller only has to stamp a brush at each returned point.
 */
object TexturePaint {

    /** A brush dab, in texture pixels. */
    data class Stamp(val x: Float, val y: Float)

    /**
     * How far a UV coordinate may move between two consecutive picks before the step is read as
     * having crossed a seam rather than travelled across the surface.
     *
     * A quarter of the texture is a lot of ground for one frame of a finger drag to cover, and
     * nothing at all for a jump between islands. The two failure modes are not symmetric: guessing
     * "seam" wrongly drops one connecting segment out of a stroke, which is nearly invisible;
     * guessing "travel" wrongly paints a stripe across unrelated parts of the model, which the user
     * has to undo.
     */
    const val SEAM_THRESHOLD = 0.25f

    /**
     * A ceiling on the dabs one segment may produce, so a huge texture with a fine brush can't
     * stall a frame. Reaching it means the segment is being drawn more coarsely than asked, which
     * is a far better outcome than dropping frames mid-stroke.
     */
    private const val MAX_STAMPS_PER_SEGMENT = 512

    /**
     * The pixel a UV coordinate names in a texture of [width]×[height].
     *
     * UVs outside the unit square are wrapped, not clamped — a model whose map tiles a texture is
     * a normal thing, and clamping would smear every repeat into the edge row.
     *
     * V is used as-is rather than flipped: the bitmap's first row is uploaded as the texture's
     * first row, so a shader sampling at v reads the same row this paints into. The flip that does
     * matter happened once already, in the OBJ parser, converting the file's convention to GL's.
     */
    fun toPixel(u: Float, v: Float, width: Int, height: Int): Stamp =
        Stamp(wrapUnit(u) * width, wrapUnit(v) * height)

    /**
     * Whether the step from (`fromU`, `fromV`) to (`toU`, `toV`) crossed a UV seam — i.e. the two
     * points are unrelated places in the texture and must not be joined.
     */
    fun isSeamJump(fromU: Float, fromV: Float, toU: Float, toV: Float): Boolean =
        abs(toU - fromU) > SEAM_THRESHOLD || abs(toV - fromV) > SEAM_THRESHOLD

    /**
     * The dabs that paint the step from (`fromU`, `fromV`) to (`toU`, `toV`) on a [width]×[height]
     * texture, one every [spacingPx] pixels.
     *
     * Always ends at the destination, so the paint is under the finger when it stops. Across a seam
     * only the destination is returned: the finger is genuinely there, it just didn't get there
     * through any of the texture in between.
     */
    fun stamps(
        fromU: Float,
        fromV: Float,
        toU: Float,
        toV: Float,
        width: Int,
        height: Int,
        spacingPx: Float,
    ): List<Stamp> {
        if (width <= 0 || height <= 0) return emptyList()
        val end = toPixel(toU, toV, width, height)
        if (isSeamJump(fromU, fromV, toU, toV)) return listOf(end)

        val start = toPixel(fromU, fromV, width, height)
        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = sqrt(dx * dx + dy * dy)
        val spacing = spacingPx.coerceAtLeast(0.5f)
        if (distance <= spacing) return listOf(end)

        val steps = ceil(distance / spacing).toInt().coerceAtMost(MAX_STAMPS_PER_SEGMENT)
        // Starts at 1: the previous call already stamped the segment's start point, and stamping it
        // again doubles the paint at every joint — visible with a soft or low-opacity brush as a
        // string of darker beads along the stroke.
        return (1..steps).map { i ->
            val t = i.toFloat() / steps
            Stamp(start.x + dx * t, start.y + dy * t)
        }
    }

    /** Wraps into `[0,1)`, handling negatives the way a tiled texture needs (floor, not truncate). */
    private fun wrapUnit(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return 0f
        val wrapped = value - floor(value)
        // floor() of a tiny negative can round to exactly 1f, which indexes one past the last texel.
        return if (wrapped >= 1f) 0f else wrapped
    }
}
