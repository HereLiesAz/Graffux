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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The set picker as the set/option table that replaced the curved wheel (#888): one row per set, a tap commits
 * at once, selection is exposed, and the table sits full width at the foot of the screen for either hand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselSetTableUiTest {

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

    private var right by mutableStateOf(true)
    private var composed = false

    private fun compose(onRight: Boolean = true, start: String = FAVORITES_SET_KEY) {
        right = onRight
        selected = start
        if (composed) { rule.waitForIdle(); return }
        composed = true
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSetWheelOnRight provides right) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselTabList(sections, selected, onSelect = { picks += it.key; selected = it.key })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun set(key: String) = rule.onNodeWithTag("carousel.set.$key")

    private fun setBounds(key: String) = set(key).fetchSemanticsNode().boundsInRoot

    private fun tableBounds() = rule.onNodeWithTag("carousel.tabs").fetchSemanticsNode().boundsInRoot

    @Test
    fun `every set has its own row cell, listed in order down the table`() {
        compose()
        val table = rule.onNodeWithTag("carousel.tabs")
        // Absolute position down the scrolling column: how far it has scrolled plus where the cell shows.
        val tops = keys.map {
            set(it).performScrollTo()
            val scrolled = table.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            scrolled + setBounds(it).top - tableBounds().top
        }
        tops.zipWithNext().forEach { (a, b) -> assertTrue("$tops", b > a) }
    }

    @Test
    fun `a real tap on a set commits it at once`() {
        compose()
        set("basic.round").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("basic.round"), picks)
        assertEquals("basic.round", selected)
        set("basic.flat").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("basic.round", "basic.flat"), picks)
    }

    @Test
    fun `the last set is reachable by scrolling and the table stays within its height cap`() {
        compose(start = "pack.caps")
        set("pack.caps").performScrollTo()
        assertTrue(setBounds("pack.caps").bottom <= tableBounds().bottom + 1f)
        set("studio").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("studio"), picks)
        val maxPx = 208 * rule.density.density
        assertTrue("table ${tableBounds().height}px", tableBounds().height <= maxPx + 1f)
    }

    @Test
    fun `the table is a single-select tab list that exposes which set is chosen`() {
        compose()
        val items = keys.map { set(it).fetchSemanticsNode() }
        items.forEach { assertTrue(it.config.contains(SemanticsProperties.Selected)) }
        assertEquals(1, items.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
        set(FAVORITES_SET_KEY).assertIsSelectedCompat(true)
        set("ink").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("ink", selected)
        set("ink").assertIsSelectedCompat(true)
        set(FAVORITES_SET_KEY).assertIsSelectedCompat(false)
        assertEquals(listOf("ink"), picks)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsSelectedCompat(on: Boolean) {
        assertEquals(on, fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun `the table fills the width at the foot of the screen for either hand`() {
        listOf(true, false).forEach { right ->
            compose(onRight = right)
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            val table = tableBounds()
            assertEquals("bottom (right-handed=$right)", root.bottom, table.bottom, 1f)
            assertTrue("full width (right-handed=$right)", table.width >= root.width - 1f)
            picks.clear()
        }
    }

    @Test
    fun `typical set names show in full`() {
        compose()
        listOf("My Brushes", "Spray Caps", "Favorites").forEach { name ->
            val inTable = hasText(name) and hasAnyAncestor(hasTestTag("carousel.tabs"))
            val node = rule.onNode(inTable).performScrollTo().fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val l = layout.first()
            val cut = (0 until l.lineCount).any { l.isLineEllipsized(it) } || l.didOverflowHeight
            assertFalse("$name is cut (${l.lineCount} lines)", cut)
        }
    }

    @Test
    fun `each set cell is a 48dp touch target`() {
        compose()
        val min = 48 * rule.density.density
        keys.forEach {
            val size = set(it).fetchSemanticsNode().size
            assertTrue("$it ${size} d=${rule.density.density}", size.height >= min - 1 && size.width >= min - 1)
        }
    }
}
