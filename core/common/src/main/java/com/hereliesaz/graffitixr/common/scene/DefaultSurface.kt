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
     * The sheet for a screen of [width] x [height] pixels: 3/4 of the short side by 2/3 of the long side, in
     * either orientation (portrait 3/4 of the width by 2/3 of the height, landscape 2/3 of the width by 3/4
     * of the height). A square counts as portrait. The view is [zoom] times the canvas ([zoom] 1 is the
     * identity view), so the sheet is that share of what is on screen: its size in canvas units is divided
     * by [zoom]. It is centred on ([centerX], [centerY]), the canvas point at the middle of the screen
     * (the screen's own middle at the identity view). Null for a size or zoom that is not positive and finite.
     */
    fun rect(
        width: Float,
        height: Float,
        zoom: Float = 1f,
        centerX: Float = width / 2f,
        centerY: Float = height / 2f,
    ): SceneRect? {
        val sized = usable(width) && usable(height) && usable(zoom)
        if (!sized || !centerX.isFinite() || !centerY.isFinite()) return null
        val landscape = width > height
        val w = width * (if (landscape) LONG_FRACTION else SHORT_FRACTION) / zoom
        val h = height * (if (landscape) SHORT_FRACTION else LONG_FRACTION) / zoom
        return SceneRect(centerX - w / 2f, centerY - h / 2f, w, h)
    }

    /**
     * [rect] as a group surface profile, in the group's box-local space ([groupChain] as for
     * [SurfaceMath.capture], built for a [canvasWidth] x [canvasHeight] canvas; empty for a group with no pose):
     * its top edge across the plane z = 0, swept down the screen (+y) for exactly the sheet's height
     * ([SurfaceProfile.reach]), so the surface's box is the rectangle; the orbit pivot is `n` behind its centre
     * ([OrbitPivot.ofSurfaces]).
     * Null when [rect] is null or the chain is unusable.
     */
    fun profile(
        rect: SceneRect?,
        canvasWidth: Float,
        canvasHeight: Float,
        groupChain: List<PlanePose>,
    ): SurfaceProfile? {
        val toLocal = if (rect == null) null else SceneMath.chainModel(groupChain, canvasWidth, canvasHeight).inverse()
        if (rect == null || toLocal == null) return null
        val a = toLocal.apply(rect.x, rect.y, 0f)
        val b = toLocal.apply(rect.x + rect.width, rect.y, 0f)
        val sweep = toLocal.applyVector(0f, 1f, 0f)
        val scale = kotlin.math.sqrt(sweep[0] * sweep[0] + sweep[1] * sweep[1] + sweep[2] * sweep[2])
        val unit = SceneMath.normalize(sweep)
        val profile = SurfaceProfile(a.toList() + b.toList(), unit[0], unit[1], unit[2], reach = rect.height * scale)
        return profile.takeIf { it.isValid }
    }

    private fun usable(v: Float) = v > 0f && v.isFinite()
}
