package com.hereliesaz.graffux

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
