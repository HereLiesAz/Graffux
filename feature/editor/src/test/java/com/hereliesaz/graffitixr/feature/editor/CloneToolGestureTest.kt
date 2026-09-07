package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the stale-`pickingCloneSource` bug: [DrawingCanvas]'s `pointerInput` used to key
 * only on `activeTool` and `nextFrameMs`, so the coroutine it launched captured whatever
 * `pickingCloneSource` was true when the CLONE tool was selected and never saw it flip to `false`.
 *
 * `EditorScreen` recomputes `pickingCloneSource` fresh every recomposition
 * (`activeTool == CLONE && cloneSource == null`) and passes the new value down as an ordinary
 * parameter — exactly like this test's own [mutableStateOf], reproducing the same shape without
 * needing a real `EditorViewModel`/layer stack. If `pickingCloneSource` is not also a
 * `pointerInput` key, the *parameter* recomposes but the already-running gesture coroutine keeps
 * reading its first captured value, so every gesture after the first source-pick is
 * misclassified as "still picking the source" instead of a paint stroke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class CloneToolGestureTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `after the clone source is picked, the next drag paints instead of re-picking`() {
        val picks = mutableListOf<Offset>()
        val painted = mutableListOf<Offset>()
        var pickingCloneSource by mutableStateOf(true)

        rule.setContent {
            DrawingCanvas(
                activeTool = Tool.CLONE,
                brushSize = 20f,
                activeColor = Color.Red,
                layerBitmapKey = null,
                gate = StrokeGate(),
                modifier = Modifier.fillMaxSize(),
                onStrokeStart = { sample, _ -> painted += Offset(sample.x, sample.y) },
                onStrokePoint = { sample -> painted += Offset(sample.x, sample.y) },
                onStrokeEnd = {},
                onStrokeCancel = {},
                onFillTap = { _, _ -> },
                pickingCloneSource = pickingCloneSource,
                onPickCloneSource = { at ->
                    picks += at
                    // Mirrors EditorScreen: SetCloneSource lands and pickingCloneSource recomputes
                    // to false, all before the user's next gesture begins.
                    pickingCloneSource = false
                },
                onEyedropStart = {},
                onEyedropSample = {},
                onEyedropEnd = {},
            )
        }
        rule.waitForIdle()

        // 1. Tap (no movement past slop) while armed but unaimed -> picks the clone source.
        rule.onRoot().performTouchInput {
            down(0, Offset(150f, 400f))
            up(0)
        }
        rule.waitForIdle()

        assertEquals("the tap should have picked a clone source", 1, picks.size)
        assertTrue("nothing should have painted yet", painted.isEmpty())
        assertTrue("pickingCloneSource should have flipped false", !pickingCloneSource)

        // 2. A drag, well past the touch slop, now that the source is aimed.
        rule.onRoot().performTouchInput {
            down(0, Offset(150f, 400f))
            moveTo(0, Offset(220f, 460f))
            moveTo(0, Offset(280f, 500f))
            up(0)
        }
        rule.waitForIdle()

        assertEquals(
            "the drag must not be misread as another clone-source pick",
            1,
            picks.size,
        )
        assertTrue(
            "the drag should have painted a stroke, got ${painted.size} points",
            painted.size >= 2,
        )
    }
}
