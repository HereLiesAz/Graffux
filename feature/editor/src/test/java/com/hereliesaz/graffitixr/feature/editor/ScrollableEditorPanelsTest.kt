package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.hereliesaz.graffitixr.common.azphalt.AZPHALT_SPEC_VERSION
import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.UiControl
import com.hereliesaz.graffitixr.common.azphalt.UiSchema
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for Compose's unbounded-height scroll crash. The real editor mounts extension
 * panels in a bottom-aligned, wrap-content Column; a lazy list or verticalScroll measured there
 * without its own finite height throws before drawing its first item.
 *
 * Deliberately mount in a wrap-content Column rather than a bounded Scaffold: the latter
 * would supply a viewport accidentally, hiding the exact failure these tests prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ScrollableEditorPanelsTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `extension list scrolls inside a wrap-content host`() {
        val installed = (1..60).map { number ->
            InstalledExtension(
                manifest = AzphaltManifest(
                    azphalt = AZPHALT_SPEC_VERSION,
                    id = "ext.$number",
                    name = "Extension $number",
                    version = "1.0",
                    kind = ExtensionKind.CODE,
                    license = "MIT",
                    compat = "*",
                ),
                dir = "",
                installedAt = 0L,
            )
        }
        rule.setContent {
            Column {
                ExtensionsPanel(
                    extensions = installed,
                    activeExtension = null,
                    contributionsOf = { emptyList() },
                    onSelect = {},
                    onSelectContribution = { _, _ -> },
                    onBack = {},
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("Extension 1").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(59)
        rule.onNodeWithText("Extension 60").assertIsDisplayed()
    }

    @Test
    fun `contribution controls are bounded while run remains available`() {
        val controls = (1..60).map { number ->
            UiControl(type = "toggle", key = "control.$number", label = "Control $number")
        }
        rule.setContent {
            Column {
                ExtensionParamsPanel(
                    contributionName = "Long contribution",
                    schema = UiSchema(controls),
                    params = emptyMap(),
                    onParamChanged = { _, _ -> },
                    onRun = {},
                    onBack = {},
                    onClose = {},
                )
            }
        }
        rule.onNodeWithText("Control 1").assertIsDisplayed()
        rule.onNodeWithText("Run").assertIsDisplayed()
    }

    @Test
    fun `document size uses the floating window's single scroll viewport`() {
        rule.setContent {
            DocumentSizeDialog(
                currentWidth = 1024,
                currentHeight = 768,
                onConfirm = { _, _ -> },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Document size").assertExists()
    }
}
