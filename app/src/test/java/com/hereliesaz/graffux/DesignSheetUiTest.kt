@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Rect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The design properties as composed: the phone sheet's Frame tab and the tablet side panel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class DesignSheetUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Records what the controls asked for, and applies it, like the view-model would. */
    private class Recorder : DesignActions {
        var target by mutableStateOf<DesignTarget?>(null)
        val calls = mutableListOf<String>()
        override fun setBox(x: Float, y: Float, width: Float, height: Float) {
            calls += "box $x $y $width $height"
            target = target?.copy(box = Rect(x, y, width, height))
        }
        override fun setRotation(degrees: Float) { calls += "rotation $degrees" }
        override fun setOpacityPercent(percent: Float) { calls += "opacity $percent" }
        override fun setRadius(radius: Float) { calls += "radius $radius" }
        override fun setClip(clips: Boolean) {
            calls += "clip $clips"
            target = target?.copy(clipsContent = clips)
        }
        override fun setFill(argb: Long?) {
            calls += "fill $argb"
            target = target?.copy(fillArgb = argb)
        }
        override fun fillWithColorInHand() { calls += "fill in hand" }
        override fun addFrame() { calls += "add frame" }
    }

    private val pay = designTargetOf(FrameOps.create("pay", "Pay", Rect(390f, 70f, 190f, 340f)), Rect(390f, 70f, 190f, 340f))

    private fun sheet(recorder: Recorder) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    DesignSheetBody(
                        tab = DesignTab.FRAME,
                        showingControls = true,
                        target = recorder.target,
                        actions = recorder,
                        onSelectTab = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    fun `the Frame tab shows the selected frame and a typed width resizes it`() {
        val recorder = Recorder().apply { target = pay }
        sheet(recorder)
        rule.onNodeWithTag("design.title").assertTextEquals("Pay")
        rule.onNodeWithTag("design.x").assertTextEquals("390")
        rule.onNodeWithTag("design.w").performTextReplacement("240")
        rule.onNodeWithTag("design.w").performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("box 390.0 70.0 240.0 340.0"), recorder.calls)
        rule.onNodeWithTag("design.w").assertTextEquals("240")
    }

    @Test
    fun `an entry that does not parse changes nothing`() {
        val recorder = Recorder().apply { target = pay }
        sheet(recorder)
        rule.onNodeWithTag("design.h").performTextReplacement("tall")
        rule.onNodeWithTag("design.h").performImeAction()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), recorder.calls)
        rule.onNodeWithTag("design.h").assertTextEquals("340")
    }

    @Test
    fun `clip and fill act on the frame`() {
        val recorder = Recorder().apply { target = pay }
        sheet(recorder)
        rule.onNodeWithTag("design.clip.clip").assertIsSelected()
        rule.onNodeWithTag("design.clip.show all").performClick()
        rule.onNodeWithTag("design.clip.show all").assertIsSelected()
        rule.onNodeWithTag("design.fill.value").assertTextEquals("#FFFFFF")
        rule.onNodeWithTag("design.fill.none").performClick()
        rule.onNodeWithTag("design.fill.value").assertTextEquals("None")
        rule.onNodeWithTag("design.fill.color").performClick()
        assertEquals(listOf("clip false", "fill null", "fill in hand"), recorder.calls)
    }

    @Test
    fun `with nothing selected the sheet offers a new frame`() {
        val recorder = Recorder()
        sheet(recorder)
        rule.onNodeWithTag("design.empty").assertIsDisplayed()
        rule.onNodeWithTag("design.addFrame").performClick()
        assertEquals(listOf("add frame"), recorder.calls)
    }

    @Test
    @Config(qualifiers = "w1180dp-h820dp-mdpi")
    fun `the side panel stacks the sections and switches back to the sheet`() {
        val recorder = Recorder().apply { target = pay }
        var placement by mutableStateOf(PropertiesPlacement.SIDE_PANEL)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    PropertiesPanel(
                        target = recorder.target,
                        actions = recorder,
                        placement = placement,
                        onPlacement = { placement = it },
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                    )
                }
            }
        }
        rule.onNodeWithTag("design.sidePanel.name").assertTextEquals("Pay")
        rule.onNodeWithTag("design.section.frame").assertIsDisplayed()
        rule.onNodeWithTag("design.section.fill").assertIsDisplayed()
        val panel = rule.onNodeWithTag("design.sidePanel").fetchSemanticsNode().boundsInRoot
        assertEquals(320, Math.round(panel.width / rule.density.density))
        rule.onNodeWithTag("design.section.fill.action").performClick()
        rule.onNodeWithTag("design.placement.bottom_sheet").performClick()
        assertEquals(PropertiesPlacement.BOTTOM_SHEET, placement)
        assertEquals(listOf("fill in hand"), recorder.calls)
    }

    @Test
    fun `phones keep the sheet and tablets default to the panel`() {
        assertEquals(PropertiesPlacement.BOTTOM_SHEET, PropertiesPlacement.resolve(null, 390))
        assertEquals(PropertiesPlacement.BOTTOM_SHEET, PropertiesPlacement.resolve("side_panel", 411))
        assertEquals(PropertiesPlacement.SIDE_PANEL, PropertiesPlacement.resolve(null, 800))
        assertEquals(PropertiesPlacement.BOTTOM_SHEET, PropertiesPlacement.resolve("bottom_sheet", 800))
        assertEquals(PropertiesPlacement.SIDE_PANEL, PropertiesPlacement.resolve("nonsense", 600))
    }
}
