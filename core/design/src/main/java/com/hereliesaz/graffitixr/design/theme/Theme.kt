package com.hereliesaz.graffitixr.design.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// The canvas is the subject, so the chrome around it is black. But `surfaceVariant` and
// `onSurfaceVariant` are not decoration — they are the *second* tone in each pair, and a set of
// components relies on the difference: a floating window's title bar against its body, a project
// card against the page behind it, a FAB against the canvas, secondary text against primary.
// Collapsing all four onto Black/White (as this scheme originally did) removes every one of those
// distinctions at once, which does not read as a dark theme so much as a missing one. The variants
// are therefore near-black and near-white rather than identical to their partners.
private val DarkColorScheme = darkColorScheme(
    primary = HotPink,
    secondary = Cyan,
    tertiary = NeonGreen,
    background = Black,
    surface = Black,
    // Several call sites draw this at 40% over black, so it has to be light enough to survive that
    // and still stay clearly behind the content it sits under.
    surfaceVariant = Color(0xFF2A2A30),
    // Black, not White: the accents are neon. White on HotPink is ~3.5:1, on Cyan ~1.25:1 and on
    // NeonGreen ~1.36:1; Black is ~6:1, ~16.7:1 and ~14.6:1.
    onPrimary = Black,
    onSecondary = Black,
    onTertiary = Black,
    onBackground = White,
    onSurface = White,
    // Dimmed, not white: this is what makes a subtitle read as subordinate to its title.
    onSurfaceVariant = Color(0xFFC6C6CE),
    outline = Color(0xFF4A4A54),
    outlineVariant = Color(0xFF3A3A42),
    // No tonal tint: elevated surfaces stay black instead of picking up a HotPink cast.
    surfaceTint = Color(0x00000000), // transparent
)

/**
 * The main theme composable for GraffitiXR.
 * Applies the Material 3 color scheme and typography.
 *
 * Graffux is a dark app on every device: the light scheme it used to fall back to (on any phone
 * set to light mode) left theme-coloured text near-black on the app's black panels.

 * @param dynamicColor Whether to use Android 12+ dynamic colors (wallpaper-based).
 * @param content The content to be styled.
 */
@Suppress("FunctionNaming")
@Composable
fun GraffitiXRTheme(
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }

        else -> DarkColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        Surface(
            color = colorScheme.background,
            modifier = Modifier.fillMaxSize(),
            content = content
        )
    }
}
