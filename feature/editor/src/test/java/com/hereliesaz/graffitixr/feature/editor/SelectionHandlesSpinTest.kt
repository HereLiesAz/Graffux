package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the ANR after a brush stroke: [SelectionHandles]' gesture block returned before
 * awaiting anything when the active layer had no screen corners (no pixels yet, as on a fresh
 * layer). awaitEachGesture then looped straight back in with no pointer pressed, spinning the main
 * thread and allocating a continuation per turn until Android killed the app. With the down awaited
 * first, a stroke over such a layer ends normally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SelectionHandlesSpinTest {

    @get:Rule
    val rule = createComposeRule()

    @Test(timeout = 20_000)
    fun `a stroke over a layer with no corners does not spin the gesture loop`() {
        rule.setContent {
            SelectionHandles(
                activeLayer = Layer(id = "empty", name = "Empty"),
                viewportOffset = Offset.Zero,
                viewportZoom = 1f,
                viewportRotation = 0f,
                onGestureStart = {},
                onResize = {},
                onRotate = {},
                onMove = {},
                onGestureEnd = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        rule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(40f, 40f))
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
    }
}
