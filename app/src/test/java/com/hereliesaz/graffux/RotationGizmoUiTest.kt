@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.RotationAxis
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rotation triangle in a real Compose tree (xhdpi, so 1dp = 2px): only its three 48dp touch bands take
 * pointer input, a drag turns by its length, and each edge is an accessibility node with +/- 15 degree actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class RotationGizmoUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var canvasDowns = 0
    private val turns = mutableListOf<Pair<RotationAxis, Float>>()
    private var ends = 0
    private val steps = mutableListOf<Pair<RotationAxis, Float>>()

    private fun show() {
        rule.setContent {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); canvasDowns++ } },
                contentAlignment = Alignment.Center,
            ) {
                RotationGizmo({ a, d -> turns += a to d }, { ends++ }, { a, d -> steps += a to d }, scale = 1f)
            }
        }
    }

    // Triangle coordinates (px) to the gizmo box's: the box has a half band (48px) all round.
    private fun at(x: Float, y: Float) = Offset(x + 48f, y + 48f)
    private val w = 352f
    private val h = 240f

    @Test
    fun `a touch in the interior or outside the bands reaches the canvas, not the gizmo`() {
        show()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            val inner = at(w - 80f, h - 80f) // the incentre of a 352 x 240 triangle is 80 from each leg
            down(inner); up()
        }
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(4f, 4f)); up() } // the box's corner
        rule.waitForIdle()
        assertEquals(2, canvasDowns)
        assertTrue(turns.isEmpty())
        assertEquals(0, ends)
    }

    @Test
    fun `a touch on a band is the gizmo's, and does not reach the canvas`() {
        show()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            down(at(w / 2f, h)); moveBy(Offset(50f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals(0, canvasDowns)
        assertEquals(1, ends)
    }

    @Test
    fun `dragging the length of the bottom leg turns X by 180, the side Y, the diagonal Z`() {
        show()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            down(at(0f, h)); moveTo(at(w, h)); up()
        }
        rule.waitForIdle()
        assertTrue(turns.all { it.first == RotationAxis.X })
        assertEquals(180f, turns.sumOf { it.second.toDouble() }.toFloat(), 0.5f)
        turns.clear()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            // Not exactly on the corner (a tie there goes to the bottom leg): 30px up the side.
            down(at(w, h - 30f)); moveTo(at(w, 0f)); up()
        }
        rule.waitForIdle()
        assertTrue(turns.isNotEmpty() && turns.all { it.first == RotationAxis.Y })
        assertEquals(157.5f, turns.sumOf { it.second.toDouble() }.toFloat(), 0.5f)
        turns.clear()
        rule.onNodeWithTag("rotationGizmo").performTouchInput {
            down(at(w / 2f, h / 2f)); moveTo(at(0f, h)); up() // down-left along the diagonal: negative Z
        }
        rule.waitForIdle()
        assertTrue(turns.isNotEmpty() && turns.all { it.first == RotationAxis.Z })
        assertEquals(-90f, turns.sumOf { it.second.toDouble() }.toFloat(), 0.5f)
        assertEquals(3, ends)
    }

    @Test
    fun `a drag stays locked to its edge and a second swipe adds on`() {
        show()
        repeat(2) {
            rule.onNodeWithTag("rotationGizmo").performTouchInput {
                down(at(0f, h)); moveTo(at(w / 2f, h - 30f)); moveTo(at(w, h - 90f)); up()
            }
        }
        rule.waitForIdle()
        assertTrue(turns.all { it.first == RotationAxis.X })
        assertEquals(360f, turns.sumOf { it.second.toDouble() }.toFloat(), 1f)
        assertEquals(2, ends)
    }

    @Test
    fun `each edge is a named node with plus and minus 15 degree actions`() {
        show()
        for (axis in RotationAxis.entries) {
            val node = rule.onNodeWithContentDescription("Rotate ${axis.name}").fetchSemanticsNode()
            val actions = node.config[SemanticsActions.CustomActions]
            assertEquals(2, actions.size)
            rule.runOnIdle { actions[0].action(); actions[1].action() }
        }
        assertEquals(
            listOf(
                RotationAxis.X to 15f, RotationAxis.X to -15f,
                RotationAxis.Y to 15f, RotationAxis.Y to -15f,
                RotationAxis.Z to 15f, RotationAxis.Z to -15f,
            ),
            steps,
        )
    }

    // ---- docking: the small triangle in the bottom corner on the brush hand's side ----

    private val handleHalfWidthDp = 32f // the sheet's centred handle/name pill is assumed no wider than 64dp

    private fun dockInWindow(rightHanded: Boolean, sheetDowns: IntArray? = null) {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                // the sheet first, the dock after it, as MainActivity composes them
                Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); sheetDowns?.let { it[0]++ } } })
                RotationGizmoDock(rightHanded = rightHanded) {
                    RotationGizmo({ a, d -> turns += a to d }, { ends++ }, { a, d -> steps += a to d }, scale = it)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun box() = rule.onNodeWithTag("rotationGizmo").fetchSemanticsNode().boundsInRoot

    @Test
    fun `right-handed, the small triangle is in the bottom-right corner`() {
        dockInWindow(rightHanded = true)
        val b = box()
        val w = 390f * 2f
        val h = 844f * 2f
        assertTrue("right edge ${b.right} in the right-hand corner", b.right > w - 40f && b.right <= w)
        assertTrue("bottom ${b.bottom} in the bottom corner", b.bottom > h - 40f && b.bottom <= h)
        assertTrue("left ${b.left} is in the right half", b.left > w / 2f)
    }

    @Test
    fun `left-handed, the small triangle is in the bottom-left corner`() {
        dockInWindow(rightHanded = false)
        val b = box()
        val w = 390f * 2f
        val h = 844f * 2f
        assertTrue("left edge ${b.left} in the left-hand corner", b.left >= 0f && b.left < 40f)
        assertTrue("bottom ${b.bottom} in the bottom corner", b.bottom > h - 40f && b.bottom <= h)
        assertTrue("right ${b.right} is in the left half", b.right < w / 2f)
    }

    @Test
    fun `it is noticeably smaller than before but its touch bands stay 48dp`() {
        dockInWindow(rightHanded = true)
        val b = box()
        val fullBox = (GizmoWidth.value + GizmoBand.value) * 2f
        assertTrue("narrower than the old ${fullBox}px: ${b.width}", b.width < fullBox * 0.85f)
        assertEquals((GizmoWidth.value * GIZMO_DEFAULT_SCALE + GizmoBand.value) * 2f, b.width, 1f)
        assertEquals(48f, GizmoBand.value, 0f)
        // a drag on the bottom band (96px thick at xhdpi) works, so the 48dp target is intact
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(width / 2f, height - 48f + 40f)); moveBy(Offset(20f, 0f)); up() }
        rule.waitForIdle()
        assertTrue(turns.isNotEmpty())
    }

    @Test
    fun `right-handed it keeps clear of the sheet's handle in the middle`() {
        dockInWindow(rightHanded = true)
        val centre = 390f // px, half of the 780px window
        assertTrue("box starts at ${box().left}", box().left >= centre + handleHalfWidthDp * 2f)
    }

    @Test
    fun `left-handed it keeps clear of the sheet's handle in the middle`() {
        dockInWindow(rightHanded = false)
        val centre = 390f
        assertTrue("box ends at ${box().right}", box().right <= centre - handleHalfWidthDp * 2f)
    }

    @Test
    fun `the corner keeps clear of the gesture insets and margin`() {
        val at = gizmoCorner(1000, 2000, 300, 250, rightHanded = true, left = 40, right = 60, bottom = 120)
        assertEquals(1000 - 60 - 300, at.x)
        assertEquals(2000 - 120 - 250, at.y)
        val lh = gizmoCorner(1000, 2000, 300, 250, rightHanded = false, left = 40, right = 60, bottom = 120)
        assertEquals(40, lh.x)
        assertEquals(2000 - 120 - 250, lh.y)
    }

    @Test
    fun `it works with a sheet open or closed, above it and without taking the sheet's other touches`() {
        val downs = IntArray(1)
        dockInWindow(rightHanded = true, sheetDowns = downs)
        // a band touch is the gizmo's, not the sheet's
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(width / 2f, height - 48f)); moveBy(Offset(60f, 0f)); up() }
        rule.waitForIdle()
        assertEquals(0, downs[0])
        assertTrue(turns.isNotEmpty())
        // and a touch in the box's empty corner still reaches whatever is under it (the sheet)
        rule.onNodeWithTag("rotationGizmo").performTouchInput { down(Offset(4f, 4f)); up() }
        rule.waitForIdle()
        assertEquals(1, downs[0])
    }

    // ---- the set wheel owns its corner: the triangle's bands slide up the edge to clear it ----

    private val wheelSections = listOf(
        CarouselTabSection(
            "ALL",
            listOf("Favorites", "Round", "Flat").map { CarouselTabGroup(it, it, CarouselCategory.BRUSHES, emptyList()) },
        ),
    )

    private fun dockWithWheel(rightHanded: Boolean) {
        val bounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSetWheelOnRight provides rightHanded,
                LocalSetWheelBounds provides bounds,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselTabList(wheelSections, "Favorites", onSelect = {})
                    }
                    RotationGizmoDock(rightHanded = rightHanded, avoid = { bounds.value }) {
                        RotationGizmo({ a, d -> turns += a to d }, { ends++ }, { a, d -> steps += a to d }, scale = it)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun wheel() = rule.onNodeWithTag("carousel.wheel").fetchSemanticsNode().boundsInRoot

    private fun assertClearOfWheel(touchXdp: Float) {
        val b = box()
        val wb = wheel()
        assertTrue("triangle box ${b.top}..${b.bottom} is above the wheel top ${wb.top}", b.bottom <= wb.top + 0.5f)
        // the 390dp example: a touch 50dp above the bottom edge at this x is on the wheel, not on a triangle band
        val x = touchXdp * 2f
        val y = 844f * 2f - 50f * 2f
        assertTrue("that point is on the wheel", wb.contains(Offset(x, y)))
        assertTrue("and not in the triangle's box", !b.contains(Offset(x, y)))
    }

    @Test
    fun `right-handed with the set wheel open, the triangle clears the wheel and the touch at 330dp reaches the wheel`() {
        dockWithWheel(rightHanded = true)
        assertClearOfWheel(330f)
        val b = box()
        assertTrue("still in the right-hand corner column", b.right > 780f - 40f)
        assertTrue("on screen", b.top >= 0f)
    }

    @Test
    fun `left-handed with the set wheel open, the triangle clears the wheel and a touch at 60dp reaches the wheel`() {
        dockWithWheel(rightHanded = false)
        assertClearOfWheel(60f)
        assertTrue("still in the left-hand corner column", box().left < 40f)
    }

    @Test
    fun `with no wheel the triangle sits in the corner as before`() {
        dockInWindow(rightHanded = true)
        assertTrue(box().bottom > 844f * 2f - 40f)
    }

    @Test
    fun `gizmoCorner slides only as far as needed and only when it overlaps`() {
        val wheel = androidx.compose.ui.geometry.Rect(100f, 1900f, 900f, 2000f)
        val clear = gizmoCorner(1000, 2000, 300, 250, true, 0, 0, 0, avoid = wheel, gap = 10)
        assertEquals(1900 - 10 - 250, clear.y)
        // a wheel that does not reach the box's columns changes nothing
        val side = androidx.compose.ui.geometry.Rect(0f, 1900f, 600f, 2000f)
        assertEquals(2000 - 250, gizmoCorner(1000, 2000, 300, 250, true, 0, 0, 0, avoid = side, gap = 10).y)
    }

    // ---- the layers rail stops above the triangle, wherever the triangle is ----

    /** Codex's example: 390 x 844dp (xhdpi: 780 x 1688px), a retained 24dp nav inset, the set wheel open. */
    @Test
    fun `the rail stops above the triangle's top when the wheel lifts it, with a retained nav inset`() {
        val d = 2
        val navInset = 24 * d
        val margin = 8 * d
        val boxW = ((GizmoWidth.value * GIZMO_DEFAULT_SCALE + GizmoBand.value) * d).toInt()
        val boxH = ((GizmoHeight.value * GIZMO_DEFAULT_SCALE + GizmoBand.value) * d).toInt()
        val wheelTop = 1688 - navInset - 76 * d
        for (right in listOf(true, false)) {
            val wheelX = if (right) 780f - 272 * d else 0f
            val wheel = androidx.compose.ui.geometry.Rect(wheelX, wheelTop.toFloat(), wheelX + 272 * d, 1688f - navInset)
            for (avoid in listOf(wheel, null)) {
                val at = gizmoCorner(780, 1688, boxW, boxH, right, margin, margin, navInset + margin, avoid, margin)
                val occupied = 1688 - at.y
                // the rail's container ends at the bottom safe inset; its bottom edge in root px:
                val clearance = gizmoRailClearancePx(occupied, navInset, margin)
                val railBottomRoot = 1688 - navInset - clearance
                assertTrue(
                    "right=$right wheel=${avoid != null}: rail bottom $railBottomRoot above triangle top ${at.y}",
                    railBottomRoot <= at.y - margin + 1,
                )
            }
        }
    }

    @Test
    fun `no triangle needs no rail clearance`() {
        assertEquals(0, gizmoRailClearancePx(0, 48, 16))
    }

    @Test
    fun `the dock reports the height it occupies, from the same position it places the triangle at`() {
        var occupied = 0
        val bounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSetWheelOnRight provides true,
                LocalSetWheelBounds provides bounds,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselTabList(wheelSections, "Favorites", onSelect = {})
                    }
                    RotationGizmoDock(rightHanded = true, avoid = { bounds.value }, onOccupied = { occupied = it }) {
                        RotationGizmo({ _, _ -> }, {}, { _, _ -> }, scale = it)
                    }
                }
            }
        }
        rule.waitForIdle()
        val b = box()
        assertEquals(1688f - b.top, occupied.toFloat(), 1f)
    }
}
