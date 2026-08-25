package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrushDynamics]: speed and pressure are independent multipliers on the segment width, and a
 * finger with no real pressure sensor (pressure always 1f) must reproduce the pre-pressure
 * behaviour exactly — nothing here may regress a device with no stylus.
 */
class BrushDynamicsTest {

    private fun straightLine(n: Int, stepPx: Float) = List(n) { Offset(it * stepPx, 0f) }

    @Test
    fun `full pressure throughout is identical to no pressure list at all`() {
        val points = straightLine(20, 5f)
        val withEmptyPressures = BrushDynamics.segmentWidths(points, baseWidth = 40f)
        val withFullPressures = BrushDynamics.segmentWidths(points, baseWidth = 40f, pressures = List(points.size) { 1f })
        assertEquals(withEmptyPressures.size, withFullPressures.size)
        for (i in withEmptyPressures.indices) {
            assertEquals(withEmptyPressures[i], withFullPressures[i], 0.0001f)
        }
    }

    @Test
    fun `lower pressure narrows the stroke, higher speed still thins on top of it`() {
        val points = straightLine(20, 5f)
        val base = 40f

        val lightTouch = BrushDynamics.segmentWidths(points, base, pressures = List(points.size) { 0.1f })
        val fullPress = BrushDynamics.segmentWidths(points, base, pressures = List(points.size) { 1f })

        // Every segment under light pressure must be narrower than the same segment at full
        // pressure — pressure is a real, monotonic multiplier, not a cosmetic no-op.
        for (i in lightTouch.indices) {
            assertTrue("segment $i: light=${lightTouch[i]} full=${fullPress[i]}", lightTouch[i] < fullPress[i])
        }

        // A stroke never fully vanishes at zero pressure — there is a floor.
        val zeroPress = BrushDynamics.segmentWidths(points, base, pressures = List(points.size) { 0f })
        assertTrue(zeroPress.all { it > 0f })
    }

    @Test
    fun `a fast flick still thins at full pressure, same as before pressure existed`() {
        val base = 40f
        val slow = BrushDynamics.segmentWidths(straightLine(20, 1f), base, pressures = List(20) { 1f })
        val fast = BrushDynamics.segmentWidths(straightLine(20, 20f), base, pressures = List(20) { 1f })
        // Speed's own thinning is unaffected by pressure being wired in — a fast, full-pressure
        // flick is still thinner than a slow, full-pressure one.
        assertTrue(fast.last() < slow.last())
    }

    @Test
    fun `a short pressures list falls back to full pressure for the segments it does not cover`() {
        val points = straightLine(10, 5f)
        val base = 40f
        val noPressures = BrushDynamics.segmentWidths(points, base)
        val shortPressures = BrushDynamics.segmentWidths(points, base, pressures = listOf(1f, 1f))
        for (i in noPressures.indices) {
            assertEquals(noPressures[i], shortPressures[i], 0.0001f)
        }
    }
}
