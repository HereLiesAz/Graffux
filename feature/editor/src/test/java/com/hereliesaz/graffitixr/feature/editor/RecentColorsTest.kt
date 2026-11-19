package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Colour sheet's recent-colours list and hex field: pure logic, no Android. */
class RecentColorsTest {
    private val red = Color(0xFFFF0000)
    private val green = Color(0xFF00FF00)
    private val blue = Color(0xFF0000FF)

    @Test
    fun `a colour already in the list moves to the front instead of appearing twice`() {
        assertEquals(listOf(red, blue, green), pushRecentColor(listOf(blue, green, red), red))
        assertEquals(listOf(blue, red), pushRecentColor(listOf(red), blue))
        assertEquals(listOf(red, blue), pushRecentColor(listOf(blue, red), red))
    }

    @Test
    fun `the list never grows past the cap and drops the oldest`() {
        val many = (0 until RECENT_COLORS_MAX + 6).fold(emptyList<Color>()) { acc, i ->
            pushRecentColor(acc, Color(0xFF000000 or i.toLong()))
        }
        assertEquals(RECENT_COLORS_MAX, many.size)
        assertEquals(Color(0xFF000000 or (RECENT_COLORS_MAX + 5).toLong()), many.first())
        assertEquals(Color(0xFF000000 or 6L), many.last())
    }

    @Test
    fun `the cap is 24`() = assertEquals(24, RECENT_COLORS_MAX)

    @Test
    fun `deleting removes that colour only, and nothing when it is absent`() {
        assertEquals(listOf(red, green), removeRecentColor(listOf(red, blue, green), blue))
        val list = listOf(red, green)
        assertEquals(list, removeRecentColor(list, blue))
    }

    @Test
    fun `stored colours sit under this run's, without duplicates, within the cap`() {
        assertEquals(listOf(red, green, blue), mergeRecentColors(listOf(red, green), listOf(green, blue)))
        val stored = (0 until RECENT_COLORS_MAX).map { Color(0xFF000000 or it.toLong()) }
        assertEquals(RECENT_COLORS_MAX, mergeRecentColors(listOf(red), stored).size)
    }

    @Test
    fun `the list survives being written and read back, in order`() {
        val list = listOf(red, Color(0x80123456), blue)
        assertEquals("FFFF0000,80123456,FF0000FF", encodeRecentColors(list))
        assertEquals(list, decodeRecentColors(encodeRecentColors(list)))
    }

    @Test
    fun `unreadable stored entries are skipped, never an error`() {
        assertEquals(emptyList<Color>(), decodeRecentColors(null))
        assertEquals(emptyList<Color>(), decodeRecentColors(""))
        assertEquals(listOf(red, blue), decodeRecentColors("FFFF0000,zz,12,,FF0000FF,FFFF0000"))
        val forty = (0 until 40).map { Color(0xFF000000 or it.toLong()) }
        assertEquals(RECENT_COLORS_MAX, decodeRecentColors(encodeRecentColors(forty)).size)
    }

    @Test
    fun `hex takes three or six digits, with or without the hash`() {
        assertEquals(Color(0xFFFF00AA), parseHexColor("#F0A"))
        assertEquals(Color(0xFFFF00AA), parseHexColor("f0a"))
        assertEquals(Color(0xFFFF00AA), parseHexColor("#ff00aa"))
        assertEquals(Color(0xFFFF00AA), parseHexColor("FF00AA"))
        assertEquals(Color(0xFFFF00AA), parseHexColor("  #ff00aa "))
        assertEquals(Color(0x80FF00AA), parseHexColor("#80FF00AA"))
    }

    @Test
    fun `anything else is not a colour`() {
        listOf("", "#", "#12", "#1234", "#12345", "#1234567", "#GGG", "#12G456", "-12", "+123", "#ff 00aa", "red")
            .forEach { assertNull(it, parseHexColor(it)) }
    }

    @Test
    fun `a hex field keeps the brush's opacity unless it spells its own`() {
        val half = Color(0x80000000)
        assertEquals(Color(0xFFFF00AA).copy(alpha = half.alpha), hexFieldColor("#F0A", half))
        assertEquals(Color(0xFFFF00AA).copy(alpha = half.alpha), hexFieldColor("ff00aa", half))
        assertEquals(Color(0x40FF00AA), hexFieldColor("#40FF00AA", half))
        assertNull(hexFieldColor("#12", half))
    }

    @Test
    fun `hex is written out the way it is read`() {
        assertEquals("#FF00AA", hexOf(Color(0xFFFF00AA)))
        assertEquals("#80FF00AA", hexOf(Color(0x80FF00AA)))
        assertEquals(Color(0xFFFF00AA), parseHexColor(hexOf(Color(0xFFFF00AA))))
    }

    @Test
    fun `a swatch is a 48dp target and the grid fits as many as the width allows`() {
        assertTrue(RECENT_SWATCH_TOUCH >= 48.dp)
        assertEquals(1, recentGridColumns(20.dp))
        assertEquals(1, recentGridColumns(95.dp))
        assertEquals(2, recentGridColumns(96.dp))
        assertEquals(6, recentGridColumns(300.dp))
    }
}
