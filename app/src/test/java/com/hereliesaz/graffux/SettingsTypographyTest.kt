package com.hereliesaz.graffux

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hereliesaz.graffitixr.design.theme.DarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings is prose and switches, not toolbar chrome: it must be readable and AA-contrast. */
class SettingsTypographyTest {
    private val t = settingsTypography(Typography())

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun `descriptions and titles meet the readable minimum`() {
        assertTrue(t.bodySmall.fontSize.value >= MIN_BODY_SP)
        assertTrue(t.bodyMedium.fontSize.value >= MIN_BODY_SP)
        assertTrue(t.titleMedium.fontSize.value >= 16f)
        assertTrue(t.titleSmall.fontSize.value >= MIN_BODY_SP)
        assertTrue(t.labelSmall.fontSize.value >= MIN_BODY_SP)
    }

    @Test fun `title stays larger than its description`() {
        assertTrue(t.titleMedium.fontSize.value > t.bodySmall.fontSize.value)
    }

    @Test fun `line height leaves room for the larger text`() {
        listOf(t.bodySmall, t.bodyMedium, t.titleMedium, t.titleSmall).forEach {
            assertTrue(it.lineHeight.value >= it.fontSize.value * 1.2f)
        }
    }

    @Test fun `settings text colours meet WCAG AA on the settings surface`() {
        val s = DarkColorScheme
        assertTrue(contrast(s.onSurface, s.surface) >= 4.5f)
        assertTrue(contrast(s.onSurfaceVariant, s.surface) >= 4.5f)
        assertTrue(contrast(s.primary, s.surface) >= 4.5f) // section headers
    }

    @Test fun `unrelated styles are untouched`() {
        assertEquals(Typography().displayLarge, t.displayLarge)
    }

    private companion object { const val MIN_BODY_SP = 12f }
}
