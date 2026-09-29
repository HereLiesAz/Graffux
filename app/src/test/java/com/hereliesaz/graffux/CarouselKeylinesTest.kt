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
}
