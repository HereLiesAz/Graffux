package com.hereliesaz.graffux

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Whether the sheet is actually drawn open: the user's choice, overridden shut while the UI is
 * hidden for drawing (a stroke, plus [DRAWING_UI_RETURN_DELAY_MS] after it). [userOpen] is never
 * changed by a stroke, so the sheet comes back on its own after the hold — and one the user had shut
 * stays shut.
 */
internal fun carouselSheetShownOpen(userOpen: Boolean, hiddenForDrawing: Boolean): Boolean =
    userOpen && !hiddenForDrawing

/**
 * The scrim behind the carousel: black, fading in from nothing at its top edge to
 * [CAROUSEL_SCRIM_ALPHA] by [CAROUSEL_SCRIM_FADE_STOP] of its height, then deepening slightly to
 * [CAROUSEL_SCRIM_BOTTOM_ALPHA] at the screen's edge. Mostly dark, mostly transparent.
 */
internal const val CAROUSEL_SCRIM_ALPHA = 0.40f
internal const val CAROUSEL_SCRIM_BOTTOM_ALPHA = 0.45f
internal const val CAROUSEL_SCRIM_FADE_STOP = 0.22f

internal val CarouselScrimBrush: Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    CAROUSEL_SCRIM_FADE_STOP to Color.Black.copy(alpha = CAROUSEL_SCRIM_ALPHA),
    1f to Color.Black.copy(alpha = CAROUSEL_SCRIM_BOTTOM_ALPHA),
)

/**
 * A bottom sheet with no surface of its own: [content] and a small grab pill above it, over a
 * full-width dark, mostly transparent scrim ([CarouselScrimBrush]). Drag it down to shut it (the
 * pill stays, to pull it back up), drag or fling it up to open it, or tap the pill. It settles open
 * or shut by [carouselSheetSettlesOpen]; [onOpenChange] reports where.
 *
 * [hiddenForDrawing] (see [rememberDrawingUiHidden]) is read, not observed by the caller's
 * composition: while it is true the sheet snaps shut at once (no animation — the stroke must not wait
 * on it), and when it goes false (the hold after the stroke has run out) it slides back to [open] in
 * [REOPEN_MS]. It never calls [onOpenChange], so the user's choice survives.
 *
 * The scrim is drawn *behind* the draggable column, as a sibling with no pointer input, so it
 * darkens the full width without taking a single touch: the canvas beside and under the carousel's
 * cards still gets its strokes. It fades with the sheet, so a shut sheet leaves only its pill.
 * [bottomInset] is empty space kept below the content (above the navigation bar, which is added
 * too) that the scrim runs down behind, so it meets the bottom edge of the screen.
 *
 * Why not AzNavRail's own `azBottomSheet` (guide §10): at PEEK that shell lays a full-screen
 * transparent tap catcher over the app (§10.4), which would stop the canvas taking a single stroke
 * while the carousel is open; its detents step on to HALF/FULL with a scrim; and it would compete for
 * the bottom edge with the shortcuts sheet that already uses it. So this lives in the carousel's own
 * `background()` page and only the sheet's own pixels take touches.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun CarouselSheet(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hiddenForDrawing: () -> Boolean = { false },
    bottomInset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var contentHeight by remember { mutableIntStateOf(0) }
    // 0 = open; contentHeight = shut (only the grab pill still shows).
    val offset = remember { Animatable(0f) }
    var settledOnce by remember { mutableStateOf(false) }
    val hiddenNow by rememberUpdatedState(hiddenForDrawing)
    // Only this flag's flips recompose the sheet; the content lambda is not re-run by them.
    val drawing by remember { derivedStateOf { hiddenNow() } }
    val shownOpen = carouselSheetShownOpen(open, drawing)
    LaunchedEffect(shownOpen, contentHeight) {
        if (contentHeight == 0) return@LaunchedEffect
        val target = if (shownOpen) 0f else contentHeight.toFloat()
        when {
            !settledOnce || drawing -> offset.snapTo(target)
            open && !drawing && offset.value != target -> offset.animateTo(target, tween(REOPEN_MS))
            else -> offset.animateTo(target)
        }
        settledOnce = true
    }
    fun settle(velocityPxPerSec: Float) {
        val max = contentHeight.toFloat().coerceAtLeast(1f)
        val velocityDp = with(density) { velocityPxPerSec.toDp().value }
        val nowOpen = carouselSheetSettlesOpen(offset.value / max, velocityDp)
        scope.launch { offset.animateTo(if (nowOpen) 0f else max) }
        if (nowOpen != open) onOpenChange(nowOpen)
    }
    val dragState = rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, contentHeight.toFloat())) }
    }
    // A card's vertical scroll (BottomCarousel) consumes first; what it leaves over drags the sheet,
    // and a fling after the sheet moved settles it as a direct drag would.
    val settleNow by rememberUpdatedState(::settle)
    val nested = remember(dragState) {
        object : NestedScrollConnection {
            var moved = false
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                moved = true
                dragState.dispatchRawDelta(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!moved) return Velocity.Zero
                moved = false
                settleNow(available.y)
                return Velocity(0f, available.y)
            }
        }
    }
    Box(modifier = modifier.fillMaxWidth().clipToBounds(), contentAlignment = Alignment.BottomCenter) {
        // Full width, behind everything, no pointer input: darkens, never blocks.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val max = contentHeight.toFloat()
                    alpha = if (max <= 0f) 1f else (1f - offset.value / max).coerceIn(0f, 1f)
                }
                .background(CarouselScrimBrush)
                .testTag("carousel.scrim"),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(0, offset.value.roundToInt()) }
                    .draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) })
                    .nestedScroll(nested),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GrabHandle(open) { onOpenChange(!open) }
                Box(Modifier.onSizeChanged { contentHeight = it.height }) { content() }
            }
            Box(Modifier.navigationBarsPadding().height(bottomInset))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GrabHandle(open: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = HandleTouchWidth, height = HandleTouchHeight)
            .semantics { contentDescription = if (open) "Hide brushes" else "Show brushes" }
            .clickable(role = Role.Button, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = HANDLE_ALPHA), RoundedCornerShape(2.dp)),
        )
    }
}

private val HandleTouchWidth = 96.dp
private val HandleTouchHeight = 24.dp
private const val HANDLE_ALPHA = 0.6f

/** How fast the sheet slides back once the post-stroke hold is over: quick, for the next pick. */
internal const val REOPEN_MS = 120
