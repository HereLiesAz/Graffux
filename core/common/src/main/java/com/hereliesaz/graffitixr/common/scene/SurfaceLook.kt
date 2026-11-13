package com.hereliesaz.graffitixr.common.scene

/**
 * How a 3D surface looks (spec/3d-space.md, "How the surface looks"): pure colour resolution, in ARGB
 * ints so it needs no UI types.
 */
object SurfaceLook {
    private const val ALPHA_SHIFT = 24
    private const val OPAQUE = 0xFF

    /**
     * The reference grid's colour: the one source for the 2D `InfiniteGrid` and the surface mesh. There
     * is no user setting for it yet.
     */
    const val GRID_ARGB: Int = 0x33808080

    /** The resolved fill: [argb], and whether the surface's edge shows against the workspace. */
    class Fill(val argb: Int, val edgeVisible: Boolean)

    /**
     * The surface fill: the canvas background colour, not the workspace (screen) colour, so the edge
     * shows. Null unless the canvas colour is opaque: a see-through canvas has no fill to show, and a
     * translucent one would blend twice over the page it already paints.
     */
    fun fill(canvasBackgroundArgb: Int, workspaceArgb: Int): Fill? =
        if ((canvasBackgroundArgb ushr ALPHA_SHIFT) != OPAQUE) {
            null
        } else {
            Fill(canvasBackgroundArgb, canvasBackgroundArgb != workspaceArgb)
        }

    /** The surface mesh colour, or null when the grid overlay is off. */
    fun meshArgb(gridOn: Boolean, gridArgb: Int = GRID_ARGB): Int? = if (gridOn) gridArgb else null
}
