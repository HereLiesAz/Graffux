package com.hereliesaz.graffux

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Keyline sizes, in px, for small · medium · HERO · medium · small across a strip. */
internal data class CarouselKeylineSizes(val hero: Float, val medium: Float, val small: Float) {
    /** Anything at or below this is drawn as a small card; it sits between small and medium. */
    val smallCeiling: Float get() = (small + medium) / 2f
}

private const val HERO_FRACTION = 0.42f
private const val MEDIUM_FRACTION = 0.16f
private const val VISIBLE_GAPS = 4

/**
 * Sizes for the five visible keylines of a strip [width] px wide with [spacing] px between items:
 * the hero takes 42%, each medium 16%, and the two small cards split what is left, so the five
 * always fill the strip exactly and stay symmetric about the centre.
 */
internal fun carouselKeylineSizes(width: Float, spacing: Float): CarouselKeylineSizes {
    val hero = width * HERO_FRACTION
    val medium = width * MEDIUM_FRACTION
    val small = ((width - VISIBLE_GAPS * spacing - hero - 2 * medium) / 2f).coerceAtLeast(1f)
    return CarouselKeylineSizes(hero, medium, small)
}

/** Where one item sits: its centre x and its width, both in px. */
internal data class CarouselSlot(val center: Float, val size: Float)

/** Keylines either side of the centre, including one off-screen beyond each small card. */
private const val OUTER_KEYLINE = 3

private fun keyline(k: Int, width: Float, spacing: Float, s: CarouselKeylineSizes): CarouselSlot {
    val n = abs(k)
    val sign = if (k < 0) -1f else 1f
    val mid = width / 2f
    val toMedium = s.hero / 2f + spacing + s.medium / 2f
    val toSmall = toMedium + s.medium / 2f + spacing + s.small / 2f
    return when (n) {
        0 -> CarouselSlot(mid, s.hero)
        1 -> CarouselSlot(mid + sign * toMedium, s.medium)
        2 -> CarouselSlot(mid + sign * toSmall, s.small)
        else -> CarouselSlot(mid + sign * (toSmall + (n - 2) * (s.small + spacing)), s.small)
    }
}

/**
 * The slot of an item [offset] items from the centre (0 = the hero, ±1 medium, ±2 small; fractions
 * while scrolling interpolate between neighbouring keylines, so sizes change smoothly and the row
 * stays symmetric about the centre at every rest position).
 */
internal fun carouselSlot(offset: Float, width: Float, spacing: Float): CarouselSlot {
    val sizes = carouselKeylineSizes(width, spacing)
    val o = offset.coerceIn(-OUTER_KEYLINE.toFloat(), OUTER_KEYLINE.toFloat())
    val lo = floor(o).toInt()
    val hi = (lo + 1).coerceAtMost(OUTER_KEYLINE)
    val t = o - lo
    val a = keyline(lo, width, spacing, sizes)
    val b = keyline(hi, width, spacing, sizes)
    return CarouselSlot(a.center + (b.center - a.center) * t, a.size + (b.size - a.size) * t)
}

/** A release faster than this many items per second advances one item in its direction. */
private const val FLING_ITEMS_PER_SEC = 1.5f

/**
 * Where the row settles after a drag: [position] is the fractional index at the centre, [velocity]
 * in items/s (positive = towards higher indices). One item per gesture, like M3's single-advance
 * fling: a fling moves to the next item that way, a slow release to the nearest.
 */
internal fun carouselSnapTarget(position: Float, velocity: Float, count: Int): Int {
    val target = when {
        velocity >= FLING_ITEMS_PER_SEC -> floor(position).toInt() + 1
        velocity <= -FLING_ITEMS_PER_SEC -> kotlin.math.ceil(position).toInt() - 1
        else -> position.roundToInt()
    }
    return target.coerceIn(0, (count - 1).coerceAtLeast(0))
}
