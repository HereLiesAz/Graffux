package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.design.components.LocalSurfaceBackStack
import com.hereliesaz.graffitixr.design.components.SurfaceBackStack
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Each kind of tool surface opens in its container on a phone (411dp) and a tablet (840dp), and
 * system Back closes it through the same [SurfaceBackStack] + BackHandler wiring MainActivity uses.
 */
abstract class ToolSurfaceContainersTest(private val phone: Boolean) {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)

    private fun host(dialog: @Composable (dismiss: () -> Unit) -> Unit) {
        open = true
        rule.setContent {
            val stack = remember { SurfaceBackStack() }
            CompositionLocalProvider(LocalSurfaceBackStack provides stack) {
                BackHandler(enabled = stack.isOpen) { stack.closeTop() }
                if (open) dialog { open = false }
            }
        }
    }

    private fun assertOpensInThenBackCloses(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse("Back should close the surface", open)
        rule.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun expect(phoneTag: String, tabletTag: String) = if (phone) phoneTag else tabletTag

    @Test
    fun `single value opens in an anchored popover and applies when Back closes it`() {
        var applied: Float? = null
        host { dismiss -> CornerRadiusDialog(currentRadius = 12f, onApply = { applied = it }, onDismiss = dismiss) }
        assertOpensInThenBackCloses("surface.popover")
        assertEquals(12f, applied)
    }

    @Test
    fun `picker opens in a bottom sheet on a phone and a popover on a tablet`() {
        host { dismiss -> BlendModePicker(current = BlendMode.SrcOver, onSelect = {}, onDismiss = dismiss) }
        assertOpensInThenBackCloses(expect("surface.sheet", "surface.popover"))
    }

    @Test
    fun `quick form opens in a bottom sheet on a phone and a centred sheet on a tablet`() {
        host { dismiss -> SaveProjectDialog(currentName = "Wall", onConfirm = {}, onDismiss = dismiss) }
        assertOpensInThenBackCloses(expect("surface.sheet", "surface.centered"))
    }

    @Test
    fun `big editor opens full screen on a phone and docked on a tablet`() {
        host { dismiss -> CurvesDialog(onDismissRequest = dismiss, onCurvesApplied = {}) }
        assertOpensInThenBackCloses(expect("surface.page", "surface.docked"))
    }

    @Test
    fun `timeline opens full screen on a phone and docked along the bottom on a tablet`() {
        host { dismiss -> ToolSurface(SurfaceKind.TIMELINE, "Animation", dismiss) {} }
        assertOpensInThenBackCloses(expect("surface.page", "surface.docked"))
    }

    @Test
    fun `browser opens full screen on both`() {
        host { dismiss -> ToolSurface(SurfaceKind.BROWSER, "Open", dismiss) {} }
        assertOpensInThenBackCloses("surface.page")
    }

    @Test
    fun `reference opens as a sheet on a phone and a split view on a tablet`() {
        host { dismiss -> ReferenceWindow(imageUri = null, onPickImage = {}, onDismiss = dismiss) }
        assertOpensInThenBackCloses(expect("surface.sheet", "surface.docked"))
    }

    @Test
    fun `text editing stays a bottom sheet on both for now`() {
        host { dismiss ->
            TextEditDialog(
                initialText = "Hi", initialFontName = "Roboto", initialSizeDp = 24f, initialKerningEm = 0f,
                initialColorArgb = 0xFFFFFFFF.toInt(), initialBold = false, initialItalic = false,
                initialHasOutline = false, initialHasDropShadow = false,
                onTextChange = {}, onFontChange = {}, onSizeChange = {}, onSizeStart = {}, onSizeCommit = {},
                onKerningChange = {}, onKerningStart = {}, onKerningCommit = {}, onColorChange = {},
                onStyleChange = { _, _, _, _ -> }, onAlign = {}, onDismiss = dismiss,
            )
        }
        assertOpensInThenBackCloses("surface.sheet")
    }

    @Test
    fun `no floating window is left in the phone and tablet sources`() {
        val roots = listOf(File("src/main"), File("../../app/src/main"), File("../../core/design/src/main"))
        assertTrue("source roots not found from ${File(".").absolutePath}", roots.all { it.isDirectory })
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" && it.readText().contains("FloatingWindow(") }.toList()
        }
        assertEquals(emptyList<File>(), offenders)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PhoneToolSurfaceContainersTest : ToolSurfaceContainersTest(phone = true)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h1200dp")
class TabletToolSurfaceContainersTest : ToolSurfaceContainersTest(phone = false)
