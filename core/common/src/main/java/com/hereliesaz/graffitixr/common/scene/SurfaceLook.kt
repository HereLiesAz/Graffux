package com.hereliesaz.graffitixr.common.scene

/**
 * How a 3D surface looks (spec/3d-space.md, "How the surface looks"): pure colour resolution, in ARGB
 * ints so it needs no UI types.
 *
 * - Fill: the canvas background colour the user set (`SettingsRepository.backgroundColor`, shown in
 *   the editor as `canvasBackground`), not the workspace (screen) colour, so the surface's edge shows.
 *   A fully transparent canvas has no fill to show.
 * - Mesh: the reference-grid colour, only while the grid overlay is on.
 */
object SurfaceLook {
    /** The reference grid colour (no user setting yet). Keep equal to `InfiniteGrid`s default in EditorScreen. */
    private const val ALPHA_SHIFT = 24

    const val GRID_ARGB: Int = 0x33808080

    /** The surface fill, or null when the canvas background is fully transparent. */
    fun fillArgb(canvasBackgroundArgb: Int): Int? =
        if ((canvasBackgroundArgb ushr ALPHA_SHIFT) == 0) null else canvasBackgroundArgb

    /** The surface mesh colour, or null when the grid overlay is off. */
    fun meshArgb(gridOn: Boolean, gridArgb: Int = GRID_ARGB): Int? = if (gridOn) gridArgb else null
}
