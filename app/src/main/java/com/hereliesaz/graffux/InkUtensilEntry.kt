package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.design.GraffuxIcons

/**
 * The Jetpack Ink utensils as brush-list entries: rail item id, classifier, label and glyph, one per
 * [InkUtensil] and in its order. Every surface that lists brushes (the `grp.brushRail` today) reads
 * this rather than spelling the four out again, the same reason [TOOL_CATALOG] exists — so adding
 * a utensil to [InkUtensil] puts it in every brush list with no other change.
 *
 * Ids are derived from [InkUtensil.id], which is the stable identity; never key anything on the label.
 */
internal data class InkUtensilEntry(
    val utensil: InkUtensil,
    val railId: String,
    val classifier: String,
    val label: String,
    val icon: Int,
)

internal val INK_UTENSIL_CATALOG: List<InkUtensilEntry> = InkUtensil.entries.map { utensil ->
    InkUtensilEntry(
        utensil = utensil,
        railId = "brushRail.${utensil.id}",
        classifier = "brush.${utensil.id}",
        label = utensil.displayName,
        icon = when (utensil) {
            InkUtensil.PEN -> GraffuxIcons.PenInk
            InkUtensil.MARKER, InkUtensil.HIGHLIGHTER -> GraffuxIcons.Marker
            InkUtensil.DASHED_LINE -> GraffuxIcons.ShapeLine
        },
    )
}

/** The catalogue entry lit while `activeBrushName` is [name], or null when no Ink utensil is in hand. */
internal fun inkUtensilEntryForBrushName(name: String?): InkUtensilEntry? =
    InkUtensil.fromDisplayName(name)?.let { u -> INK_UTENSIL_CATALOG.first { it.utensil == u } }
