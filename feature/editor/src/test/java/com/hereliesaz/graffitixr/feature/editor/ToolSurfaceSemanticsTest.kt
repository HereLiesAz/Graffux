package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every tool surface is announced as a pane titled with its title, its title is a heading, and a
 * screen reader reads its own content (header, close, body) before the scrim behind it. A surface
 * used to be an unnamed group of nodes with a scrim that was first in traversal order.
 */
abstract class ToolSurfaceSemanticsTest(private val phone: Boolean) {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var shown by mutableStateOf<Pair<SurfaceKind, String>?>(null)
    private var composed = false

    // One setContent per test: later kinds swap through state.
    private fun open(kind: SurfaceKind, title: String) {
        if (!composed) {
            composed = true
            rule.setContent {
                shown?.let { (k, t) -> key(k, t) { ToolSurface(k, t, onDismiss = {}) { Text("body of $t") } } }
            }
        }
        shown = kind to title
        rule.waitForIdle()
    }

    private fun tagFor(kind: SurfaceKind): String = when (kind) {
        SurfaceKind.VALUE -> "surface.popover"
        SurfaceKind.PICKER -> if (phone) "surface.sheet" else "surface.popover"
        SurfaceKind.FORM -> if (phone) "surface.sheet" else "surface.centered"
        SurfaceKind.EDITOR, SurfaceKind.TIMELINE -> if (phone) "surface.page" else "surface.docked"
        SurfaceKind.BROWSER -> "surface.page"
    }

    @Test
    fun `each surface is a pane named by its title, a traversal group, with a heading title`() {
        SurfaceKind.entries.forEach { kind ->
            val title = "Title ${kind.name}"
            open(kind, title)
            val panel = rule.onNodeWithTag(tagFor(kind), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("$kind pane title", title, panel.config.getOrNull(SemanticsProperties.PaneTitle))
            assertEquals("$kind traversal group", true, panel.config.getOrNull(SemanticsProperties.IsTraversalGroup))
            val isHeading = hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            val heading = rule.onAllNodes(isHeading, true)
            assertTrue("$kind: title is not a heading", heading.fetchSemanticsNodes().isNotEmpty())
        }
    }

    /** The kinds whose container has a scrim behind it (a popover, a bottom sheet, a centred sheet). */
    private fun hasScrim(kind: SurfaceKind): Boolean = when (kind) {
        SurfaceKind.VALUE -> true
        SurfaceKind.PICKER, SurfaceKind.FORM -> true
        SurfaceKind.EDITOR, SurfaceKind.TIMELINE, SurfaceKind.BROWSER -> false
    }

    @Test
    fun `exactly the kinds with a scrim have one, and a screen reader reaches the surface before it`() {
        SurfaceKind.entries.forEach { kind ->
            open(kind, "T")
            val scrims = rule.onAllNodesWithTag("surface.scrim", useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("$kind scrim count", if (hasScrim(kind)) 1 else 0, scrims.size)
            if (hasScrim(kind)) {
                val panel = rule.onNodeWithTag(tagFor(kind), useUnmergedTree = true).fetchSemanticsNode()
                val scrimIndex = scrims.single().config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f
                val panelIndex = panel.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f
                assertTrue("$kind: scrim $scrimIndex must follow the panel $panelIndex", scrimIndex > panelIndex)
            }
        }
    }

    @Test
    fun `the close button is named for its surface`() {
        open(SurfaceKind.BROWSER, "Open")
        val desc = rule.onNodeWithTag("surface.back", useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Close Open"), desc)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PhoneToolSurfaceSemanticsTest : ToolSurfaceSemanticsTest(phone = true)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h1200dp")
class TabletToolSurfaceSemanticsTest : ToolSurfaceSemanticsTest(phone = false)
