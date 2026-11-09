package com.hereliesaz.graffux

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The app-wide scale is tuned for compact toolbar chrome (12 to 14sp),
 * smaller than the prose and labels Settings is made of want. Settings re-scales
 * only the styles it uses, leaving every other screen and the rails untouched. Colours are not
 * changed: `onSurfaceVariant` and `primary` already clear WCAG AA on the black surface (checked
 * in SettingsTypographyTest).
 */
internal fun settingsTypography(base: Typography): Typography = base.copy(
    // The screen's own title: larger than the 16sp section titles it heads (it was the app-wide 10sp).
    headlineSmall = base.headlineSmall.sized(24.sp, 32.sp),
    titleMedium = base.titleMedium.sized(16.sp, 22.sp),
    titleSmall = base.titleSmall.sized(12.sp, 16.sp),
    bodyLarge = base.bodyLarge.sized(16.sp, 22.sp),
    bodyMedium = base.bodyMedium.sized(14.sp, 20.sp),
    bodySmall = base.bodySmall.sized(12.sp, 17.sp),
    labelSmall = base.labelSmall.sized(12.sp, 16.sp),
)

private fun TextStyle.sized(size: TextUnit, line: TextUnit) =
    copy(fontSize = size, lineHeight = line, letterSpacing = 0.2.sp)

/** Scopes [settingsTypography] to [content] (Settings and the dialogs it opens). */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = settingsTypography(MaterialTheme.typography),
        content = content,
    )
}
