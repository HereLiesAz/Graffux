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
 * mechanism with no desktop equivalent in this Compose Multiplatform version (1.12.0) — bundling
 * the TTFs directly would require wiring up `org.jetbrains.compose.resources`' font-resource
 * codegen (a new Gradle module convention, not just a dependency bump), which is out of scope for
 * this pass. Desktop text still renders in Compose's stock default font. See DESKTOP.md.
 */
object GraffuxColors {
    val HotPink = Color(0xFFFF00C8)
    val Cyan = Color(0xFF00FFFF)
    val NeonGreen = Color(0xFF39FF14)
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val DarkGrey = Color(0xFF2C2C2C)
    val Gray = Color(0xFF5D5D5D)
}

private val GraffuxDarkColorScheme = darkColorScheme(
    primary = GraffuxColors.HotPink,
    secondary = GraffuxColors.Cyan,
    tertiary = GraffuxColors.NeonGreen,
    background = GraffuxColors.Black,
    surface = GraffuxColors.DarkGrey,
    onPrimary = GraffuxColors.Black,
    onSecondary = GraffuxColors.Black,
    onTertiary = GraffuxColors.Black,
    onBackground = GraffuxColors.White,
    onSurface = GraffuxColors.White,
    outline = GraffuxColors.Gray,
    outlineVariant = GraffuxColors.HotPink,
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
