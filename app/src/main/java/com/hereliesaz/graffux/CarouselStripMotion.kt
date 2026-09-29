package com.hereliesaz.graffux

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop

/**
 * The strip's gesture effects on M3's [state]: [onSettle] once per user drag or fling, with the
 * item M3's snapping left in the hero slot, and a haptic `SegmentTick` each time a new item takes
 * the hero slot under the user's finger (never during [programmatic] scrolls).
 */
@Suppress("FunctionNaming")
@Composable
internal fun CarouselGestureEffects(state: CarouselState, programmatic: ProgrammaticScrolls, onSettle: (Int) -> Unit) {
    val settle by rememberUpdatedState(onSettle)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        var userGesture = false
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                userGesture = programmatic.count == 0
            } else if (userGesture) {
                userGesture = false
                settle(state.currentItem)
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentItem }.drop(1).collect {
            if (programmatic.count == 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
}

/** The key in the strip's width map that carries the hero keyline's width, not an item's. */
internal const val HERO_WIDTH_KEY = -1

/**
 * [carouselHeroPosition] over the widths the strip reports (item index to width, plus the hero
 * keyline's under [HERO_WIDTH_KEY]), or [fallback] before layout. The floor is the small cards'
 * max width, not M3's `minSize`: that one counts the 10dp anchor keylines, which would give the
 * resting small cards weight and skew the position at the ends.
 */
internal fun carouselStripPosition(widths: Map<Int, Float>, smallMax: Float, fallback: Int): Float {
    val max = widths[HERO_WIDTH_KEY]
    val position = max?.let { carouselHeroPosition(widths.filterKeys { k -> k >= 0 }, smallMax, it) }
    return position ?: fallback.toFloat()
}

/**
 * The active hero's accent: a theme-primary outline and a light primary tint over the card,
 * springing in with Expressive's fast-effects spec when the entry becomes the active one. It draws
 * nothing and composes nothing once faded out; it takes no input, so taps reach the card beneath.
 */
@Suppress("FunctionNaming")
@Composable
internal fun BoxScope.ActiveHighlight(active: Boolean) {
    val progress by animateFloatAsState(
        if (active) 1f else 0f,
        MotionScheme.expressive().fastEffectsSpec(),
        label = "heroHighlight",
    )
    if (progress <= 0f && !active) return
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = progress }
            .background(accent.copy(alpha = HIGHLIGHT_TINT_ALPHA), CardShape)
            .border(HighlightWidth, accent, CardShape)
            .testTag("carousel.hero.active"),
    )
}

private val HighlightWidth = 2.dp
private const val HIGHLIGHT_TINT_ALPHA = 0.14f
