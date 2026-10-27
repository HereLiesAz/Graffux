package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the host has hidden its chrome because a finger is on the canvas. A lambda, read inside
 * [HiddenWhileDrawing], so only what hides recomposes when it flips. The default never hides.
 */
val LocalChromeHidden = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Composes [content] only while the host's chrome is shown (see [LocalChromeHidden]). */
@Suppress("FunctionNaming")
@Composable
fun HiddenWhileDrawing(content: @Composable () -> Unit) {
    if (!LocalChromeHidden.current()) content()
}
