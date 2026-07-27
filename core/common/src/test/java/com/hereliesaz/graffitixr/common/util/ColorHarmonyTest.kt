package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorHarmonyTest {

    private fun assertHues(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.01f) }
    }

    @Test
    fun `complementary is the opposite hue`() {
        assertHues(listOf(180f), ColorHarmony.partners(0f, HarmonyMode.COMPLEMENTARY))
        assertHues(listOf(30f), ColorHarmony.partners(210f, HarmonyMode.COMPLEMENTARY))
    }

    @Test
    fun `analogous straddles the base by thirty degrees`() {
        assertHues(listOf(70f, 130f), ColorHarmony.partners(100f, HarmonyMode.ANALOGOUS))
    }

    @Test
    fun `triadic splits the wheel in three`() {
        assertHues(listOf(120f, 240f), ColorHarmony.partners(0f, HarmonyMode.TRIADIC))
    }

    @Test
    fun `split complementary flanks the complement`() {
        val partners = ColorHarmony.partners(0f, HarmonyMode.SPLIT_COMPLEMENTARY)
        assertHues(listOf(150f, 210f), partners)
        // Both sit the same distance either side of the complement.
        assertEquals(180f - partners[0], partners[1] - 180f, 0.01f)
    }

    @Test
    fun `partners wrap around the wheel rather than exceeding it`() {
        // A base near the top of the range would push its partners past 360 without normalising.
        val partners = ColorHarmony.partners(350f, HarmonyMode.TRIADIC)
        assertHues(listOf(110f, 230f), partners)
        assertTrue(partners.all { it >= 0f && it < 360f })
    }

    @Test
    fun `a negative base hue normalises`() {
        // atan2-derived hues and wrapped wheel drags can both go negative.
        assertHues(listOf(170f), ColorHarmony.partners(-10f, HarmonyMode.COMPLEMENTARY))
    }

    @Test
    fun `normalizeHue maps every input into the wheel`() {
        assertEquals(0f, ColorHarmony.normalizeHue(360f), 0.01f)
        assertEquals(350f, ColorHarmony.normalizeHue(-10f), 0.01f)
        assertEquals(10f, ColorHarmony.normalizeHue(730f), 0.01f)
    }

    @Test
    fun `no mode returns the base itself as one of its partners`() {
        // The base is drawn by the main selector; a partner marker on top of it would be invisible
        // and unpickable.
        HarmonyMode.entries.forEach { mode ->
            assertTrue(
                "$mode returned the base hue",
                ColorHarmony.partners(42f, mode).none { kotlin.math.abs(it - 42f) < 0.01f },
            )
        }
    }
}
