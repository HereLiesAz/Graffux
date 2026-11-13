package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.rememberExportPreviewStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The export frame's controls: Export and Cancel call back once each, are at least 48dp, and carry the
 * TalkBack labels. Not covered: the scrim's pixels (that it dims exactly the area outside the artboard
 * rectangle) and its look over a real 3D canvas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ExportFramePreviewTest {

    @get:Rule val rule = createComposeRule()

    private var confirmed = 0
    private var cancelled = 0

    private fun show() = rule.setContent {
        ExportFramePreview(
            documentWidth = 1080,
            documentHeight = 1920,
            strings = rememberExportPreviewStrings(),
            onConfirm = { confirmed++ },
            onCancel = { cancelled++ },
        )
    }

    @Test
    fun `Export and Cancel each call back once`() {
        show()
        rule.onNodeWithTag("exportPreview.confirm").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, cancelled)
        rule.onNodeWithTag("exportPreview.cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `the buttons are at least 48dp`() {
        show()
        for (tag in listOf("exportPreview.confirm", "exportPreview.cancel")) {
            rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun `the frame and both buttons have TalkBack labels`() {
        show()
        rule.onNode(hasContentDescription("Export the area inside the frame")).assertExists()
        rule.onNode(hasContentDescription("Cancel the export")).assertExists()
        rule.onNode(hasContentDescription("Export frame: the area that will be exported")).assertExists()
    }
}
