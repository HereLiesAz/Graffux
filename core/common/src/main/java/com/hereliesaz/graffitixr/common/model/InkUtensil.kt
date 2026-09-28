package com.hereliesaz.graffitixr.common.model

/**
 * The Jetpack Ink (`androidx.ink`) art utensils: one per stock `BrushFamily` the pinned Ink version
 * exposes as public API. Each is its own entry in the brush list, beside the bundled Azphalt brushes,
 * custom brushes and installed extensions — not a setting that changes what the round brush does.
 *
 * Ink 1.0.0's `StockBrushes` offers exactly these four public families, each with only a `V1`
 * version (`LATEST` == `V1`). It also carries `pencilUnstable`, which is `@RestrictTo(LIBRARY_GROUP)`
 * and needs a client-supplied texture, and `emojiHighlighter`, which needs a client-supplied emoji
 * texture — neither is a stock utensil an app can use as-is, so neither is listed. 1.0.0 is also the
 * newest stable Ink release; 1.1.0 is still alpha.
 *
 * Kept free of any Ink import so the catalogue, the co-op wire id and the brush list can be read
 * (and unit-tested) without Ink's native library; the id → `BrushFamily` mapping lives in
 * `feature/editor/.../ink/InkStrokes.family`.
 *
 * [id] is the stable identity: it is what the rail item, `StrokeCommand.inkUtensil`, the co-op
 * [BrushStroke.inkUtensilId] and `EditorUiState.activeBrushName` lookups key on. Never rename one.
 * [displayName] is what the user sees and is also written to `activeBrushName`, like every other
 * brush's name.
 *
 * None of these take the round brush's soft edge (`brushFeathering`): each stock family has its own
 * fixed tip, and softening it would make it not that utensil. Size, colour, opacity and the
 * stabilizer apply to all four.
 */
enum class InkUtensil(val id: String, val displayName: String) {
    /** `StockBrushes.pressurePen()` — pressure → width, a ballpoint/fineliner. */
    PEN("ink.pen", "Ink Pen"),

    /** `StockBrushes.marker()` — constant width, opaque, overlaps flatten. */
    MARKER("ink.marker", "Ink Marker"),

    /** `StockBrushes.highlighter()` — chisel tip, translucent, self-overlap doesn't darken. */
    HIGHLIGHTER("ink.highlighter", "Ink Highlighter"),

    /** `StockBrushes.dashedLine()` — a constant-width dashed stroke. */
    DASHED_LINE("ink.dashed", "Ink Dashed Line"),
    ;

    companion object {
        /** The utensil with [id], or null for an unknown/absent id (an older or newer peer's). */
        fun fromId(id: String?): InkUtensil? = entries.firstOrNull { it.id == id }

        /** The utensil whose [displayName] is [name] — what `activeBrushName` holds while one is in hand. */
        fun fromDisplayName(name: String?): InkUtensil? = entries.firstOrNull { it.displayName == name }
    }
}
