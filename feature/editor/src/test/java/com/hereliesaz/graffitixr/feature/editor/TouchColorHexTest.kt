package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What typing in the hex field does to the brush: when it applies, when it is remembered, what closing does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class TouchColorHexTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf(Color(0xFF000000))
    private var open by mutableStateOf(true)
    private val applied = ArrayList<Color>()
    private val committed = ArrayList<Color>()
    private val pink = Color(0xFFFF00AA)
    private val yellow = Color(0xFFFFFF00)

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                if (open) {
                    HexColourField(
                        selected = selected,
                        onApply = { applied += it; selected = it },
                        onCommit = { committed += it },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun type(text: String) {
        rule.onNodeWithTag("touchColor.hex").performTextReplacement(text)
        rule.mainClock.advanceTimeBy(40)
    }

    @Test
    fun `typing FF00AA key by key never paints the three-digit prefix FF0`() {
        listOf("F", "FF", "FF0", "FF00", "FF00A", "FF00AA").forEach { type(it) }
        assertFalse("FF0 is #FFFF00: it must not reach the brush", yellow in applied)
        assertEquals(listOf(pink), applied)
    }

    @Test
    fun `a six digit value applies at once, with no rest`() {
        type("FF00AA")
        assertEquals(listOf(pink), applied)
    }

    @Test
    fun `an eight digit value applies at once and keeps its own alpha`() {
        type("80FF00AA")
        assertEquals(listOf(Color(0x80FF00AA)), applied)
    }

    @Test
    fun `a three digit value applies only after the short rest`() {
        type("F0A")
        assertTrue("not yet: it may be the start of a longer value", applied.isEmpty())
        rule.mainClock.advanceTimeBy(HEX_SHORT_REST_MS - 100)
        assertTrue(applied.isEmpty())
        rule.mainClock.advanceTimeBy(200)
        assertEquals(listOf(pink), applied)
    }

    @Test
    fun `an applied value is committed once it has rested, and only then`() {
        type("FF00AA")
        rule.mainClock.advanceTimeBy(HEX_COMMIT_DELAY_MS - 100)
        assertTrue(committed.isEmpty())
        rule.mainClock.advanceTimeBy(200)
        assertEquals(listOf(pink), committed)
    }

    @Test
    fun `closing the sheet inside the rest still records the typed value`() {
        type("FF00AA")
        assertTrue(committed.isEmpty())
        open = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals("recorded on close, with the latest value", listOf(pink), committed)
        assertEquals(listOf(pink), applied)
    }

    @Test
    fun `closing while a three digit value is still resting applies and records it`() {
        type("F0A")
        assertTrue(applied.isEmpty())
        open = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf(pink), applied)
        assertEquals(listOf(pink), committed)
    }

    @Test
    fun `closing after the value was already recorded does not record it twice`() {
        type("FF00AA")
        rule.mainClock.advanceTimeBy(HEX_COMMIT_DELAY_MS + 100)
        assertEquals(listOf(pink), committed)
        open = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf(pink), committed)
    }

    @Test
    fun `closing on an invalid value records the last valid one that was applied`() {
        type("FF00AA")
        type("FF00A") // five digits: invalid, the brush stays pink
        open = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf(pink), committed)
    }

    @Test
    fun `the three lengths and nothing else are what the field accepts and the error says`() {
        listOf("abc", "ABCDEF", "#12345678").forEach { assertTrue(it, hexFieldColor(it, Color.Black) != null) }
        listOf("ab", "abcd", "abcde", "abcdefg", "123456789").forEach {
            assertTrue(it, hexFieldColor(it, Color.Black) == null)
        }
        val text = rule.activity.getString(R.string.colour_hex_invalid)
        assertTrue(text, "3, 6 or 8" in text)
    }

    @Test
    fun `closing after a resting three digit value was typed over into an invalid one leaves the brush alone`() {
        type("FF0")
        type("FF00") // four digits: invalid, and FF0 never reached the brush
        open = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue("nothing applied: $applied", applied.isEmpty())
        assertTrue("nothing recorded: $committed", committed.isEmpty())
    }

    @Test
    fun `closing after a value was applied and then typed over still records what the brush has`() {
        type("FF00AA")
        type("FF00A")
        open = false
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(listOf(pink), applied)
        assertEquals(listOf(pink), committed)
    }
}
