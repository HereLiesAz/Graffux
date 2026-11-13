package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.design.R
import com.hereliesaz.graffitixr.design.theme.rememberExportPreviewStrings
import com.hereliesaz.graffitixr.feature.editor.export.artboardRect
import com.hereliesaz.graffitixr.feature.editor.export.exportFrameRect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The export frame: its rectangle is the one the exporter keeps (artboard, or the Crop tab's crop of it, with
 * no viewport in it), and the controls are 48dp, labelled, and in a dialog window (which is what is modal).
 * The outline's placement in the camera is in ExportPreviewWiringTest. Not covered: the scrim's pixels, the
 * dialog's insets on a device with a cutout, TalkBack's focus actually being unable to leave the dialog (a platform
 * behaviour of a dialog window, not exercised here), and the exported bitmap matching the rectangle on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ExportFramePreviewTest {

    @get:Rule val rule = createComposeRule()

    private var confirmed = 0
    private var cancelled = 0

    private fun show() = rule.setContent {
        ExportFrameControls(
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
    fun `Back cancels`() {
        show()
        rule.waitForIdle()
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(0, confirmed)
    }

    @Test
    fun `the buttons are at least 48dp`() {
        show()
        for (tag in listOf("exportPreview.confirm", "exportPreview.cancel")) {
            rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun `both buttons have TalkBack labels`() {
        show()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNode(hasContentDescription(context.getString(R.string.export_preview_confirm_description)))
            .assertExists()
        rule.onNode(hasContentDescription(context.getString(R.string.export_preview_cancel_description)))
            .assertExists()
    }

    // ── The rectangle: what the exporter keeps ───────────────────────────────────────────────────

    @Test
    fun `with no crop the frame is the artboard, and the viewport is not an input`() {
        val rect = exportFrameRect(1080, 2400, 1000, 1000, DocumentCrop())
        assertEquals(artboardRect(1080, 2400, 1000, 1000).toList(), rect.toList())
    }

    @Test
    fun `with a crop the frame is the crop's rectangle, the one the export cuts`() {
        val crop = DocumentCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.7f)
        val art = DocumentCropRender.artboard(1080, 2400, 1000, 1000)
        val kept = DocumentCropRender.cropRect(crop, art)
        val rect = exportFrameRect(1080, 2400, 1000, 1000, crop)
        assertEquals(kept.left, rect[0], 0.01f)
        assertEquals(kept.top, rect[1], 0.01f)
        assertEquals(kept.width(), rect[2], 0.01f)
        assertEquals(kept.height(), rect[3], 0.01f)
    }

    @Test
    fun `a crop with only a straighten or flip but the full rectangle still frames the artboard`() {
        val crop = DocumentCrop(straighten = 5f)
        val rect = exportFrameRect(1080, 2400, 1000, 1000, crop)
        assertEquals(artboardRect(1080, 2400, 1000, 1000).toList(), rect.toList())
    }
}
