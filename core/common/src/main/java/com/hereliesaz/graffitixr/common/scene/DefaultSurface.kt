package com.hereliesaz.graffitixr.common.scene

/** A rectangle in canvas (scene) units: top-left corner and size. */
data class SceneRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The default surface (spec/3d-space.md, "The default surface"): the flat sheet a new 3D group gets when
 * the user chooses not to draw one. Pure Kotlin.
 */
object DefaultSurface {
    /** The sheet's share of the screen's short side. */
    const val SHORT_FRACTION = 0.75f

    /** The sheet's share of the screen's long side. */
    const val LONG_FRACTION = 2f / 3f

    /**
     * The sheet for a screen of [width] x [height] (canvas units): 3/4 of the short side by 2/3 of the long
     * side, in either orientation (portrait 3/4 of the width by 2/3 of the height, landscape 2/3 of the width
     * by 3/4 of the height), centred. A square screen counts as portrait. Null for a size that is not
     * positive and finite.
     */
    fun rect(width: Float, height: Float): SceneRect? {
        if (!usable(width) || !usable(height)) return null
        val landscape = width > height
        val w = width * (if (landscape) LONG_FRACTION else SHORT_FRACTION)
        val h = height * (if (landscape) SHORT_FRACTION else LONG_FRACTION)
        return SceneRect((width - w) / 2f, (height - h) / 2f, w, h)
    }

    /**
     * The sheet as a group surface profile, in the group's box-local space ([groupChain] as for
     * [SurfaceMath.capture]; empty for a group with no pose): its top edge across the plane z = 0, swept
     * down the screen (+y). Like every surface the sweep has no bound, so the sheet's height is the grid
     * extent (the longer of its sides plus the margin) and each layer's own pixels; the width is exact.
     * Null when the screen size or the chain is unusable.
     */
    fun profile(width: Float, height: Float, groupChain: List<PlanePose>): SurfaceProfile? {
        val r = rect(width, height)
        val toLocal = if (r == null) null else SceneMath.chainModel(groupChain, width, height).inverse()
        if (r == null || toLocal == null) return null
        val a = toLocal.apply(r.x, r.y, 0f)
        val b = toLocal.apply(r.x + r.width, r.y, 0f)
        val sweep = SceneMath.normalize(toLocal.applyVector(0f, 1f, 0f))
        val profile = SurfaceProfile(a.toList() + b.toList(), sweep[0], sweep[1], sweep[2])
        return profile.takeIf { it.isValid }
    }

    private fun usable(v: Float) = v > 0f && v.isFinite()
}
