package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The hairline rotation slider over the sheet: drag, snap at 0, double-tap reset, semantics, visibility. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class RotationSliderUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var angle by mutableFloatStateOf(0f)
    private var starts = 0
    private var ends = 0
    private var resets = 0

    private fun setSlider(initial: Float = 0f) {
        angle = initial
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    RotationSlider(
                        value = angle,
                        onStart = { starts++ },
                        onChange = { angle = it },
                        onEnd = { ends++ },
                        onReset = { resets++; angle = 0f },
                        label = "Rotate layer",
                    )
                }
            }
        }
    }

    @Test
    fun `a drag rotates live, one start and one end`() {
        setSlider()
        rule.onNodeWithTag("rotationSlider").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(width / 8f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width / 8f, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue("dragged right turns clockwise: $angle", angle > 30f)
        assertEquals(1, starts)
        assertEquals(1, ends)
    }

    @Test
    fun `values near 0 snap to 0, and detents are found`() {
        assertEquals(0f, snapRotation(1.9f))
        assertEquals(0f, snapRotation(-2f))
        assertEquals(5f, snapRotation(5f))
        assertEquals(180f, snapRotation(400f))
        assertTrue(crossesRotationDetent(40f, 50f))
        assertTrue(crossesRotationDetent(-1f, 3f))
        assertFalse(crossesRotationDetent(10f, 20f))
        assertFalse(crossesRotationDetent(45f, 50f))
    }

    @Test
    fun `a double-tap on the thumb resets`() {
        setSlider(initial = 90f)
        rule.onNodeWithTag("rotationSlider").performTouchInput {
            doubleClick(androidx.compose.ui.geometry.Offset(width * 0.75f, centerY))
        }
        rule.waitForIdle()
        assertEquals(1, resets)
        assertEquals(0f, angle)
    }

    @Test
    fun `it is a range slider with set-value support`() {
        setSlider(initial = 30f)
        val node = rule.onNodeWithTag("rotationSlider").fetchSemanticsNode()
        val info = node.config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(30f, info.current)
        assertEquals(-180f..180f, info.range)
        assertTrue(node.config.contains(SemanticsActions.SetProgress))
        rule.onNodeWithTag("rotationSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(-45f) }
        rule.waitForIdle()
        assertEquals(-45f, angle)
        assertEquals(1, starts)
        assertEquals(1, ends)
    }

    @Test
    fun `it shows only with the sheet`() {
        var open by mutableStateOf(true)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (open) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(
                            open = true,
                            onOpenChange = {},
                            topAccessory = {
                                RotationSlider(0f, {}, {}, {}, {}, label = "Rotate layer")
                            },
                        ) { Box(Modifier.height(120.dp)) }
                    }
                }
            }
        }
        rule.onNodeWithTag("rotationSlider").assertIsDisplayed()
        open = false
        rule.waitForIdle()
        rule.onNodeWithTag("rotationSlider").assertDoesNotExist()
    }
}
