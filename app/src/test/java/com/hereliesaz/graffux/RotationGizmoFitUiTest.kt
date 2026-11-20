@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The enlarged rotation triangle fits the window on a 360dp-wide phone, portrait and landscape, either hand: fully
 * on screen, off the tool rail beside it, and with room left for the layers rail above it (xhdpi, 1dp = 2px).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi", application = Application::class)
class RotationGizmoFitUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val d = 2f

    private fun show(rightHanded: Boolean, onOccupied: (Int) -> Unit = {}) {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                RotationGizmoDock(rightHanded = rightHanded, onOccupied = onOccupied) {
                    RotationGizmo({ _, _ -> }, {}, { _, _ -> }, scale = it)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertFits(windowWdp: Float, windowHdp: Float, right: Boolean) {
        var occupied = 0
        show(right) { occupied = it }
        val b = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        val ww = windowWdp * d
        val wh = windowHdp * d
        assertTrue("inside the window: $b", b.left >= 0f && b.top >= 0f && b.right <= ww && b.bottom <= wh)
        val gap = GizmoEdgeMargin.value * d
        assertTrue("keeps the edge margin: $b", if (right) b.right <= ww - gap else b.left >= gap)
        // off the tool rail, which is on the other side (12dp inset + 56dp wide)
        val railPx = (RailSideInset + ToolRailWidth).value * d
        assertTrue("clear of the tool rail: $b", if (right) b.left >= railPx else b.right <= ww - railPx)
        // the layers rail, on the gizmo's side, stops above it and still has room for a button
        val clearance = gizmoRailClearancePx(occupied, 0, gap.toInt())
        val room = wh - LayersRailTopInset.value * d - clearance
        assertTrue("layers rail keeps ${room}px", room >= RailTouchSize.value * d)
        assertEquals(wh - b.top, occupied.toFloat(), 1f)
    }

    @Test
    fun `360dp portrait, right-handed`() = assertFits(360f, 740f, true)

    @Test
    fun `360dp portrait, left-handed`() = assertFits(360f, 740f, false)

    @Test
    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    fun `360dp-high landscape, right-handed`() = assertFits(740f, 360f, true)

    @Test
    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    fun `360dp-high landscape, left-handed`() = assertFits(740f, 360f, false)

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `short 360 x 640dp portrait, right-handed`() = assertFits(360f, 640f, true)

    // ---- tablet, RTL, large font, real insets ----

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `tablet, right-handed`() = assertFits(1280f, 800f, true)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `tablet, left-handed`() = assertFits(1280f, 800f, false)

    @Test
    @Config(qualifiers = "ldrtl-w360dp-h740dp-xhdpi")
    fun `RTL, the handedness is physical and right-handed is still the right corner`() {
        assertFits(360f, 740f, true)
        val b = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        assertEquals(360f * d - GizmoEdgeMargin.value * d, b.right, 1f)
    }

    @Test
    @Config(qualifiers = "ldrtl-w360dp-h740dp-xhdpi")
    fun `RTL, left-handed is the left corner`() {
        assertFits(360f, 740f, false)
        assertEquals(GizmoEdgeMargin.value * d, rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot.left, 1f)
    }

    @Test
    @Config(fontScale = 2f)
    fun `at 2x font scale the box is unchanged (it is sized in dp) and still fits`() {
        assertFits(360f, 740f, true)
        val b = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        assertEquals((GizmoWidth.value * GIZMO_DEFAULT_SCALE + GizmoBand.value) * d, b.width, 1f)
    }

    private fun inject(rightHanded: Boolean, nav: Insets, cutout: Insets, gestures: Insets) {
        show(rightHanded)
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), nav)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), nav)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
                .setInsets(WindowInsetsCompat.Type.systemGestures(), gestures)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
            fun walk(v: android.view.View) {
                ViewCompat.dispatchApplyWindowInsets(v, insets)
                (v as? android.view.ViewGroup)?.let { g -> for (i in 0 until g.childCount) walk(g.getChildAt(i)) }
            }
            walk(view)
        }
        rule.waitForIdle()
    }

    private fun insetCase(right: Boolean) {
        // 740 x 360dp landscape: 48dp nav bar at the bottom, 40dp cutout on the left, 24dp gesture strip each side
        inject(
            right,
            nav = Insets.of(0, 0, 0, (48 * d).toInt()),
            cutout = Insets.of((40 * d).toInt(), 0, 0, 0),
            gestures = Insets.of((24 * d).toInt(), 0, (24 * d).toInt(), 0),
        )
        val b = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        val m = GizmoEdgeMargin.value * d
        assertTrue("bottom ${b.bottom} above the nav bar", b.bottom <= 360f * d - 48f * d - m + 1f)
        if (right) {
            assertTrue("right ${b.right} inside the gesture strip", b.right <= 740f * d - 24f * d - m + 1f)
        } else {
            assertTrue("left ${b.left} past the cutout", b.left >= 40f * d + m - 1f)
        }
    }

    @Test
    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    fun `with a nav bar, a cutout and gesture strips injected, right-handed stays inside them`() = insetCase(true)

    @Test
    @Config(qualifiers = "w740dp-h360dp-land-xhdpi")
    fun `with a nav bar, a cutout and gesture strips injected, left-handed stays inside them`() = insetCase(false)

    @Test
    fun `gizmoCorner with real-looking insets keeps the whole box inside them, both hands`() {
        val wPx = (740 * d).toInt()
        val hPx = (360 * d).toInt()
        val boxW = ((GizmoWidth.value * GIZMO_DEFAULT_SCALE + GizmoBand.value) * d).toInt()
        val boxH = ((GizmoHeight.value * GIZMO_DEFAULT_SCALE + GizmoBand.value) * d).toInt()
        val margin = (GizmoEdgeMargin.value * d).toInt()
        val left = (40 * d).toInt() + margin // the cutout is wider than the 24dp gesture strip
        val right = (24 * d).toInt() + margin
        val bottom = (48 * d).toInt() + margin
        for (rh in listOf(true, false)) {
            val at = gizmoCorner(wPx, hPx, boxW, boxH, rh, left, right, bottom)
            assertTrue("x ${at.x} inside the left inset", at.x >= left || rh)
            assertTrue("right ${at.x + boxW} inside the right inset", at.x + boxW <= wPx - right || !rh)
            assertEquals(hPx - bottom - boxH, at.y)
            assertTrue(at.y >= 0)
        }
    }

    // ---- the set wheel at the bigger size ----

    private val wheelSections = listOf(
        CarouselTabSection(
            "ALL",
            listOf("Favorites", "Round", "Flat").map { CarouselTabGroup(it, it, CarouselCategory.BRUSHES, emptyList()) },
        ),
    )

    private fun wheelCase(right: Boolean) {
        val bounds = mutableStateOf<Rect?>(null)
        rule.setContent {
            CompositionLocalProvider(LocalSetWheelOnRight provides right, LocalSetWheelBounds provides bounds) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselTabList(wheelSections, "Favorites", onSelect = {})
                    }
                    RotationGizmoDock(rightHanded = right, avoid = { bounds.value }) {
                        RotationGizmo({ _, _ -> }, {}, { _, _ -> }, scale = it)
                    }
                }
            }
        }
        rule.waitForIdle()
        val box = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        val wheel = rule.onNodeWithTag("carousel.wheel").fetchSemanticsNode().boundsInRoot
        assertTrue("box ${box.top}..${box.bottom} is above the wheel top ${wheel.top}", box.bottom <= wheel.top + 0.5f)
        assertTrue("and on screen: top ${box.top}", box.top >= 0f)
        assertTrue("and does not overlap it", !box.overlaps(wheel))
    }

    @Test
    fun `right-handed, the bigger triangle moves up off the set wheel at 360dp`() = wheelCase(true)

    @Test
    fun `left-handed, the bigger triangle moves up off the set wheel at 360dp`() = wheelCase(false)

    // ---- the sheet's name pill: what is guaranteed where the box sits under it ----

    @Test
    fun `where the box sits under the name pill, bands win and the rest reaches the sheet`() {
        val sheetDowns = IntArray(1)
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); sheetDowns[0]++ } },
                    contentAlignment = Alignment.BottomCenter,
                ) { NamePill("A brush with a name long enough to fill the whole pill") }
                RotationGizmoDock(rightHanded = true) { RotationGizmo({ _, _ -> }, {}, { _, _ -> }, scale = it) }
            }
        }
        rule.waitForIdle()
        val pill = rule.onNodeWithTag("carousel.namePill").fetchSemanticsNode().boundsInRoot
        val box = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot
        // the pill is centred and at most PillMaxWidth wide: on a 360dp phone its widest extent reaches under the box
        assertTrue(pill.width <= PillMaxWidth.value * d + 1f)
        val widestRight = pill.center.x + PillMaxWidth.value * d / 2f
        assertTrue("the box (${box.left}) can sit under the pill (to $widestRight)", box.left < widestRight)
        // a touch on the pill's row left of the box is the sheet's: the pill takes no input of its own
        rule.onRoot().performTouchInput { down(Offset(100f * d, pill.center.y)); up() }
        rule.waitForIdle()
        assertEquals(1, sheetDowns[0])
        // a touch in the box's empty top-left corner, over the pill's column, reaches the sheet too
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(4f, 4f)); up() }
        rule.waitForIdle()
        assertEquals(2, sheetDowns[0])
        // a touch on the bottom band is the gizmo's and the sheet does not see it
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(width / 2f, height - 48f)); up() }
        rule.waitForIdle()
        assertEquals(2, sheetDowns[0])
    }
}
