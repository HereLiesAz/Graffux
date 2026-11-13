package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.GestureSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gates that keep the editor's gestures off while the export frame is up. `EditorScreen` and `MainActivity`
 * call exactly these modifiers with `uiState.exportPreview`, so removing the gate from them (or the call) is what
 * these tests are written to catch; each also runs the ungated case, so a gesture that never fires cannot
 * pass them. Not covered: the call sites passing the right flag (a source-level wiring, read in review), and a
 * touch landing on a node beneath the dialog: Compose's test injection goes to each compose root directly, so a
 * window-level block cannot be exercised on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ExportPreviewWiringTest {

    @get:Rule val rule = createComposeRule()

    private fun twoFingerTap(): Int {
        var fired = 0
        rule.setContent {
            Box(
                Modifier.fillMaxSize().testTag("pad")
                    .historyGestures(StrokeGate(), exportPreview = blocked) { slot, _ ->
                        if (slot == GestureSlot.TWO_FINGER_TAP) fired++
                    },
            )
        }
        rule.onNodeWithTag("pad").performTouchInput {
            down(0, Offset(100f, 100f))
            down(1, Offset(200f, 100f))
            advanceEventTime(40)
            up(0)
            up(1)
        }
        rule.waitForIdle()
        return fired
    }

    private var blocked = false

    @Test
    fun `a two-finger tap fires normally`() {
        blocked = false
        assertTrue(twoFingerTap() >= 1)
    }

    @Test
    fun `a two-finger tap does nothing while the export frame is up`() {
        blocked = true
        assertEquals(0, twoFingerTap())
    }

    private fun twoFingerDrag(): Int {
        var calls = 0
        rule.setContent {
            Box(
                Modifier.fillMaxSize().testTag("pad")
                    .cameraNavigationUnless(blocked = blocked) { _, _, _, _ -> calls++ },
            )
        }
        rule.onNodeWithTag("pad").performTouchInput {
            down(0, Offset(100f, 200f))
            down(1, Offset(200f, 200f))
            moveBy(0, Offset(0f, 80f))
            moveBy(1, Offset(0f, 80f))
            up(0)
            up(1)
        }
        rule.waitForIdle()
        return calls
    }

    @Test
    fun `a two-finger drag moves the camera normally`() {
        blocked = false
        assertTrue(twoFingerDrag() >= 1)
    }

    @Test
    fun `a two-finger drag does not move the camera while the export frame is up`() {
        blocked = true
        assertEquals(0, twoFingerDrag())
    }

    @Test
    fun `the export frame's outline is placed in the camera's space, rotation included`() {
        val zoom = 1.5f
        val offset = Offset(30f, -20f)
        val rotation = 25f
        val rect = floatArrayOf(10f, 20f, 100f, 200f)
        var corner = Offset.Unspecified
        rule.setContent {
            Box(Modifier.fillMaxSize().viewportCamera(zoom, offset, rotation)) {
                ExportFrameLayer(
                    rect,
                    Modifier.fillMaxSize().onGloballyPositioned {
                        // Where the world point (rect.left, rect.top) lands on screen.
                        corner = it.localToRoot(Offset(rect[0], rect[1]))
                    },
                )
            }
        }
        rule.waitForIdle()
        val r = Math.toRadians(rotation.toDouble())
        val x = (zoom * (cos(r) * rect[0] - sin(r) * rect[1]) + offset.x).toFloat()
        val y = (zoom * (sin(r) * rect[0] + cos(r) * rect[1]) + offset.y).toFloat()
        assertEquals(x, corner.x, 0.5f)
        assertEquals(y, corner.y, 0.5f)
    }

    @Test
    fun `with no rectangle nothing is drawn`() {
        rule.setContent { ExportFrameLayer(null, Modifier.fillMaxSize()) }
        rule.onNodeWithTag("exportPreview.frame").assertDoesNotExist()
    }
}
