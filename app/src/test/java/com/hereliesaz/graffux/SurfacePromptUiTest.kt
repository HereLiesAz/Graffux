@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.SurfaceDrawNotice
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.surfaceDrawArmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Draw-surface prompt chip and the 3D slot while armed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class SurfacePromptUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var armed by mutableStateOf(true)
    private var notice by mutableStateOf<SurfaceDrawNotice?>(null)
    private var cancels = 0

    private fun showChip() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SurfacePromptChip(armed, notice, onCancel = { cancels++; armed = false; notice = null })
            }
        }
    }

    @Test
    fun `armed, the chip says plainly what to do and offers Cancel`() {
        showChip()
        rule.onNodeWithText("Set up this 3D group: draw its shape", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Drag one line across the canvas. That line becomes the group's 3D surface. Your artwork is not changed.", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("surface.prompt.cancel").assertExists()
        rule.onNodeWithText("Cancel", useUnmergedTree = true).assertExists()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Set up this 3D group: draw its shape. Drag one line across the canvas. That line becomes the group's 3D surface. Your artwork is not changed."), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `Cancel disarms`() {
        showChip()
        rule.onNodeWithTag("surface.prompt.cancel").performClick()
        rule.waitForIdle()
        assertEquals(1, cancels)
        rule.onAllNodes(hasTestTag("surface.prompt")).assertCountEquals(0)
    }

    @Test
    fun `the Back key cancels while armed`() {
        showChip()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, cancels)
        assertFalse(armed)
    }

    @Test
    fun `the armed prompt stays up with no timeout`() {
        showChip()
        rule.mainClock.advanceTimeBy(120_000)
        rule.onNodeWithTag("surface.prompt").assertExists()
    }

    @Test
    fun `a too-short line is said in the chip while it stays armed`() {
        notice = SurfaceDrawNotice.TOO_SHORT
        showChip()
        rule.onNodeWithText("Set up this 3D group: draw its shape", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("That line was too short. Drag a longer line across the canvas.", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Cancel", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `reasons for a refused tap are in the chip with a way forward and OK`() {
        armed = false
        val wanted = mapOf(
            SurfaceDrawNotice.LOCKED to "This layer is locked. Unlock it, then tap 3D.",
            SurfaceDrawNotice.NO_LAYER to "No layer is selected. Tap a layer on the rail, then tap 3D.",
            SurfaceDrawNotice.UNAVAILABLE to "This layer can't have a 3D surface. Pick another layer, then tap 3D.",
        )
        notice = SurfaceDrawNotice.LOCKED
        showChip()
        wanted.forEach { (n, text) ->
            rule.runOnUiThread { notice = n }
            rule.onNodeWithText(text, useUnmergedTree = true).assertExists()
        }
        rule.onNodeWithText("OK", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the confirmation names the next step and how to undo`() {
        armed = false
        notice = SurfaceDrawNotice.DONE
        showChip()
        rule.onNodeWithText("3D surface set. Turn it with the X/Y/Z button. Undo removes it.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no chip when there is nothing to say`() {
        armed = false
        showChip()
        rule.onAllNodes(hasTestTag("surface.prompt")).assertCountEquals(0)
    }

    @Test
    fun `the slot reads Draw while armed, 3D otherwise, and a tap reaches the view-model`() {
        var taps = 0
        var state by mutableStateOf(EditorUiState(activeTool = Tool.BRUSH, layers = listOf(Layer("a", "A")), activeLayerId = "a"))
        val actions = object : ToolRailActions {
            override fun setActiveTool(tool: Tool) = Unit
            override fun onColorClicked() = Unit
            override fun resetViewport() = Unit
            override fun onUndoClicked() = Unit
            override fun onRedoClicked() = Unit
            override fun setBrushSize(size: Float) = Unit
            override fun setBrushFeathering(feathering: Float) = Unit
            override fun onAxisSlotTapped() { taps++; state = state.copy(surfaceDrawWrapLayerId = if (state.surfaceDrawArmed) null else "a") }
        }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(actions, state, emptySet(), emptySet(), ToolRailCallbacks({}, {}, {}))
            }
        }
        rule.onNodeWithText("3D", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rail.$AXIS_RAIL_ID").performClick()
        rule.waitForIdle()
        assertEquals(1, taps)
        rule.onNodeWithText("Draw", useUnmergedTree = true).assertExists()
        // Bright white while setup is going on, and its state is spoken.
        assertEquals(androidx.compose.ui.graphics.Color.White, AXIS_ARMED_FILL)
        rule.onNode(androidx.compose.ui.test.hasStateDescription("Setting up the 3D group"), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3D", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("rail.$AXIS_RAIL_ID").assertContentDescriptionContains("cancel", substring = true, ignoreCase = true)
        // A tap on the armed slot is the toggle back.
        rule.onNodeWithTag("rail.$AXIS_RAIL_ID").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("3D", useUnmergedTree = true).assertExists()
        assertTrue(taps == 2)
    }

    @Test
    fun `the messages sit under the chip, not over it, at a large font scale`() {
        val alerts = MessageController().apply { show(message = "Saved the project to your device and shared it with everyone") }
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale = 2f),
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    NoticeStack(true, SurfaceDrawNotice.TOO_SHORT, {}, alerts, "OK", { "$it times" })
                }
            }
        }
        val chip = rule.onNodeWithTag("surface.prompt").fetchSemanticsNode().boundsInRoot
        val message = rule.onNodeWithTag("chrome.message").fetchSemanticsNode().boundsInRoot
        assertTrue("chip ${chip} message ${message}", message.top >= chip.bottom)
    }

    @Test
    fun `a side view says what to do and marks which views are done`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SurfacePromptChip(
                    true, null, onCancel = {},
                    view = com.hereliesaz.graffitixr.common.scene.SurfaceView.SIDE,
                    views = SurfaceViewsStatus(front = true, side = false, top = false),
                )
            }
        }
        rule.onNodeWithText("Set up this 3D group: draw its side", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Front: done. Side: not drawn yet. Top: not drawn yet.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `once a view is set the chip offers the other views`() {
        val drawn = mutableListOf<com.hereliesaz.graffitixr.common.scene.SurfaceView>()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SurfacePromptChip(
                    false, SurfaceDrawNotice.DONE, onCancel = {},
                    views = SurfaceViewsStatus(front = true, side = false, top = true),
                    onDrawView = { drawn += it },
                )
            }
        }
        rule.onNodeWithText("Draw side view", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Redraw top view", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("surface.prompt.draw.side").performClick()
        assertEquals(listOf(com.hereliesaz.graffitixr.common.scene.SurfaceView.SIDE), drawn)
    }
}
