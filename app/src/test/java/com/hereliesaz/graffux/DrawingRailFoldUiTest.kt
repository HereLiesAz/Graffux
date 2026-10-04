package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.model.AzUnattachedAnchor
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The right-rail wiring as composed by the real AzNavRail host: an OPPOSITE unattached host the user
 * left expanded collapses its sub-items when [StrokeGate.strokeActive] goes true and re-expands them
 * [DRAWING_UI_RETURN_DELAY_MS] after it goes false (through [rememberDrawingUiHidden], as
 * MainActivity wires it), and nothing is persisted as a user collapse along the way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class DrawingRailFoldUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `host collapses on stroke start and restores on stroke end`() {
        val gate = StrokeGate()
        val persisted = mutableListOf<Boolean>()
        var folded = false
        rule.setContent {
            val hidden by rememberDrawingUiHidden { gate.strokeActive }
            AzHostActivityLayout(navController = rememberNavController(), initiallyExpanded = false) {
                isFoldedUp = DrawingRailFold.mainRailFolded(false, hidden)
                folded = isFoldedUp
                azRailItem(id = "main", text = "Main") { }
                azUnattachedHostItem(
                    id = "grp.test", text = "Host", anchor = AzUnattachedAnchor.OPPOSITE,
                    initiallyExpanded = true,
                    expandWhen = { DrawingRailFold.hostExpandWhen(true, hidden) },
                    onExpandedChange = {
                        if (DrawingRailFold.persistExpansionChange(it, hidden)) persisted += it
                    },
                )
                azRailSubItem(id = "sub.a", hostId = "grp.test", text = "SubItemA") { }
            }
        }
        rule.waitForIdle()
        fun subShown() = rule.onAllNodesWithText("SubItemA").fetchSemanticsNodes().isNotEmpty()
        assertTrue("sub-item visible before drawing", subShown())

        rule.runOnIdle { gate.strokeActive = true }
        rule.waitForIdle()
        assertEquals("sub-item hidden while drawing", false, subShown())
        assertTrue("main rail folded while drawing", folded)

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { gate.strokeActive = false }
        rule.mainClock.advanceTimeBy(DRAWING_UI_RETURN_DELAY_MS - 100)
        assertEquals("sub-item still hidden during the hold", false, subShown())
        assertTrue("main rail still folded during the hold", folded)
        rule.mainClock.advanceTimeBy(500)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("sub-item restored after the stroke", subShown())
        assertEquals("main rail unfolded after the stroke", false, folded)
        assertTrue("no collapse persisted as the user's choice", persisted.none { !it })
    }
}
