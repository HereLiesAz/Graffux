package com.hereliesaz.graffux

import com.hereliesaz.graffux.carousel.CarouselAlignment
import com.hereliesaz.graffux.carousel.KeylineList
import com.hereliesaz.graffux.carousel.emptyKeylineList
import com.hereliesaz.graffux.carousel.keylineListOf
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The row's continuous position (the fractional index in the hero slot), read off the widths M3's
 * carousel gave its items. `CarouselState` only exposes the integer `currentItem`; the pager offset
 * behind it is internal. Each composed item reports its mask width [sizes] (index to px); [minSize]
 * and [maxSize] are the small and hero keyline widths. An item's "heroness" is how far its width is
 * from small towards hero (0..1), and the position is the heroness-weighted mean of the indices: at
 * rest exactly the hero's index, mid-scroll a fraction between the two items trading places.
 * Within [REST_EPSILON] of an index counts as resting on it. Null when nothing is wider than a
 * small card (not laid out yet).
 */
internal fun carouselHeroPosition(sizes: Map<Int, Float>, minSize: Float, maxSize: Float): Float? {
    val range = maxSize - minSize
    var weight = 0f
    var sum = 0f
    if (range > 0f) {
        for ((index, size) in sizes) {
            val h = ((size - minSize) / range).coerceIn(0f, 1f)
            weight += h
            sum += h * index
        }
    }
    // Sub-pixel rounding in the layout must not read as a sliver of scroll at rest.
    return (sum / weight).takeIf { weight > 0f }
        ?.let { p -> if (abs(p - p.roundToInt()) < REST_EPSILON) p.roundToInt().toFloat() else p }
}

private const val REST_EPSILON = 1e-3f

/**
 * Which previews the stroke area above the hero blends at a continuous row [position]: the entry
 * nearest the centre ([heroIndex]) and, while between two rest positions, the one the row is
 * nearest after it ([neighborIndex], null at rest). The neighbour shows at [neighborAlpha] (0 at
 * rest, 0.5 halfway) and the hero at [heroAlpha].
 */
internal data class CarouselHeroBlend(val heroIndex: Int, val neighborIndex: Int?, val neighborAlpha: Float) {
    val heroAlpha: Float get() = 1f - neighborAlpha
}

/** [CarouselHeroBlend] for a row of [count] items at [position] (clamped to the ends); null if empty. */
internal fun carouselHeroBlend(position: Float, count: Int): CarouselHeroBlend? {
    if (count <= 0) return null
    val p = position.coerceIn(0f, (count - 1).toFloat())
    val hero = p.roundToInt()
    val delta = p - hero
    val neighbor = when {
        delta > 0f -> hero + 1
        delta < 0f -> hero - 1
        else -> null
    }
    return CarouselHeroBlend(hero, neighbor, abs(delta))
}

/** Item [index]'s preview opacity at [position]: 1 in the hero slot, fading to 0 one item away. */
internal fun carouselPreviewAlpha(position: Float, index: Int): Float =
    (1f - abs(position - index)).coerceIn(0f, 1f)

/**
 * The resting widths (px) of the strip's centred, symmetric keylines: small · medium · HERO ·
 * medium · small. See [centredHeroSizes].
 */
internal data class CentredHeroSizes(val small: Float, val medium: Float, val hero: Float)

/**
 * Sizes five cards, small · medium · HERO · medium · small, to fill [availableSpace] exactly with
 * [itemSpacing] between them, in M3 Expressive's hero proportions (the forked `Keylines.kt`): a
 * small item aims for a third of the hero, clamped to [minSmall]..[maxSmall], and a medium item
 * sits halfway between small and hero (`multiBrowseKeylineList`'s target medium). Solving
 * `hero + 2·medium + 2·small + 4·spacing = space` with `medium = (hero + small) / 2` gives
 * `2·hero + 3·small = space − 4·spacing`. Null when there is no room for small < medium < hero.
 */
internal fun centredHeroSizes(
    availableSpace: Float,
    itemSpacing: Float,
    minSmall: Float,
    maxSmall: Float,
): CentredHeroSizes? {
    val space = availableSpace - CENTRED_GAPS * itemSpacing
    // Unclamped, small = hero / 3 makes the whole row 3 · hero, so small = space / 9.
    val small = (space / SMALL_PER_SPACE).coerceIn(minSmall, maxSmall)
    val hero = (space - SMALLS_PER_ROW * small) / 2f
    val medium = (hero + small) / 2f
    return CentredHeroSizes(small, medium, hero).takeIf { hero > medium && medium > small }
}

/**
 * The fork's [KeylineList] for [centredHeroSizes]: anchor · small · medium · HERO · medium ·
 * small · anchor, centre-aligned so the hero keyline sits in the middle of [availableSpace]. Empty
 * when the strip is too narrow for it.
 */
internal fun centredHeroKeylineList(
    availableSpace: Float,
    itemSpacing: Float,
    minSmall: Float,
    maxSmall: Float,
    anchorSize: Float,
): KeylineList {
    val sizes = centredHeroSizes(availableSpace, itemSpacing, minSmall, maxSmall) ?: return emptyKeylineList()
    return keylineListOf(availableSpace, itemSpacing, CarouselAlignment.Center) {
        add(anchorSize, isAnchor = true)
        add(sizes.small)
        add(sizes.medium)
        add(sizes.hero)
        add(sizes.medium)
        add(sizes.small)
        add(anchorSize, isAnchor = true)
    }
}

private const val CENTRED_GAPS = 4
private const val SMALLS_PER_ROW = 3
private const val SMALL_PER_SPACE = 9f
