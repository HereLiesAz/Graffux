@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The enlarged rotation triangle fits the window on a 360dp-wide phone, portrait and landscape, either hand: fully
 * on screen, off the tool rail beside it, and with room left for the layers rail above it (xhdpi, 1dp = 2px).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi", application = Application::class)
class RotationGizmoFitUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val d = 2f

    private fun show(rightHanded: Boolean, onOccupied: (Int) -> Unit = {}) {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                RotationGizmoDock(rightHanded = rightHanded, onOccupied = onOccupied) {
                    RotationGizmo({ _, _ -> }, {}, { _, _ -> }, scale = it)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertFits(windowWdp: Float, windowHdp: Float, right: Boolean) {
        var occupied = 0
        show(right) { occupied = it }
        val b = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        val ww = windowWdp * d
        val wh = windowHdp * d
        assertTrue("inside the window: $b", b.left >= 0f && b.top >= 0f && b.right <= ww && b.bottom <= wh)
        val gap = GizmoEdgeMargin.value * d
        assertTrue("keeps the edge margin: $b", if (right) b.right <= ww - gap else b.left >= gap)
        // off the tool rail, which is on the other side (12dp inset + 56dp wide)
        val railPx = (RailSideInset + ToolRailWidth).value * d
        assertTrue("clear of the tool rail: $b", if (right) b.left >= railPx else b.right <= ww - railPx)
        // the layers rail, on the gizmo's side, stops above it and still has room for a button
        val clearance = gizmoRailClearancePx(occupied, 0, gap.toInt())
        val room = wh - LayersRailTopInset.value * d - clearance
        assertTrue("layers rail keeps ${room}px", room >= RailTouchSize.value * d)
        assertEquals(wh - b.top, occupied.toFloat(), 1f)
    }

    @Test
    fun `360dp portrait, right-handed`() = assertFits(360f, 740f, true)

    @Test
    fun `360dp portrait, left-handed`() = assertFits(360f, 740f, false)

    @Test
    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    fun `360dp-high landscape, right-handed`() = assertFits(740f, 360f, true)

    @Test
    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    fun `360dp-high landscape, left-handed`() = assertFits(740f, 360f, false)

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `short 360 x 640dp portrait, right-handed`() = assertFits(360f, 640f, true)
}
