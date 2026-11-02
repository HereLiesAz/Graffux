package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.feature.editor.CHROME_FADE_IN_MS
import com.hereliesaz.graffitixr.feature.editor.CHROME_FADE_OUT_MS
import com.hereliesaz.graffitixr.feature.editor.CHROME_GHOST_ALPHA
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hidden chrome is a faint ghost, not gone: it fades to [CHROME_GHOST_ALPHA], takes no touches (a
 * stroke over it reaches the canvas), and fades back [DRAWING_UI_RETURN_DELAY_MS] after the stroke.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ChromeGhostUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val gate = StrokeGate()
    private var buttonClicks = 0
    private var canvasDowns = 0

    private fun compose() {
        rule.setContent {
            val touch = remember { CanvasTouch() }
            val chrome = rememberChromeVisibility(touch, gate) {}
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("canvas")
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.changes.any { it.pressed && !it.previousPressed }) canvasDowns++
                                }
                            }
                        },
                )
                ShownWhenNotDrawing(chrome) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(120.dp)
                            .background(Color.White)
                            .clickable { buttonClicks++ }
                            .testTag("ghost.button"),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun frames(ms: Long) {
        val end = rule.mainClock.currentTime + ms
        while (rule.mainClock.currentTime < end) {
            Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot().fetchSemanticsNode()
        }
    }

    /** The red channel at the screen's centre, where the white button is, as 0..1. */
    private fun centreLevel(): Float {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        return android.graphics.Color.red(bmp.getPixel(bmp.width / 2, bmp.height / 2)) / 255f
    }

    private fun shown() = rule.onAllNodesWithTag("ghost.button").fetchSemanticsNodes().isNotEmpty()

    private fun startStroke() {
        rule.mainClock.autoAdvance = false
        gate.strokeActive = true
        frames(CHROME_FADE_OUT_MS + 50L)
    }

    @Test
    fun `the chrome fades to the ghost alpha while drawing`() {
        compose()
        assertEquals(1f, centreLevel(), 0.02f)
        startStroke()
        assertEquals("a faint silhouette, not gone", CHROME_GHOST_ALPHA, centreLevel(), 0.02f)
        assertTrue(CHROME_GHOST_ALPHA in 0.06f..0.10f)
        assertFalse("no semantics while ghosted", shown())
    }

    @Test
    fun `touches over the ghost reach the canvas`() {
        compose()
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("shown, the button takes the tap", 1, buttonClicks)
        val before = canvasDowns
        startStroke()
        rule.onNodeWithTag("canvas").performTouchInput {
            down(center)
            moveBy(Offset(10f, 0f))
            up()
        }
        frames(32)
        assertEquals("the ghost took nothing", 1, buttonClicks)
        assertEquals("the canvas got the touch", before + 1, canvasDowns)
    }

    @Test
    fun `the chrome returns after the hold and fades back in`() {
        compose()
        startStroke()
        gate.strokeActive = false
        frames(DRAWING_UI_RETURN_DELAY_MS - 100)
        assertFalse("still ghosted during the hold", shown())
        assertEquals(CHROME_GHOST_ALPHA, centreLevel(), 0.02f)
        frames(100L + CHROME_FADE_IN_MS + 50L)
        assertTrue("back after the hold", shown())
        assertEquals("fully faded back in", 1f, centreLevel(), 0.02f)
        rule.mainClock.autoAdvance = true
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("and takes taps again", 1, buttonClicks)
    }
}
