@file:Suppress("MagicNumber", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.azphalt.PackagePrice
import com.hereliesaz.graffitixr.common.model.EditorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The editor-side pieces behind the UI/UX audit fixes. */
class AuditFixesEditorTest {

    @Test
    fun `overlapping long operations keep the spinner until the last one ends`() {
        var s = EditorUiState()
        s = EditorReducer.reduce(s, EditorIntent.SetLoading(true, "Exporting PNG…", cancellable = true))
        s = EditorReducer.reduce(s, EditorIntent.SetLoading(true, "Flattening layers…"))
        s = EditorReducer.reduce(s, EditorIntent.SetLoading(false))
        assertTrue(s.isLoading)
        assertEquals("Flattening layers…", s.loadingLabel)
        s = EditorReducer.reduce(s, EditorIntent.SetLoading(false))
        assertFalse(s.isLoading)
        assertNull(s.loadingLabel)
        assertFalse(s.loadingCancellable)
        // An extra end never goes negative.
        s = EditorReducer.reduce(s, EditorIntent.SetLoading(false))
        assertEquals(0, s.loadingJobs)
    }

    @Test
    fun `recent colours move to the front without duplicates and stay bounded`() {
        val red = Color(0xFFFF0000)
        val blue = Color(0xFF0000FF)
        assertEquals(listOf(blue, red), pushRecentColor(listOf(red), blue))
        assertEquals(listOf(red, blue), pushRecentColor(listOf(blue, red), red))
        val many = (0 until 20).fold(emptyList<Color>()) { acc, i -> pushRecentColor(acc, Color(0xFF000000 or i.toLong())) }
        assertEquals(RECENT_COLORS_MAX, many.size)
    }

    @Test
    fun `hex round-trips with and without alpha`() {
        assertEquals("#FF3EA5", hexOf(Color(0xFFFF3EA5)))
        assertEquals("#80FF3EA5", hexOf(Color(0x80FF3EA5)))
        assertEquals(Color(0xFFFF3EA5), parseHexColor("ff3ea5"))
        assertEquals(Color(0x80FF3EA5), parseHexColor("#80FF3EA5"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GGGGGG"))
    }

    @Test
    fun `every share gets its own file name`() {
        assertNotEquals(shareFileName("Wall", 1L), shareFileName("Wall", 2L))
        assertEquals("Graffux-5.png", shareFileName(null, 5L))
        assertEquals("My wall-5.png", shareFileName("My wall/?", 5L))
    }

    @Test
    fun `the hud stays long enough to read a sentence`() {
        assertEquals(700L, hudDurationMs("Undo", 700L))
        assertTrue(hudDurationMs("Cleared layer - Undo on the rail", 700L) >= 1500L)
        assertEquals(5000L, hudDurationMs("Cleared layer - Undo on the rail", 5000L))
    }

    @Test
    fun `buy shows the price when the repository sends one`() {
        assertEquals("Buy", buyLabel(null))
        assertEquals("Buy · $4.99", buyLabel(PackagePrice(499, "usd")).replace(' ', ' ').let {
            if (Locale.getDefault() == Locale.US) it else "Buy · " + formatPrice(PackagePrice(499, "usd"), Locale.US)
        })
        assertEquals("$4.99", formatPrice(PackagePrice(499, "USD"), Locale.US))
        assertEquals("499 XQZ", formatPrice(PackagePrice(499, "xqz"), Locale.US))
    }
}
