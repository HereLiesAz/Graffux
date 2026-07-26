package com.hereliesaz.graffitixr.common.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadialMenuGeometryTest {

    private val center = Offset(500f, 500f)

    /** A point [dist] px from centre at [deg] (0 = right, growing clockwise in screen space). */
    private fun at(deg: Float, dist: Float = 100f): Offset {
        val r = Math.toRadians(deg.toDouble())
        return center + Offset((Math.cos(r) * dist).toFloat(), (Math.sin(r) * dist).toFloat())
    }

    @Test
    fun `straight up is slot zero`() {
        assertEquals(0, RadialMenuGeometry.slotAt(center, at(-90f)))
    }

    @Test
    fun `slots run clockwise from the top`() {
        assertEquals(1, RadialMenuGeometry.slotAt(center, at(-30f)))
        assertEquals(2, RadialMenuGeometry.slotAt(center, at(30f)))
        assertEquals(3, RadialMenuGeometry.slotAt(center, at(90f)))   // straight down
        assertEquals(4, RadialMenuGeometry.slotAt(center, at(150f)))
        assertEquals(5, RadialMenuGeometry.slotAt(center, at(210f)))
    }

    @Test
    fun `slot zero is centred on up, not started at it`() {
        // A few degrees either side of vertical must still be the top slot — that's how it reads
        // to the hand, and it's what rounding rather than flooring buys.
        assertEquals(0, RadialMenuGeometry.slotAt(center, at(-100f)))
        assertEquals(0, RadialMenuGeometry.slotAt(center, at(-80f)))
    }

    @Test
    fun `inside the dead zone selects nothing`() {
        assertNull(RadialMenuGeometry.slotAt(center, center))
        assertNull(RadialMenuGeometry.slotAt(center, at(-90f, dist = 10f)))
    }

    @Test
    fun `just outside the dead zone selects`() {
        assertEquals(0, RadialMenuGeometry.slotAt(center, at(-90f, dist = RadialMenuGeometry.DEAD_ZONE_PX + 1f)))
    }

    @Test
    fun `every direction maps to a valid slot`() {
        // No angle may fall through the wedges — a gap would be a dead direction the user can't
        // tell apart from a miss.
        for (deg in -360..720 step 3) {
            val slot = RadialMenuGeometry.slotAt(center, at(deg.toFloat()))
            assertEquals("angle $deg", true, slot != null && slot in 0..5)
        }
    }

    @Test
    fun `slot count other than six is honoured`() {
        assertEquals(0, RadialMenuGeometry.slotAt(center, at(-90f), slots = 4))
        assertEquals(1, RadialMenuGeometry.slotAt(center, at(0f), slots = 4))
        assertEquals(2, RadialMenuGeometry.slotAt(center, at(90f), slots = 4))
        assertEquals(3, RadialMenuGeometry.slotAt(center, at(180f), slots = 4))
    }

    @Test
    fun `a non-positive slot count selects nothing rather than dividing by zero`() {
        assertNull(RadialMenuGeometry.slotAt(center, at(-90f), slots = 0))
    }

    @Test
    fun `slot centre angles match where the slots actually are`() {
        for (slot in 0 until 6) {
            val angle = RadialMenuGeometry.slotCenterAngle(slot)
            assertEquals("slot $slot", slot, RadialMenuGeometry.slotAt(center, at(angle)))
        }
    }
}
