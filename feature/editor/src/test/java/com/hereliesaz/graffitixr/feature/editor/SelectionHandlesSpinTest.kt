package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
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
 *
 * SDK 33 on purpose: every other editor test runs on 34, so this one gets its own Robolectric
 * sandbox and never inherits an uncaught coroutine exception another test left behind (which the
 * Compose rule reports as UncaughtExceptionsBeforeTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp")
class SelectionHandlesSpinTest {

    @get:Rule
    val rule = createComposeRule()

    // The timeout turns the old failure mode (a hung main loop) into a failing test instead of a
    // stalled build.
    @Test(timeout = 20_000)
    fun `a stroke over a layer with no corners does not spin the gesture loop`() {
        var callbacks = 0
        rule.setContent {
            SelectionHandles(
                activeLayer = Layer(id = "empty", name = "Empty"),
                viewportOffset = Offset.Zero,
                viewportZoom = 1f,
                viewportRotation = 0f,
                onGestureStart = { callbacks++ },
                onResize = { callbacks++ },
                onRotate = { callbacks++ },
                onMove = { callbacks++ },
                onGestureEnd = { callbacks++ },
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
        // A layer with no corners has no handles: the stroke must pass straight through them.
        assertEquals(0, callbacks)
    }
}
