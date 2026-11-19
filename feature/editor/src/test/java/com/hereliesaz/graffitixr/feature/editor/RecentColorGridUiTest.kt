package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The recent-colours grid: tap uses, hold deletes (a buzz and "Deleted #RRGGBB"), TalkBack has Delete, 48dp targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class RecentColorGridUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val red = Color(0xFFFF0000)
    private val green = Color(0xFF00FF00)
    private val picked = ArrayList<Color>()
    private val deleted = ArrayList<Color>()
    private var buzzes = 0

    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            if (hapticFeedbackType == HapticFeedbackType.LongPress) buzzes++
        }
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                MaterialTheme {
                    RecentColorGrid(
                        colors = listOf(red, green),
                        selected = green,
                        onPick = { picked += it },
                        onDelete = { deleted += it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun swatches() = rule.onAllNodesWithTag("touchColor.recent.swatch", useUnmergedTree = true)

    @Test
    fun `a tap uses the colour and does not delete it`() {
        show()
        swatches()[0].performClick()
        assertEquals(listOf(red), picked)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a long press deletes it with a buzz and a polite Deleted notice`() {
        show()
        swatches()[0].performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(listOf(red), deleted)
        assertTrue(picked.isEmpty())
        assertEquals(1, buzzes)
        val notice = rule.onNodeWithTag("touchColor.recent.deleted", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Deleted #FF0000", notice.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        assertTrue(notice.config.contains(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `TalkBack gets a Delete action on every swatch`() {
        show()
        val node = swatches()[1]
        val actions = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Delete"), actions.map { it.label })
        assertTrue(actions.single().action())
        assertEquals(listOf(green), deleted)
    }

    @Test
    fun `every swatch is a 48dp target`() {
        show()
        val fetched = swatches().fetchSemanticsNodes()
        assertEquals(2, fetched.size)
        fetched.forEach {
            assertEquals(48f * rule.density.density, it.size.width.toFloat(), 1f)
            assertEquals(48f * rule.density.density, it.size.height.toFloat(), 1f)
        }
    }
}
