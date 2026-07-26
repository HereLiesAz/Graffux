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
    SELECT
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
