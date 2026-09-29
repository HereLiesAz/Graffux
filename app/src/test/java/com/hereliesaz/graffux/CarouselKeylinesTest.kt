package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Test

class CarouselKeylinesTest {

    // 411dp at xxhdpi (3x), 6dp spacing.
    private val width = 1233f
    private val spacing = 18f
    private val sizes = carouselKeylineSizes(width, spacing)

    private fun tierAt(offset: Float) =
        carouselTier(carouselSlot(offset, width, spacing).size, sizes.hero, sizes.smallCeiling)

    @Test
    fun `at rest the row is small, medium, HERO, medium, small`() {
        assertEquals(
            listOf(CarouselTier.SMALL, CarouselTier.MEDIUM, CarouselTier.HERO, CarouselTier.MEDIUM, CarouselTier.SMALL),
            (-2..2).map { tierAt(it.toFloat()) },
        )
    }

    @Test
    fun `the five visible keylines fill the strip and are symmetric about the centre`() {
        val slots = (-2..2).map { carouselSlot(it.toFloat(), width, spacing) }
        val left = slots.first().center - slots.first().size / 2f
        val right = slots.last().center + slots.last().size / 2f
        assertEquals(0f, left, 0.5f)
        assertEquals(width, right, 0.5f)
        for (k in 1..2) {
            val l = carouselSlot(-k.toFloat(), width, spacing)
            val r = carouselSlot(k.toFloat(), width, spacing)
            assertEquals(width / 2f - l.center, r.center - width / 2f, 0.01f)
            assertEquals(l.size, r.size, 0.01f)
        }
        assertEquals(width / 2f, slots[2].center, 0.01f)
    }

    @Test
    fun `mid-scroll sizes interpolate between keylines`() {
        val half = carouselSlot(0.5f, width, spacing).size
        assertEquals((sizes.hero + sizes.medium) / 2f, half, 0.01f)
    }

    @Test
    fun `snap moves one item per fling, else to the nearest, within bounds`() {
        assertEquals(3, carouselSnapTarget(2.2f, velocity = 5f, count = 10))
        assertEquals(1, carouselSnapTarget(1.8f, velocity = -5f, count = 10))
        assertEquals(2, carouselSnapTarget(2.4f, velocity = 0.2f, count = 10))
        assertEquals(9, carouselSnapTarget(9f, velocity = 5f, count = 10))
        assertEquals(0, carouselSnapTarget(0f, velocity = -5f, count = 10))
    }

    @Test
    fun `at an exact position only the hero preview shows, fully opaque`() {
        assertEquals(CarouselHeroBlend(3, null, 0f), carouselHeroBlend(3f, count = 10))
        assertEquals(1f, carouselPreviewAlpha(3f, 3), 0f)
        assertEquals(0f, carouselPreviewAlpha(3f, 4), 0f)
        assertEquals(0f, carouselPreviewAlpha(3f, 2), 0f)
    }

    @Test
    fun `between positions the hero and its neighbour blend`() {
        val quarter = carouselHeroBlend(3.25f, count = 10)!!
        assertEquals(3, quarter.heroIndex)
        assertEquals(4, quarter.neighborIndex)
        assertEquals(0.25f, quarter.neighborAlpha, 1e-5f)
        assertEquals(0.75f, quarter.heroAlpha, 1e-5f)
        val back = carouselHeroBlend(2.8f, count = 10)!!
        assertEquals(3, back.heroIndex)
        assertEquals(2, back.neighborIndex)
        assertEquals(0.2f, back.neighborAlpha, 1e-5f)
    }

    @Test
    fun `halfway the two previews share the stroke evenly`() {
        val half = carouselHeroBlend(3.5f, count = 10)!!
        assertEquals(setOf(3, 4), setOf(half.heroIndex, half.neighborIndex))
        assertEquals(0.5f, half.neighborAlpha, 1e-5f)
        assertEquals(0.5f, carouselPreviewAlpha(3.5f, 3), 1e-5f)
        assertEquals(0.5f, carouselPreviewAlpha(3.5f, 4), 1e-5f)
    }

    @Test
    fun `the list ends clamp, and an empty row has no preview`() {
        assertEquals(CarouselHeroBlend(0, null, 0f), carouselHeroBlend(-0.4f, count = 10))
        assertEquals(CarouselHeroBlend(9, null, 0f), carouselHeroBlend(9.6f, count = 10))
        assertEquals(CarouselHeroBlend(0, null, 0f), carouselHeroBlend(0f, count = 1))
        assertEquals(null, carouselHeroBlend(0f, count = 0))
    }
}
