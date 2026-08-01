package com.hereliesaz.graffitixr.design.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
    onPrimary = White,
    onSecondary = White,
    onTertiary = White,
    onBackground = White,
    onSurface = White,
    // Dimmed, not white: this is what makes a subtitle read as subordinate to its title.
    onSurfaceVariant = Color(0xFFC6C6CE),
    outline = Color(0xFF4A4A54),
    outlineVariant = Color(0xFF3A3A42),
)

private val LightColorScheme = lightColorScheme(
    primary = HotPink,
    secondary = Cyan,
    tertiary = NeonGreen

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * The main theme composable for GraffitiXR.
 * Applies the Material 3 color scheme and typography.
 *
 * @param darkTheme Whether to use the dark theme (defaults to system setting).
 * @param dynamicColor Whether to use Android 12+ dynamic colors (wallpaper-based).
 * @param content The content to be styled.
 */
@Composable
fun GraffitiXRTheme(
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
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
