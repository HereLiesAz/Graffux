package com.hereliesaz.graffux.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Desktop's own copy of the Android app's real color tokens (`core:design`'s `Color.kt`/
 * `Theme.kt`) — not invented independently. `core:design` is an `com.android.library` module
 * (Google-Fonts-provider + non-CMP AzNavRail dependencies), so it can't be depended on directly
 * from `:desktop`; these are the same literal values, copied by hand, rather than a from-scratch
 * palette. See DESKTOP.md's UI-parity section.
 *
 * Android's Roboto Condensed type scale (`Typography.kt`) is NOT replicated here: it's loaded via
 * `androidx.compose.ui.text.googlefonts.GoogleFont.Provider`, an Android-Google-Play-Services
 * mechanism with no desktop equivalent in this Compose Multiplatform version (1.12.1) — bundling
 * the TTFs directly would require wiring up `org.jetbrains.compose.resources`' font-resource
 * codegen (a new Gradle module convention, not just a dependency bump), which is out of scope for
 * this pass. Desktop text still renders in Compose's stock default font. See DESKTOP.md.
 *
 * [ThemeColorSyncTest] guards this copy against drifting from `core:design`: since no module can
 * see both files at compile time, it compares the brand tokens in `Color.kt` and the full
 * `DarkColorScheme` in `Theme.kt` (name by name, resolved to hex) as text instead of via the type
 * system. Keep it passing — don't edit the values here without also updating `Color.kt`,
 * or vice versa.
 */
object GraffuxColors {
    val HotPink = Color(0xFFFF00C8)
    val Cyan = Color(0xFF00FFFF)
    val NeonGreen = Color(0xFF39FF14)
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Gray = Color(0xFF5D5D5D)

    // Desktop-only names for literals core:design's DarkColorScheme inlines (kept in sync by the test).
    val SurfaceVariant = Color(0xFF2A2A30)
    val OnSurfaceVariant = Color(0xFFC6C6CE)
    val Outline = Color(0xFF4A4A54)
    val OutlineVariant = Color(0xFF3A3A42)
}

// Mirrors core:design's DarkColorScheme (Theme.kt) entry for entry; ThemeColorSyncTest compares the
// two schemes' named arguments, so keep the argument names and values identical.
private val GraffuxDarkColorScheme = darkColorScheme(
    primary = GraffuxColors.HotPink,
    secondary = GraffuxColors.Cyan,
    tertiary = GraffuxColors.NeonGreen,
    background = GraffuxColors.Black,
    surface = GraffuxColors.Black,
    surfaceVariant = GraffuxColors.SurfaceVariant,
    onPrimary = GraffuxColors.Black,
    onSecondary = GraffuxColors.Black,
    onTertiary = GraffuxColors.Black,
    onBackground = GraffuxColors.White,
    onSurface = GraffuxColors.White,
    onSurfaceVariant = GraffuxColors.OnSurfaceVariant,
    outline = GraffuxColors.Outline,
    outlineVariant = GraffuxColors.OutlineVariant,
)

/**
 * Desktop's equivalent of Android's `GraffitiXRTheme` — same color scheme, minus the Android-only
 * bits (status-bar styling, `Build.VERSION_CODES.S` dynamic color, the Roboto Condensed type
 * scale — see this file's doc comment) that have no desktop-window analog or an available port.
 */
@Composable
fun GraffuxDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GraffuxDarkColorScheme,
        content = content,
    )
}
