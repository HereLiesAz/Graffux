package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.DEFAULT_PEN_ANGLE_DEG
import com.hereliesaz.graffitixr.common.azphalt.StrokeProgramSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class NibAngleTest {
    /** Device-to-world rotation about the screen normal by [deg], counter-clockwise. */
    private fun twisted(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    @Test
    fun `turning the device turns the nib on screen the same way`() {
        val level = twisted(0f)
        assertEquals(25f, deviceTwistDeg(level, twisted(25f)), 1e-3f)
        assertEquals(-60f, deviceTwistDeg(twisted(10f), twisted(-50f)), 1e-3f)
        val nib = NibAngleState(-40f)
        nib.twistDeg = deviceTwistDeg(level, twisted(25f))
        assertEquals(-15f, nib.screenAngleDeg, 1e-3f)
    }

    @Test
    fun `a quarter turn the UI follows is not a twist`() {
        assertEquals(0f, deviceTwistDeg(twisted(0f), twisted(90f), uiQuarterTurns = 1), 1e-3f)
        assertEquals(5f, deviceTwistDeg(twisted(0f), twisted(95f), uiQuarterTurns = 1), 1e-3f)
    }

    @Test
    fun `setting the dial points the nib there whatever the twist, and relevel keeps it there`() {
        val nib = NibAngleState()
        nib.twistDeg = 30f
        nib.setScreenAngle(-45f)
        assertEquals(-45f, nib.screenAngleDeg, 1e-3f)
        assertEquals(-75f, nib.userAngleDeg, 1e-3f)
        nib.relevel()
        assertEquals(-45f, nib.screenAngleDeg, 1e-3f)
        assertEquals(0f, nib.twistDeg, 0f)
    }

    @Test
    fun `the layer sees the nib through the view rotation`() {
        assertEquals(-70f, nibLayerAngle(-40f, 30f), 1e-3f)
        assertEquals(DEFAULT_PEN_ANGLE_DEG, NibAngleState().screenAngleDeg, 0f)
    }

    @Test
    fun `only brushes whose program reads penAngle offer the dial`() {
        val reads = AzphaltBrush(name = "a", program = StrokeProgramSpec(step = "let nib = penAngle; hide = 1;"))
        val not = AzphaltBrush(name = "b", program = StrokeProgramSpec(step = "let penAngleX = 1;"))
        assertTrue(reads.usesPenAngle())
        assertFalse(not.usesPenAngle())
        assertFalse(AzphaltBrush(name = "c").usesPenAngle())
    }

    @Test
    fun `locked, the nib keeps its angle to the canvas through device turns and view rotation`() {
        val nib = NibAngleState(-40f)
        nib.viewRotationDeg = 10f
        nib.twistDeg = 20f
        val onScreen = nib.screenAngleDeg
        nib.toggleLock()
        assertTrue(nib.locked)
        assertEquals(nibLayerAngle(onScreen, 10f), nib.lockedLayerDeg!!, 1e-3f)
        // The device turns: nothing changes. The canvas view turns: the nib turns with it on screen.
        nib.twistDeg = 80f
        nib.viewRotationDeg = 40f
        assertEquals(nibLayerAngle(onScreen, 10f), nibLayerAngle(nib.screenAngleDeg, 40f), 1e-3f)
        // Released, it stays where it points and follows the world again.
        val before = nib.screenAngleDeg
        nib.toggleLock()
        assertFalse(nib.locked)
        assertEquals(before, nib.screenAngleDeg, 1e-3f)
    }

    @Test
    fun `dragging a locked dial sets the canvas angle`() {
        val nib = NibAngleState(-40f, initialLockDeg = -40f)
        nib.viewRotationDeg = 30f
        nib.setScreenAngle(0f)
        assertEquals(-30f, nib.lockedLayerDeg!!, 1e-3f)
    }
}
