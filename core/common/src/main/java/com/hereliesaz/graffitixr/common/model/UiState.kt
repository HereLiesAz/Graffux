// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt
package com.hereliesaz.graffitixr.common.model

/**
 * Editor tool selection. NONE is the transform/move baseline (no active brush).
 */
enum class Tool {
    NONE, BRUSH, ERASER, BLUR, HEAL, BURN, DODGE, LIQUIFY, COLOR,
    /** Vector pen: a freeform drag traces an open PATH vector shape on a new layer. */
    PEN,
    /** Flood fill (Procreate's ColorDrop): a tap fills the contiguous region under the finger. */
    FILL,
    /**
     * Freehand selection (Procreate's lasso). A drag traces a region; every raster tool is then
     * confined to it until it is cleared. Dragging inside an existing selection moves its pixels.
     */
    SELECT,

    /**
     * Procreate's Clone: paints with pixels copied from somewhere else on the same layer. A source
     * point is picked first (a tap, while none is set); each stroke then samples the layer at a
     * fixed offset from where that stroke began, so the source travels with the brush and a whole
     * region can be duplicated by painting over it.
     */
    CLONE,

    /**
     * Photoshop's Sharpen brush: the inverse of [BLUR], painting local contrast back into the pixels
     * under the stroke via an unsharp mask.
     *
     * Appended at the end of the enum rather than filed next to [BLUR] on purpose. Tool travels over
     * the wire and through history as an **ordinal** — `Op.StrokeComplete.blendModeOrdinal`, read
     * back by `Tool.entries.getOrNull(...)` — so inserting a constant anywhere but the end would
     * silently re-map every stroke a peer or an older session had already recorded.
     */
    SHARPEN,

    /**
     * Photoshop's Smudge: drags the colour under the brush along the stroke, the way a finger drags
     * wet paint.
     *
     * Distinct from [BLUR], which the rail spent a long time calling "Smudge" while running a box
     * blur underneath. Blur averages a neighbourhood in place and moves nothing; smudge *carries*
     * colour, so it has a direction and leaves a tail. Neither substitutes for the other.
     *
     * Appended at the end for the same wire-ordinal reason as [SHARPEN].
     */
    SMUDGE
}

enum class ArScanMode {
    /** User-facing "Canvas": built-in feature-point cloud (no depth API required). */
    CLOUD_POINTS,
    /** User-facing "Mural": the engine is picked by [MuralMethod]. */
    MURAL
}

enum class MuralMethod {
    /** Gaussian Splatting (Mural v1) */
    VOXEL_HASH,
    /** Surface-Aware Mesh / t-SNE Unroller (Mural v2) */
    SURFACE_MESH,
    /** Point Cloud Anchor Offset Handoff (Mural v3) */
    CLOUD_OFFSET
}

enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}
