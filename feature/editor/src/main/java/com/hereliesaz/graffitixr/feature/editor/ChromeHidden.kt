package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag

/**
 * Whether the host has hidden its chrome because a finger is on the canvas. A lambda, read inside
 * [HiddenWhileDrawing], so only what hides recomposes when it flips. The default never hides.
 */
val LocalChromeHidden = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Alpha of hidden chrome: a faint silhouette, so the user still knows where everything is. */
const val CHROME_GHOST_ALPHA = 0.08f

/** How long the chrome takes to fade down to [CHROME_GHOST_ALPHA] when a finger lands. */
const val CHROME_FADE_OUT_MS = 150

/** How long it takes to fade back to full once the hold after the stroke runs out. */
const val CHROME_FADE_IN_MS = 200

/** Test tag on [GhostWhileDrawing]'s wrapper; its testing `alpha` is read through the layer. */
const val CHROME_GHOST_TAG = "chrome.ghost"

// Where ghosted chrome is laid out (and drawn back from): far off every screen, so no touch can land on it.
private const val GHOST_PARK_PX = 1 shl 20

/** The alpha [GhostWhileDrawing] is heading for while the chrome is [hidden] or shown. */
fun chromeTargetAlpha(hidden: Boolean): Float = if (hidden) CHROME_GHOST_ALPHA else 1f

/**
 * [content] at full alpha while the chrome is shown; while [hidden], faded (in [CHROME_FADE_OUT_MS])
 * to a [CHROME_GHOST_ALPHA] silhouette that takes no touches and has no semantics, then faded back
 * (in [CHROME_FADE_IN_MS]). The content stays composed, so nothing is rebuilt when it comes back.
 *
 * Only `graphicsLayer` alpha animates: no frame of the fade recomposes or relays out anything.
 * While hidden, the content is *placed* [GHOST_PARK_PX] off screen and *drawn* back where it was,
 * so hit testing finds nothing there and the canvas underneath takes the stroke. Touches return the
 * moment the fade in starts. Fills the parent Box, so the content's `Modifier.align` still works.
 */
@Suppress("FunctionNaming")
@Composable
fun GhostWhileDrawing(hidden: () -> Boolean, content: @Composable () -> Unit) {
    val latest by rememberUpdatedState(hidden)
    val ghosted by remember { derivedStateOf { latest() } }
    val alpha by animateFloatAsState(
        targetValue = chromeTargetAlpha(ghosted),
        animationSpec = tween(if (ghosted) CHROME_FADE_OUT_MS else CHROME_FADE_IN_MS),
        label = "chromeGhost",
    )
    val park = if (ghosted) GHOST_PARK_PX else 0
    Box(
        Modifier
            .fillMaxSize()
            .testTag(CHROME_GHOST_TAG)
            .graphicsLayer { this.alpha = alpha }
            .drawWithContent { translate(top = -park.toFloat()) { this@drawWithContent.drawContent() } }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, park) }
            }
            .then(if (ghosted) Modifier.clearAndSetSemantics {} else Modifier),
    ) { content() }
}

/** [content] ghosted while the host's chrome is hidden (see [LocalChromeHidden], [GhostWhileDrawing]). */
@Suppress("FunctionNaming")
@Composable
fun HiddenWhileDrawing(content: @Composable () -> Unit) {
    GhostWhileDrawing(LocalChromeHidden.current, content)
}
