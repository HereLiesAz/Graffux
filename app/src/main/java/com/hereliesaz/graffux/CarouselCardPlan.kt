package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.design.GraffuxIcons

/**
 * What a card draws as the item's "tip" — the thing that identifies it at a glance, at every size.
 *
 * - [Stamp]: an installed (extension) brush. Its bundled tip bitmap, looked up by [Stamp.extensionId];
 *   the view-model renders a round tip from the brush's own hardness for one that bundles none.
 * - [Round]: a built-in or Brush Studio brush. None of those carry a tip bitmap (built-ins have no
 *   `shapePath`, and Brush Studio drops it), so the tip is the generated round dab the engine
 *   paints: a solid core out to `hardness`, fading to transparent at the edge, squashed by
 *   `tipRatio` and turned by `angleDeg`.
 * - [Ink]: a Jetpack Ink utensil, drawn as its representative tip glyph.
 * - [Glyph]: effects and options — their existing icon.
 */
internal sealed interface CarouselTip {
    data class Stamp(val extensionId: String) : CarouselTip
    data class Round(val hardness: Float, val tipRatio: Float, val angleDeg: Float) : CarouselTip
    data class Ink(val utensil: InkUtensil, val icon: Int) : CarouselTip
    data class Glyph(val icon: Int) : CarouselTip

    /**
     * An installed azphalt filter, tool or LUT: its extension's manifest `preview.image`, looked up
     * by [extensionId], drawn untinted; [fallbackIcon] when the manifest declares none.
     */
    data class Preview(val extensionId: String, val fallbackIcon: Int) : CarouselTip
}

internal fun carouselTip(entry: CarouselEntry): CarouselTip = when (val action = entry.action) {
    is CarouselAction.ExtensionBrush -> CarouselTip.Stamp(action.id)
    is CarouselAction.BuiltInBrush, is CarouselAction.CustomBrush -> {
        val brush = entry.brush
        if (brush == null) {
            CarouselTip.Glyph(entry.icon ?: GraffuxIcons.Brush)
        } else {
            CarouselTip.Round(brush.hardness.coerceIn(0f, 1f), brush.tipRatio, brush.angle)
        }
    }
    is CarouselAction.InkUtensilPick -> CarouselTip.Ink(action.utensil, entry.icon ?: GraffuxIcons.PenInk)
    is CarouselAction.ExtensionContribution ->
        CarouselTip.Preview(action.extensionId, entry.icon ?: GraffuxIcons.FilterGallery)
    is CarouselAction.ExtensionLut -> CarouselTip.Preview(action.extensionId, entry.icon ?: GraffuxIcons.ColorLookup)
    else -> CarouselTip.Glyph(entry.icon ?: GraffuxIcons.BrushSettings)
}

/**
 * The three card sizes of the hero carousel. Which one a card is comes from the carousel's own
 * keyline strategy (the size it laid the item out at), never from its index: see [carouselTier].
 */
internal enum class CarouselTier { HERO, MEDIUM, SMALL }

/** How much slack, as a fraction of the hero size, still counts as "at the hero keyline". */
private const val HERO_SIZE_TOLERANCE = 0.9f

/**
 * The tier of an item the carousel laid out at [size] px, where [heroSize] is the strategy's large
 * item size and [smallMax] its largest small-item size. Items mid-scroll interpolate between
 * keylines, so this is a banding, not an equality: close to the hero size is the hero, at or below
 * the small ceiling is small, anything between is medium.
 */
internal fun carouselTier(size: Float, heroSize: Float, smallMax: Float): CarouselTier = when {
    heroSize > 0f && size >= heroSize * HERO_SIZE_TOLERANCE -> CarouselTier.HERO
    size <= smallMax -> CarouselTier.SMALL
    else -> CarouselTier.MEDIUM
}

/**
 * Whether a card wears the accent highlight: only the hero, and only while its entry is the active
 * one ([CarouselEntry.selected], the same state the rail reads), never by position alone. A
 * tap-only option or an installed effect the row merely settled on is the hero but not active.
 */
internal fun carouselHeroHighlighted(entry: CarouselEntry, tier: CarouselTier): Boolean =
    tier == CarouselTier.HERO && entry.selected

/** What a card shows beside its tip visual, per tier. */
internal data class CarouselCardContent(val name: String?, val details: List<String>)

/** Hero: name and details. Medium: name under the tip. Small: the tip alone. */
internal fun carouselCardContent(entry: CarouselEntry, tier: CarouselTier): CarouselCardContent = when (tier) {
    CarouselTier.HERO -> CarouselCardContent(entry.label, carouselHeroDetails(entry))
    CarouselTier.MEDIUM -> CarouselCardContent(entry.label, emptyList())
    CarouselTier.SMALL -> CarouselCardContent(null, emptyList())
}

private const val PERCENT = 100

private fun percent(value: Float): String = "${(value * PERCENT).toInt()}%"

/**
 * The hero card's extra lines: the item's family, then the key parameters that are actually known
 * for it. Nothing is estimated — an installed brush's parameters are not in the carousel's inputs,
 * so it shows its family only.
 */
internal fun carouselHeroDetails(entry: CarouselEntry): List<String> = when (val action = entry.action) {
    is CarouselAction.BuiltInBrush -> listOf("Built-in · round tip") + brushParams(entry.brush)
    is CarouselAction.CustomBrush -> listOf("Brush Studio · round tip") + brushParams(entry.brush)
    is CarouselAction.ExtensionBrush -> listOf("Installed · stamp brush")
    is CarouselAction.InkUtensilPick -> listOf("Jetpack Ink", inkTrait(action.utensil))
    is CarouselAction.PickTool -> listOf("Effect")
    is CarouselAction.StabilizerLevel -> listOf("Option · stabilizer")
    is CarouselAction.Stabilizer -> listOf("Option · stabilizer algorithm")
    is CarouselAction.SmudgeMode -> listOf("Option · smudge mode")
    is CarouselAction.SelectShape -> listOf("Option · selection shape")
    CarouselAction.OpenToolOptions -> listOf("Every tool setting")
    is CarouselAction.ExtensionContribution, is CarouselAction.ExtensionLut -> extensionDetails(entry.extensionEffect)
}

/** An installed effect's hero lines: its extension and kind, then the manifest's description. */
private fun extensionDetails(effect: ExtensionEffect?): List<String> = if (effect == null) {
    listOf("Installed")
} else {
    listOfNotNull("${effect.extensionName} · ${effect.kind.label}", effect.description?.takeIf { it.isNotBlank() })
}

private fun brushParams(brush: AzphaltBrush?): List<String> = if (brush == null) {
    emptyList()
} else {
    listOf("Hardness ${percent(brush.hardness)} · Spacing ${percent(brush.spacing)}")
}

/** Each utensil's defining trait, as `InkUtensil`'s own KDoc describes its `StockBrushes` family. */
private fun inkTrait(utensil: InkUtensil): String = when (utensil) {
    InkUtensil.PEN -> "Pressure-sensitive width"
    InkUtensil.MARKER -> "Constant width, opaque"
    InkUtensil.HIGHLIGHTER -> "Chisel tip, translucent"
    InkUtensil.DASHED_LINE -> "Dashed, constant width"
}

