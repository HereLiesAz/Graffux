package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselKeylinesTest {

    // Small cards at most 168px (56dp at xxhdpi), the hero keyline 861px.
    private val small = 168f
    private val hero = 861f

    @Test
    fun `at rest the position is exactly the hero's index`() {
        assertEquals(4f, carouselHeroPosition(mapOf(3 to small, 4 to hero, 5 to small), small, hero))
        // At the start M3 shifts the keylines so item 0 is the large one; the smalls still weigh nothing.
        assertEquals(0f, carouselHeroPosition(mapOf(0 to hero, 1 to small, 2 to 30f), small, hero))
    }

    @Test
    fun `mid-scroll the position lies between the two items trading places`() {
        val mid = (small + hero) / 2f
        val half = carouselHeroPosition(mapOf(3 to small, 4 to mid, 5 to mid, 6 to small), small, hero)!!
        assertEquals(4.5f, half, 1e-4f)
        val span = hero - small
        val quarter = carouselHeroPosition(mapOf(4 to small + span * 0.75f, 5 to small + span * 0.25f), small, hero)!!
        assertEquals(4.25f, quarter, 1e-4f)
    }

    @Test
    fun `sub-pixel noise at rest reads as resting, and nothing laid out reads as unknown`() {
        assertEquals(2f, carouselHeroPosition(mapOf(2 to hero, 3 to small + 0.1f), small, hero))
        assertNull(carouselHeroPosition(emptyMap(), small, hero))
        assertNull(carouselHeroPosition(mapOf(1 to small), small, hero))
        assertNull(carouselHeroPosition(mapOf(1 to hero), hero, hero))
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

    @Test
    fun `centred hero sizes at 411dp follow M3 hero proportions and fill the row`() {
        val s = centredHeroSizes(411f, 6f, 40f, 56f)!!
        assertEquals(129f, s.hero, 1e-3f)
        assertEquals(86f, s.medium, 1e-3f)
        assertEquals(43f, s.small, 1e-3f)
        assertEquals(411f, s.hero + 2 * s.medium + 2 * s.small + 4 * 6f, 1e-3f)
    }

    @Test
    fun `small clamps to its range on wide and narrow rows`() {
        val wide = centredHeroSizes(560f, 6f, 40f, 56f)!!
        assertEquals(56f, wide.small, 1e-3f)
        assertEquals(560f, wide.hero + 2 * wide.medium + 2 * wide.small + 24f, 1e-3f)
        val narrow = centredHeroSizes(300f, 6f, 40f, 56f)!!
        assertEquals(40f, narrow.small, 1e-3f)
        assertNull(centredHeroSizes(100f, 6f, 40f, 56f))
    }

    @Test
    fun `the keyline list is symmetric about a centred hero`() {
        val list = centredHeroKeylineList(411f, 6f, 40f, 56f, 10f)
        assertEquals(7, list.size)
        assertEquals(listOf(10f, 43f, 86f, 129f, 86f, 43f, 10f), list.map { it.size })
        assertEquals(3, list.firstFocalIndex)
        assertEquals(3, list.lastFocalIndex)
        assertEquals(205.5f, list.firstFocal.offset, 1e-3f)
        for (i in 0..2) assertEquals(411f, list[i].offset + list[6 - i].offset, 1e-3f)
        assertTrue(list.first().offset + 5f <= 0f && list.last().offset - 5f >= 411f)
        assertTrue(centredHeroKeylineList(100f, 6f, 40f, 56f, 10f).isEmpty())
    }

    @Test
    fun `card height is continuous across the keylines and ordered small, medium, hero`() {
        val h = CarouselCardHeights(104f, 136f, 200f)
        fun at(size: Float) = carouselCardHeight(size, 43f, 86f, 129f, h)
        assertEquals(104f, at(10f), 1e-3f)
        assertEquals(104f, at(43f), 1e-3f)
        assertEquals(136f, at(86f), 1e-3f)
        assertEquals(200f, at(129f), 1e-3f)
        assertEquals(120f, at(64.5f), 1e-3f)
        assertEquals(168f, at(107.5f), 1e-3f)
        // Monotonic, with no jump anywhere between anchor and hero.
        var last = at(0f)
        var size = 0f
        while (size <= 140f) {
            val next = at(size)
            assertTrue(next >= last && next - last < 2f)
            last = next
            size += 0.5f
        }
    }
}
