package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The set picker's quarter-circle wheel: spokes, drag and snap, the dwell commit, taps, ends, semantics, mirroring. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselSetWheelUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val names = listOf("Favorites", "Round", "Flat", "Ink", "My Brushes", "Spray Caps")
    private val keys = listOf(FAVORITES_SET_KEY, "basic.round", "basic.flat", "ink", "studio", "pack.caps")
    private val sections = listOf(
        CarouselTabSection(
            "ALL",
            keys.zip(names).map { (k, n) -> CarouselTabGroup(k, n, CarouselCategory.BRUSHES, emptyList()) },
        ),
    )
    private var selected by mutableStateOf<String?>(FAVORITES_SET_KEY)
    private val picks = mutableListOf<String>()

    private fun compose(onRight: Boolean = true, start: String = FAVORITES_SET_KEY) {
        selected = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSetWheelOnRight provides onRight) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselTabList(sections, selected, onSelect = { picks += it.key; selected = it.key })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun item(label: String) = rule.onNode(hasTestTag("carousel.wheel.item") and hasText(label))

    private fun center(label: String): Offset = item(label).fetchSemanticsNode().boundsInRoot.center

    private fun wheelBounds() = rule.onNodeWithTag("carousel.wheel").fetchSemanticsNode().boundsInRoot

    /** The screen angle of [label]'s name, seen from the wheel's hub in its corner. */
    private fun angleOf(label: String, onRight: Boolean = true): Float {
        val w = wheelBounds()
        val inset = WheelEdgeInset.value * rule.density.density
        val hub = WheelHub.at(w.width, w.height, inset, onRight) + Offset(w.left, w.top)
        val c = center(label)
        val deg = Math.toDegrees(atan2((c.y - hub.y).toDouble(), (c.x - hub.x).toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    /** Runs the fake clock on: animations and the dwell only move when the test says so. */
    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    /** Drags slowly around the hub from [fromDeg] to [toDeg], 80% of the way out, and lets go. */
    private fun dragAround(fromDeg: Float, toDeg: Float, onRight: Boolean = true) {
        rule.onNodeWithTag("carousel.wheel").performTouchInput {
            val inset = WheelEdgeInset.toPx()
            val hub = WheelHub.at(width.toFloat(), height.toFloat(), inset, onRight)
            val r = (height - inset) * 0.8f
            fun at(deg: Float) = (deg * PI / 180).let { hub + Offset((r * cos(it)).toFloat(), (r * sin(it)).toFloat()) }
            val steps = 30
            down(at(fromDeg))
            for (i in 1..steps) {
                advanceEventTime(40)
                moveTo(at(fromDeg + (toDeg - fromDeg) * i / steps))
            }
            advanceEventTime(300)
            moveTo(at(toDeg))
            up()
        }
        rule.waitForIdle()
    }

    private val pointer = wheelPointerDegrees(onRight = true)

    @Test
    fun `every set sits on its own spoke, equally spaced`() {
        compose()
        assertEquals(keys.size, rule.onAllNodesWithTag("carousel.wheel.item").fetchSemanticsNodes().size)
        val spokes = keys.indices.map { wheelSpokeDegrees(it, 0f, onRight = true) }
        spokes.zipWithNext().forEach { (a, b) -> assertEquals(WHEEL_WEDGE_DEGREES, a - b, 1e-4f) }
        assertEquals(225f, pointer, 1e-4f)
        // The selected name is on the pointer at the quarter's midpoint, the next a wedge lower.
        assertEquals(225f, angleOf("Favorites"), 3f)
        assertEquals(225f - WHEEL_WEDGE_DEGREES, angleOf("Round"), 3f)
        assertEquals(225f + WHEEL_WEDGE_DEGREES * 0, angleOf("Favorites"), 3f)
        // Names never read upside down.
        (180..360).forEach { assertTrue(wheelTextRotation(it.toFloat()) in -90f..90f) }
    }

    @Test
    fun `a drag snaps to a set and commits it after the dwell`() {
        compose()
        rule.mainClock.autoAdvance = false
        // Right-handed, later sets lie toward the bottom edge: turning clockwise brings them up.
        dragAround(pointer, pointer + WHEEL_WEDGE_DEGREES * 2)
        advance(WHEEL_DWELL_MS - 100)
        assertTrue("provisional while it waits", picks.isEmpty())
        assertEquals(FAVORITES_SET_KEY, selected)
        advance(WHEEL_DWELL_MS * 3)
        assertEquals(listOf(keys[2]), picks)
        assertEquals(225f, angleOf("Flat"), 3f)
    }

    @Test
    fun `a real tap on a name commits it at once and turns it to the pointer, with no dwell`() {
        compose()
        rule.mainClock.autoAdvance = false
        // A real touch on the wheel at the name (not the semantics OnClick action).
        val wheel = wheelBounds()
        val round = center("Round")
        rule.onNodeWithTag("carousel.wheel").performTouchInput {
            click(Offset(round.x - wheel.left, round.y - wheel.top))
        }
        advance(100)
        assertEquals(listOf("basic.round"), picks)
        advance(WHEEL_DWELL_MS * 3)
        assertEquals(listOf("basic.round"), picks)
        assertEquals(225f, angleOf("Round"), 3f)
        val flat = center("Flat")
        rule.onNodeWithTag("carousel.wheel").performTouchInput {
            click(Offset(flat.x - wheel.left, flat.y - wheel.top))
        }
        advance(100)
        assertEquals(listOf("basic.round", "basic.flat"), picks)
    }

    @Test
    fun `the wheel stops at the first and the last set`() {
        assertEquals(-WHEEL_OVERSCROLL, wheelDragPosition(0f, -5f, keys.size), 1e-4f)
        assertEquals(keys.size - 1 + WHEEL_OVERSCROLL, wheelDragPosition(5f, 5f, keys.size), 1e-4f)
        assertEquals(keys.size - 1, wheelSnapTarget(5.3f, 40f, keys.size))
        assertEquals(0, wheelSnapTarget(-0.3f, -40f, keys.size))
        compose()
        rule.mainClock.autoAdvance = false
        dragAround(pointer, pointer - 45f)
        advance(WHEEL_DWELL_MS * 4)
        assertEquals(FAVORITES_SET_KEY, selected)
        assertTrue(picks.isEmpty())
        assertEquals(225f, angleOf("Favorites"), 3f)
    }

    @Test
    fun `the wheel stops at the last set`() {
        compose(start = "pack.caps")
        rule.mainClock.autoAdvance = false
        dragAround(pointer, pointer + 45f)
        advance(WHEEL_DWELL_MS * 4)
        assertEquals("pack.caps", selected)
        assertTrue(picks.isEmpty())
        assertEquals(225f, angleOf("Spray Caps"), 3f)
    }

    @Test
    fun `Favorites and previous still work beside the wheel`() {
        compose(start = "ink")
        rule.onNodeWithTag("carousel.quick.favorites").performClick()
        rule.waitForIdle()
        assertEquals(FAVORITES_SET_KEY, selected)
        rule.onNode(hasTestTag("carousel.quick.previous") and hasText("Ink")).performClick()
        rule.waitForIdle()
        assertEquals("ink", selected)
        assertEquals(225f, angleOf("Ink"), 3f)
    }

    @Test
    fun `the wheel is a single-select list whose accessibility actions commit at once`() {
        compose()
        rule.mainClock.autoAdvance = false
        val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        val items = rule.onAllNodesWithTag("carousel.wheel.item").fetchSemanticsNodes()
        assertTrue(items.all { selectable.matches(it) })
        assertEquals(1, items.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
        val wheel = rule.onNodeWithTag("carousel.wheel").fetchSemanticsNode()
        assertTrue(wheel.config.contains(SemanticsProperties.SelectableGroup))
        val actions = wheel.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Next set", "Previous set"), actions.map { it.label })
        rule.runOnIdle { actions.first { it.label == "Next set" }.action() }
        assertEquals("basic.round", selected)
        rule.runOnIdle { actions.first { it.label == "Previous set" }.action() }
        assertEquals(FAVORITES_SET_KEY, selected)
        item("Ink").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("ink", selected)
        assertEquals(listOf("basic.round", FAVORITES_SET_KEY, "ink"), picks)
    }

    @Test
    fun `left-handed mirrors the wheel into the bottom-left corner`() {
        compose(onRight = false)
        val wheel = wheelBounds()
        val fav = rule.onNodeWithTag("carousel.quick.favorites").fetchSemanticsNode().boundsInRoot
        assertTrue("wheel on the left, buttons on the right", wheel.right <= fav.left)
        assertEquals(315f, angleOf("Favorites", onRight = false), 3f)
        assertEquals(315f + WHEEL_WEDGE_DEGREES, angleOf("Round", onRight = false), 3f)
        rule.mainClock.autoAdvance = false
        dragAround(315f, 315f - WHEEL_WEDGE_DEGREES * 2, onRight = false)
        advance(WHEEL_DWELL_MS * 4)
        assertEquals(keys[2], selected)
    }

    @Test
    fun `right-handed puts the wheel in the bottom-right corner`() {
        compose()
        val wheel = wheelBounds()
        val fav = rule.onNodeWithTag("carousel.quick.favorites").fetchSemanticsNode().boundsInRoot
        assertTrue(fav.right <= wheel.left)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // Drawn over its strip, reaching into the sheet's bottom inset (PickerOverhang) below it.
        assertEquals(root.bottom + PickerOverhang.value * rule.density.density, wheel.bottom, 2f)
        assertTrue(wheel.width <= WheelMaxRadius.value * rule.density.density + 1f)
    }
}
