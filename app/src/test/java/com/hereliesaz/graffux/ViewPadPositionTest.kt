package com.hereliesaz.graffux

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the View pad lands ([viewPadPosition]): beside the button, inside the window, small and landscape. */
class ViewPadPositionTest {
    private val pad = IntSize(150, 150)
    private val gap = 8

    private fun button(x: Int, y: Int) = IntRect(x, y, x + 48, y + 48)

    @Test
    fun `a button near the left edge puts the pad to its right, centred on it`() {
        val at = viewPadPosition(button(10, 300), IntSize(360, 640), pad, gap)
        assertEquals(IntOffset(58 + gap, 324 - 75), at)
    }

    @Test
    fun `a button near the right edge puts the pad to its left`() {
        val at = viewPadPosition(button(300, 300), IntSize(360, 640), pad, gap)
        assertEquals(IntOffset(300 - gap - 150, 324 - 75), at)
    }

    @Test
    fun `the pad is clamped to the top and the bottom`() {
        assertEquals(0, viewPadPosition(button(10, 0), IntSize(360, 640), pad, gap).y)
        assertEquals(640 - 150, viewPadPosition(button(10, 600), IntSize(360, 640), pad, gap).y)
    }

    @Test
    fun `the pad is clamped to the left and right edges`() {
        // Right-half button with too little room on its left: pinned to the left edge.
        assertEquals(0, viewPadPosition(button(120, 300), IntSize(220, 640), pad, gap).x)
        // Left-half button with too little room on its right: pinned to the right edge.
        assertEquals(220 - 150, viewPadPosition(button(60, 300), IntSize(220, 640), pad, gap).x)
    }

    @Test
    fun `a pad wider or taller than the window pins to the top left`() {
        assertEquals(IntOffset(0, 0), viewPadPosition(button(10, 100), IntSize(100, 100), pad, gap))
    }

    @Test
    fun `landscape 640x360 keeps the pad inside`() {
        val at = viewPadPosition(button(600, 150), IntSize(640, 360), pad, gap)
        assertEquals(IntOffset(600 - gap - 150, 174 - 75), at)
        val low = viewPadPosition(button(600, 320), IntSize(640, 360), pad, gap)
        assertEquals(360 - 150, low.y)
    }
}
