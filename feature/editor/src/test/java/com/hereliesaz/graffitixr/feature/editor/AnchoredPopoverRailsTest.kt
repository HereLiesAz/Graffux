package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.AnchoredValuePopover
import com.hereliesaz.graffitixr.design.components.LocalRailInset
import com.hereliesaz.graffitixr.design.components.LocalSurfaceAnchor
import com.hereliesaz.graffitixr.design.components.RailInset
import com.hereliesaz.graffitixr.design.components.SurfaceAnchor
import com.hereliesaz.graffitixr.design.components.popoverSpan
import com.hereliesaz.graffitixr.design.components.trackSurfaceAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tablet's anchored popover opened beside a rail: it keeps clear of each rail at that rail's own width, and of
 * a side display cutout (the span arithmetic; the insets themselves come from `WindowInsets.safeDrawing`, which a
 * Robolectric host cannot be fed). (mdpi, so a pixel is a dp.) Pressing at the screen's far edge pulls the card to the
 * limit of what it may use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h1000dp-mdpi", application = Application::class)
class AnchoredPopoverRailsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)
    private val anchor = SurfaceAnchor()

    /** The tool rail is 60dp and on the left, the layers rail 100dp and on the right. */
    private val rails = RailInset(dockedOnLeft = true, toolWidth = 60.dp, layersWidth = 100.dp)

    private fun host(clearOfRails: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalRailInset provides rails, LocalSurfaceAnchor provides anchor) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().trackSurfaceAnchor(anchor)) {
                        if (open) {
                            AnchoredValuePopover("Test", onDismiss = {}, wide = true, clearOfRails = clearOfRails) {
                                Box(Modifier.fillMaxWidth().height(80.dp))
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pressAt(x: Float) {
        rule.onRoot().performTouchInput {
            down(Offset(x, 500f))
            up()
        }
        rule.runOnIdle { open = true }
        rule.waitForIdle()
    }

    private fun popover() =
        rule.onNodeWithTag("surface.popover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `between the rails it clears each at its own width`() {
        host(clearOfRails = true)
        pressAt(1f)
        assertEquals("16dp margin + the 60dp tool rail", 76f, popover().left, 1f)
        rule.runOnIdle { open = false }
        rule.waitForIdle()
        pressAt(839f)
        assertEquals("16dp margin + the 100dp layers rail", 840f - 116f, popover().right, 1f)
    }

    @Test
    fun `without clearOfRails only the tool rail is cleared`() {
        host(clearOfRails = false)
        pressAt(839f)
        assertEquals("the layers rail is not in the way of a one-rail popover", 840f - 16f, popover().right, 1f)
    }

    @Test
    fun `the span keeps the card out of a side cutout and each rail at its own width`() {
        // 840 wide, 16 margin, rails 60 left and 100 right, a 48 cutout on the left and 36 on the right.
        assertEquals(76 to 724, popoverSpan(840, 16, 60 to 100, 0 to 0))
        assertEquals(124 to 688, popoverSpan(840, 16, 60 to 100, 48 to 36))
        // No rail, no cutout: just the margin.
        assertEquals(16 to 824, popoverSpan(840, 16, 0 to 0, 0 to 0))
        // A cutout on one side moves only that side.
        assertEquals(64 to 824, popoverSpan(840, 16, 0 to 0, 48 to 0))
        assertTrue(popoverSpan(840, 16, 60 to 100, 48 to 36).let { (l, r) -> l < r })
    }
}
