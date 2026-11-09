package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Relocate rotation point" prompt rides in the notice stack with the other popups: under the
 * status bar and a cutout, between the rails, and never on top of the Draw-surface prompt. It was
 * placed on its own, 72dp from the top whatever was there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class PivotPromptPlacementUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var cancels = 0

    private fun compose(surfaceArmed: Boolean = false) {
        WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    NoticeStack(
                        armed = surfaceArmed,
                        notice = null,
                        onCancel = {},
                        alerts = MessageController(),
                        okLabel = "OK",
                        countLabel = { "$it times" },
                        pivotArmed = true,
                        onCancelPivot = { cancels++ },
                        modifier = Modifier.fillMaxSize().padding(horizontal = BESIDE_RAIL),
                    )
                }
            }
        }
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the prompt clears the status bar and sits between the rails`() {
        compose()
        val chip = bounds("pivot.prompt")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val rail = BESIDE_RAIL.value * rule.density.density
        assertTrue("top ${chip.top} must clear the $STATUS_BAR_PX px status bar", chip.top >= STATUS_BAR_PX)
        assertTrue("left ${chip.left} is under the rail ($rail)", chip.left >= rail)
        assertTrue("right ${chip.right} is under the rail", chip.right <= root.right - rail)
    }

    @Test
    fun `the prompt and the Draw-surface prompt do not overlap`() {
        compose(surfaceArmed = true)
        val pivot = bounds("pivot.prompt")
        val surface = bounds("surface.prompt")
        assertTrue("pivot $pivot overlaps surface $surface", pivot.bottom <= surface.top || surface.bottom <= pivot.top)
    }

    @Test
    fun `Cancel and Back both leave pivot picking`() {
        compose()
        rule.onNodeWithTag("pivot.prompt.cancel").performClick()
        assertEquals(1, cancels)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(2, cancels)
    }

    private companion object {
        val BESIDE_RAIL = 72.dp
        const val STATUS_BAR_PX = 120
    }
}
