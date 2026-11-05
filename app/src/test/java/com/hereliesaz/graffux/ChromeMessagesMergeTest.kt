@file:Suppress("MagicNumber", "MaxLineLength", "FunctionNaming")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val STROKE = "That stroke didn't paint. Try it again."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w540dp-h1200dp-xhdpi", application = android.app.Application::class)
class ChromeMessagesMergeTest {
    @get:Rule val rule = createComposeRule()

    private fun host(controller: MessageController) = rule.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
            ) {
                MessageHost(
                    controller,
                    okLabel = "OK",
                    modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }

    @Test
    fun `three identical notices make one toast with count 3`() {
        val c = MessageController()
        host(c)
        repeat(3) { c.show(kind = MessageKind.WARNING, message = STROKE) }
        rule.waitForIdle()
        rule.onAllNodesWithTag("chrome.message").assertCountEquals(1)
        rule.onNodeWithTag("chrome.message.count", useUnmergedTree = true).assertTextEquals("×3")
    }

    @Test
    fun `two different messages make two toasts`() {
        val c = MessageController()
        host(c)
        c.show(kind = MessageKind.WARNING, message = STROKE)
        c.show(kind = MessageKind.WARNING, message = "Something else")
        c.show(kind = MessageKind.NOTICE, message = STROKE) // same text, other kind
        rule.waitForIdle()
        rule.onAllNodesWithTag("chrome.message").assertCountEquals(3)
        rule.onAllNodesWithTag("chrome.message.count", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `OK clears the merged toast`() {
        val c = MessageController()
        host(c)
        repeat(3) { c.show(kind = MessageKind.WARNING, message = STROKE) }
        rule.waitForIdle()
        rule.onNodeWithTag("chrome.message.ok").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("chrome.message").assertCountEquals(0)
    }

    @Test
    fun `the count is in the accessibility label`() {
        val c = MessageController()
        host(c)
        repeat(3) { c.show(kind = MessageKind.WARNING, message = STROKE) }
        rule.waitForIdle()
        rule.onAllNodesWithTag("chrome.message").assertCountEquals(1)
        rule.onAllNodes(hasContentDescription("$STROKE 3 times")).assertCountEquals(1)
    }

    @Test
    fun `a single notice shows no count`() {
        val c = MessageController()
        host(c)
        c.show(kind = MessageKind.WARNING, message = STROKE)
        rule.waitForIdle()
        rule.onAllNodesWithTag("chrome.message.count", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription(STROKE)).assertCountEquals(1)
    }

    @Test
    fun `merging restarts a notice's auto-dismiss timer`() {
        val c = MessageController()
        host(c)
        rule.mainClock.autoAdvance = false
        c.show(message = "Saved")
        rule.mainClock.advanceTimeBy(3_000)
        c.show(message = "Saved")
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onAllNodesWithTag("chrome.message").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(1_500)
        rule.onAllNodesWithTag("chrome.message").assertCountEquals(0)
    }

    @Test
    fun `render the merged toast`() {
        val c = MessageController()
        host(c)
        repeat(3) { c.show(kind = MessageKind.WARNING, message = STROKE) }
        c.show(kind = MessageKind.NOTICE, message = "Saved poster.pdf to Downloads")
        rule.waitForIdle()
        val dir = System.getenv("AUDIT_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "merged-toast.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
