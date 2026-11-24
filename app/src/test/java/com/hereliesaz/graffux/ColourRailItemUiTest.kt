package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rail's colour item, rendered in the real tool rail pill: while the Colour sheet is open it is larger, and in
 * either state it lies wholly inside the pill's border and is not clipped by it, whether the system animates
 * (the animator scale is normal) or not (it is 0, so the size lands at once).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ColourRailItemUiTest {
    /** The animator duration scale the composition's animations see; the test sets it before composing. */
    private class AnimatorScale : MotionDurationScale {
        var factor = 1f
        override val scaleFactor: Float get() = factor
    }

    private val animator = AnimatorScale()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>(effectContext = animator)

    private var sheetOpen by mutableStateOf(false)

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }

    private fun show(animatorScale: Float, open: Boolean) {
        animator.factor = animatorScale
        sheetOpen = open
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(
                    vm = actions,
                    uiState = EditorUiState(showColorPicker = sheetOpen),
                    lit = emptySet(),
                    secondary = emptySet(),
                    callbacks = ToolRailCallbacks(
                        onToolOptions = {},
                        onToolGroup = {},
                        onBrushMenu = {},
                        onBrush = {},
                        onPackWheelToggle = {},
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun node(tag: String): SemanticsNode = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun SemanticsNode.laidOut(): Rect =
        Rect(positionInRoot, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

    private fun px(dp: Float): Float = dp * rule.density.density

    /** The item is whole (not clipped by an ancestor) and between the pill's 1dp borders. */
    private fun assertInsideThePill(): Float {
        val item = node("rail.tool.color")
        val pill = node("rail.tools.pill").laidOut()
        val box = item.laidOut()
        assertEquals("not clipped: its visible width is its laid-out width", box.width, item.boundsInRoot.width, 1f)
        assertEquals("not clipped: its visible height is its laid-out height", box.height, item.boundsInRoot.height, 1f)
        val border = px(1f)
        assertTrue("left ${box.left} clear of the border at ${pill.left}", box.left >= pill.left + border - 1f)
        assertTrue("right ${box.right} clear of the border at ${pill.right}", box.right <= pill.right - border + 1f)
        assertEquals("centred in the pill", pill.center.x, box.center.x, 1f)
        return box.width
    }

    private val closedTouch = px(RailTouchSize.value)
    private val openTouch get() = px(railTouchSize(ColourItemOpenFace).value)

    @Test
    fun `closed it is the normal rail button, inside the pill`() {
        show(animatorScale = 1f, open = false)
        assertEquals(closedTouch, assertInsideThePill(), 1f)
    }

    @Test
    fun `open it is larger and still inside the pill`() {
        show(animatorScale = 1f, open = true)
        rule.waitForIdle()
        assertEquals(openTouch, assertInsideThePill(), 1f)
        assertTrue(openTouch > closedTouch)
    }

    @Test
    fun `with animations off it lands on the open size at once, inside the pill`() {
        show(animatorScale = 0f, open = false)
        rule.mainClock.autoAdvance = false
        sheetOpen = true
        // The frames it takes to recompose and measure; the size is only ever the closed one or the open one.
        val seen = (1..8).map {
            rule.mainClock.advanceTimeByFrame()
            node("rail.tool.color").size.width.toFloat()
        }
        val whole = seen.all { abs(it - closedTouch) < 1f || abs(it - openTouch) < 1f }
        assertTrue("no in-between size: $seen", whole)
        assertEquals("it lands on the open size", openTouch, assertInsideThePill(), 1f)
    }

    @Test
    fun `with animations on it grows through the pill without ever leaving it`() {
        show(animatorScale = 1f, open = false)
        rule.mainClock.autoAdvance = false
        sheetOpen = true
        val sizes = (1..30).map {
            rule.mainClock.advanceTimeBy(16)
            assertInsideThePill()
        }
        assertEquals("it ends at the open size, not past it", openTouch, sizes.max(), 1f)
        assertTrue("it was animated: $sizes", sizes.any { it > closedTouch + 1f && it < openTouch - 1f })
    }

    @Test
    fun `the open face leaves a ring inside the pill`() {
        // 46dp face + 2dp ring each side is the 50dp the pill holds between its 1dp borders.
        assertEquals(RailWidth - 2.dp, railTouchSize(ColourItemOpenFace))
    }
}
