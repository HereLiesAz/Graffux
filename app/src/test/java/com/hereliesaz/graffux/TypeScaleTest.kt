package com.hereliesaz.graffux

import androidx.compose.ui.text.TextStyle
import com.hereliesaz.graffitixr.design.theme.Typography as AppTypography
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-wide type scale has a readable floor. It had text styles at 7, 8, 10 and 11sp (bodySmall,
 * labelMedium, labelSmall, titleSmall, titleMedium, headlineSmall, bodyMedium), so every caption, pill,
 * hint and window title that used one was unreadable at the default font size.
 */
class TypeScaleTest {
    private val styles: Map<String, TextStyle> = mapOf(
        "displayLarge" to AppTypography.displayLarge,
        "displayMedium" to AppTypography.displayMedium,
        "displaySmall" to AppTypography.displaySmall,
        "headlineLarge" to AppTypography.headlineLarge,
        "headlineMedium" to AppTypography.headlineMedium,
        "headlineSmall" to AppTypography.headlineSmall,
        "titleLarge" to AppTypography.titleLarge,
        "titleMedium" to AppTypography.titleMedium,
        "titleSmall" to AppTypography.titleSmall,
        "bodyLarge" to AppTypography.bodyLarge,
        "bodyMedium" to AppTypography.bodyMedium,
        "bodySmall" to AppTypography.bodySmall,
        "labelLarge" to AppTypography.labelLarge,
        "labelMedium" to AppTypography.labelMedium,
        "labelSmall" to AppTypography.labelSmall,
    )

    @Test fun `no text style is below the readable floor`() {
        val small = styles.filter { it.value.fontSize.value < MIN_SP }.mapValues { it.value.fontSize.value }
        assertTrue("styles under ${MIN_SP}sp: $small", small.isEmpty())
    }

    @Test fun `every line height clears its font size`() {
        val clipped = styles.filter { it.value.lineHeight.value < it.value.fontSize.value }.keys
        assertTrue("line height under font size: $clipped", clipped.isEmpty())
    }

    @Test fun `each family keeps its order large to small`() {
        fun sp(s: TextStyle) = s.fontSize.value
        assertTrue(sp(AppTypography.headlineLarge) >= sp(AppTypography.headlineMedium))
        assertTrue(sp(AppTypography.headlineMedium) >= sp(AppTypography.headlineSmall))
        assertTrue(sp(AppTypography.titleLarge) >= sp(AppTypography.titleMedium))
        assertTrue(sp(AppTypography.titleMedium) >= sp(AppTypography.titleSmall))
        assertTrue(sp(AppTypography.bodyLarge) >= sp(AppTypography.bodyMedium))
        assertTrue(sp(AppTypography.bodyMedium) >= sp(AppTypography.bodySmall))
    }

    @Test fun `the settings title outranks its section headers`() {
        val t = settingsTypography(AppTypography)
        assertTrue(t.headlineSmall.fontSize.value > t.titleMedium.fontSize.value)
    }

    private companion object { const val MIN_SP = 12f }
}
