package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.LocalRailInset
import com.hereliesaz.graffitixr.design.components.RailInset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Colour sheet on a 360dp portrait phone, where the two rails (72dp a side) leave a sheet 216dp wide and a
 * 184dp body. The plane used to be a fixed 280dp box, clamped to that width and so no longer square.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = Application::class)
class TouchColorPickerLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val rail = RailInset(dockedOnLeft = true, toolWidth = 72.dp, layersWidth = 72.dp)
    private val recents = (1..13).map { Color(0xFF000000 or (it * 0x131313).toLong()) }

    private fun show(fontScale: Float = 1f, page: PickerPage = PickerPage.CLASSIC) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalRailInset provides rail,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    TouchColorPicker(
                        currentColor = Color(0xFF3366CC),
                        onSelectColor = {},
                        onCommitColor = {},
                        recentColors = recents,
                        onDeleteRecentColor = {},
                        onDismiss = {},
                        onOpenMixer = {},
                        page = page,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** Where the node is laid out, not clipped by the sheet's scroll: a swatch scrolled out of view still has it. */
    private fun SemanticsNode.laidOut(): Rect =
        Rect(positionInRoot, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

    private fun bounds(tag: String): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().laidOut()

    private fun px(dp: Float): Float = dp * rule.density.density

    private fun assertInside(inner: Rect, outer: Rect, what: String) {
        val inside = inner.left >= outer.left - 1f && inner.right <= outer.right + 1f
        assertTrue("$what $inner is not inside $outer", inside)
    }

    private fun assertFits() {
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val sheet = bounds("surface.sheet")
        val plane = bounds("touchColor.plane")
        val railPx = px(72f)
        assertEquals("the plane is square", plane.width, plane.height, 1f)
        assertTrue("the sheet starts clear of the left rail", sheet.left >= railPx - 1f)
        assertTrue("the sheet ends clear of the right rail", sheet.right <= screen.width - railPx + 1f)
        assertTrue("the plane is clear of the left rail", plane.left >= railPx)
        assertTrue("the plane is clear of the right rail", plane.right <= screen.width - railPx)
        assertInside(plane, sheet, "plane")
        assertInside(bounds("touchColor.hue"), sheet, "hue strip")
        assertInside(bounds("touchColor.recent"), sheet, "recent colours")
        rule.onAllNodesWithTag("touchColor.recent.swatch", useUnmergedTree = true).fetchSemanticsNodes()
            .forEach { assertInside(it.laidOut(), sheet, "a recent swatch") }
        // Phone sheet 216dp, less its 16dp gutters, is all the plane has.
        assertEquals("the plane takes the sheet's width", px(184f), plane.width, 1f)
        // Never narrower than a finger-friendly field.
        assertTrue(plane.width >= px(120f))
    }

    @Test
    fun `on a 360dp phone the plane is square, in the sheet and clear of both rails`() {
        show()
        assertFits()
    }

    @Test
    fun `the same holds at twice the font size`() {
        show(fontScale = 2f)
        assertFits()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h1000dp-xxhdpi", application = Application::class)
    fun `on a tablet the plane stops at its named maximum and is still square`() {
        show()
        val plane = bounds("touchColor.plane")
        assertEquals(plane.width, plane.height, 1f)
        assertEquals(px(PickerPlaneMax.value), plane.width, 1f)
    }

    @Test
    fun `disc takes available square space between rails`() {
        show(page = PickerPage.DISC)
        val disc = bounds("touchColor.disc")
        assertEquals(disc.width, disc.height, 1f)
        assertInside(disc, bounds("surface.sheet"), "disc")
    }

    @Test
    fun `value page exposes numeric controls and hex entry`() {
        show(page = PickerPage.VALUE)
        val sheet = bounds("surface.sheet")
        assertInside(bounds("touchColor.value.H"), sheet, "hue field")
        assertInside(bounds("touchColor.value.Red"), sheet, "red field")
        assertInside(bounds("touchColor.hex"), sheet, "hex field")
    }

    @Test
    fun `the plane size is the width available up to the maximum, never negative`() {
        assertEquals(152.dp, pickerPlaneSize(152.dp))
        assertEquals(PickerPlaneMax, pickerPlaneSize(900.dp))
        assertEquals(0.dp, pickerPlaneSize((-5).dp))
    }
}
