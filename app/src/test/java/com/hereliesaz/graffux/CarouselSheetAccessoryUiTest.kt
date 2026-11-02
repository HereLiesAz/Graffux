package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rotation slider rides in [CarouselSheet]'s top accessory inside [ShownWhenNotDrawing]. The ghost
 * wrapper must wrap its content there: filling the parent would eat the sheet's height and push the
 * carousel content out of view, slider or no slider.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselSheetAccessoryUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun compose(angle: Float?) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val touch = remember { CanvasTouch() }
                val chrome = rememberChromeVisibility(touch, remember { StrokeGate() }) {}
                Box(Modifier.fillMaxSize()) {
                    CarouselSheet(
                        open = true,
                        onOpenChange = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                        topAccessory = {
                            ShownWhenNotDrawing(chrome) {
                                angle?.let {
                                    RotationSlider(
                                        value = it,
                                        onStart = {},
                                        onChange = {},
                                        onEnd = {},
                                        onReset = {},
                                        label = "Rotate",
                                        modifier = Modifier.testTag("slider"),
                                    )
                                }
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxWidth().height(CONTENT_HEIGHT.dp).testTag("carousel.content"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun contentStaysVisibleAndSizedWithTheSlider() {
        compose(angle = 30f)
        rule.onNodeWithTag("slider").assertIsDisplayed()
        rule.onNodeWithTag("carousel.content").assertIsDisplayed().assertHeightIsEqualTo(CONTENT_HEIGHT.dp)
    }

    @Test
    fun contentStaysVisibleAndSizedWithNoActiveLayer() {
        compose(angle = null)
        rule.onNodeWithTag("carousel.content").assertIsDisplayed().assertHeightIsEqualTo(CONTENT_HEIGHT.dp)
    }

    private companion object {
        const val CONTENT_HEIGHT = 200
    }
}
