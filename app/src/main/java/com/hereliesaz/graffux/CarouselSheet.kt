package com.hereliesaz.graffux

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Empty space kept under the carousel's content, above the navigation bar, so its grab pill stays
 * clear of the bottom edge's system gestures.
 */
internal val CarouselBottomInset = 20.dp

/** Clear space under the top accessory (the rotation slider), above the pill and the content. */
internal val TopAccessoryGap = 8.dp

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
 * full-width dark, mostly transparent scrim ([CarouselScrimBrush]). Drag it down to shut it, drag
 * or fling it up to open it. It has no grab handle; [nameLabel] may put a small name pill there.
 * The editor composes it only while it is open (it is
 * opened from the rail), so a shut sheet leaves nothing behind there. It settles open
 * or shut by [carouselSheetSettlesOpen]; [onOpenChange] reports where.
 *
 * The sheet has stages above shut (tab list, then carousel). While it is open, an upward swipe past
 * fully open calls [SheetStages.onStepUp]; a downward swipe that would shut it first asks
 * [SheetStages.onStepDown], which returns true when it stepped down a stage instead.
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
 * while the carousel is open; and its detents step on to HALF/FULL with a scrim. So this lives in the carousel's own
 * `background()` page and only the sheet's own pixels take touches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun CarouselSheet(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hiddenForDrawing: () -> Boolean = { false },
    bottomInset: Dp = 0.dp,
    stages: SheetStages? = null,
    // True while a photo slider is dragged: the sheet stays where it is (the row under the finger
    // must not move) but its scrim and pill vanish, so only that row is left over the photo.
    chromeHidden: () -> Boolean = { false },
    // The name for the small pill above the content, or null for none. The sheet has no drag
    // handle: the pill is a label, and the sheet drags from anywhere on it, pill included.
    nameLabel: () -> String? = { null },
    // Rides just above the sheet's top edge, moving with it between detents (the rotation slider).
    // Shown only while the sheet shows open.
    topAccessory: (@Composable () -> Unit)? = null,
    // Told where the sheet's top edge is (root pixels) as it opens, closes and is dragged: the
    // rotation triangle rides it.
    onTopChanged: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hideChrome by rememberUpdatedState(chromeHidden)
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
    // How far a drag has pulled up past fully open: the swipe that steps the sheet up a stage.
    var pulledUp by remember { mutableStateOf(0f) }
    val stepUpPx = with(density) { STEP_UP_DP.dp.toPx() }
    fun settle(velocityPxPerSec: Float) {
        val max = contentHeight.toFloat().coerceAtLeast(1f)
        val velocityDp = with(density) { velocityPxPerSec.toDp().value }
        val release = carouselSheetRelease(
            canStepUp = sheetCanStepUp(stages, shownOpen, offset.value),
            pulledUp = pulledUp >= stepUpPx,
            fractionShut = offset.value / max,
            velocityDpPerSec = velocityDp,
        )
        pulledUp = 0f
        applySheetRelease(release, open, stages, onOpenChange) { target ->
            scope.launch { offset.animateTo(if (target) 0f else max) }
        }
    }
    val dragState = rememberDraggableState { delta ->
        pulledUp = pulledUpAfter(pulledUp, delta, offset.value)
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
                    alpha = scrimAlpha(hideChrome(), offset.value, max)
                }
                .background(CarouselScrimBrush)
                .testTag("carousel.scrim"),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Only the handle may remain when closed. Clip the translating content before the
            // reserved bottom inset: otherwise the hidden tabs slide INTO the navigation area.
            // Open, the clip reaches down over the bottom inset (never the nav bar's) so the set
            // picker's wheel, drawn over its strip, may use that gesture-free room; it shrinks back as
            // the sheet closes, so hidden content still never slides into it.
            val insetPx = with(LocalDensity.current) { bottomInset.toPx() }
            Box(
                Modifier.drawWithContent {
                    val extra = (insetPx - offset.value).coerceAtLeast(0f)
                    clipRect(0f, 0f, size.width, size.height + extra) { this@drawWithContent.drawContent() }
                },
            ) {
                Column(
                    modifier = Modifier
                        .offset { IntOffset(0, offset.value.roundToInt()) }
                        .onGloballyPositioned { onTopChanged(it.positionInRoot().y) }
                        .draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) })
                        .nestedScroll(nested),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (topAccessory != null && shownOpen) {
                        // A gap under it, so it never sits on the name pill or the content's top.
                        Box(
                            Modifier
                                .padding(bottom = TopAccessoryGap)
                                .graphicsLayer { alpha = if (hideChrome()) 0f else 1f },
                        ) { topAccessory() }
                    }
                    nameLabel()?.let { name ->
                        Box(Modifier.graphicsLayer { alpha = if (hideChrome()) 0f else 1f }) { NamePill(name) }
                    }
                    Box(Modifier.onSizeChanged { contentHeight = it.height }) {
                        content()
                        // Over the content's top corners, taking no height: see SheetStageControls.
                        if (stages != null && shownOpen) {
                            SheetStageControls(
                                stages,
                                onClose = { onOpenChange(false) },
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = if (hideChrome()) 0f else 1f },
                            )
                        }
                    }
                }
            }
            // The app hides the system bars (immersive), so navigationBars reads 0 until a swipe
            // shows them over the sheet: keep their room whether they are showing or not.
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).height(bottomInset))
        }
    }
}

/**
 * A very small pill with [name] in it (the brush in hand). Only a label: no click, no gesture of its
 * own, so a drag on it drags the sheet like a drag anywhere else on it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun NamePill(name: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        name,
        color = colors.onSurface,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .widthIn(max = PillMaxWidth)
            .background(colors.surface.copy(alpha = PILL_ALPHA), RoundedCornerShape(PILL_ROUND_PERCENT))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("carousel.namePill"),
    )
}

internal val PillMaxWidth = 160.dp
private const val PILL_ALPHA = 0.72f
private const val PILL_ROUND_PERCENT = 50

/**
 * The pill's name: the brush in hand, only on the Brushes page ([brushesPage]) and only at the peek
 * detent (the sheet open to its set picker alone, [showingCarousel] false). Null hides it.
 */
internal fun carouselNamePill(brushesPage: Boolean, showingCarousel: Boolean, brushName: String?): String? =
    brushName?.takeIf { brushesPage && !showingCarousel && it.isNotBlank() }

/** An upward pull this far past fully open steps the sheet up a stage (tab list to carousel). */
private const val STEP_UP_DP = 32f

/** A vertical fling faster than this (dp/s) decides the sheet's side on its own. */
private const val SHEET_FLING_DP_PER_SEC = 400f

/** Past this fraction of the way shut, a slow release settles the sheet shut. */
private const val SHEET_HALFWAY = 0.5f

/**
 * The sheet's stages above shut. [onStepUp]: an upward swipe past fully open. [onStepDown]: a swipe
 * that would shut the sheet; returns true when it stepped down a stage instead.
 */
internal class SheetStages(
    val onStepUp: () -> Unit,
    val onStepDown: () -> Boolean,
    /** Whether [onStepUp] would show more: false at the top stage, where the up control is dimmed away. */
    val canStepUp: () -> Boolean,
    /** Whether [onStepDown] would show less (true) or shut the sheet (false), which the control then says. */
    val canStepDown: () -> Boolean,
    /** Whether the stage showing is the set-picker wheel, which fills one bottom corner of the content. */
    val showsSetWheel: () -> Boolean,
    /**
     * Which side the set wheel sits on (right-handed: the right). Passed explicitly because the controls are
     * composed outside the provider that gives the wheel [LocalSetWheelOnRight], and an ambient read there
     * would silently take its default.
     */
    val wheelOnRight: Boolean,
)

/** Whether a release can step the sheet up: it has stages, is showing open, and sits fully open. */
private fun sheetCanStepUp(stages: SheetStages?, shownOpen: Boolean, offset: Float): Boolean =
    stages != null && shownOpen && offset <= 1f

/** How far past fully open a drag has pulled, after one more [delta] at sheet offset [offset]. */
private fun pulledUpAfter(pulled: Float, delta: Float, offset: Float): Float = when {
    delta < 0f && offset <= 1f -> pulled - delta
    delta > 0f -> 0f
    else -> pulled
}

/**
 * Carries out [release]: steps up, steps down a stage (staying open) when [SheetStages.onStepDown] takes a
 * would-be shut, or settles via [animateOpen] and reports a changed side through [onOpenChange].
 */
private fun applySheetRelease(
    release: SheetRelease,
    open: Boolean,
    stages: SheetStages?,
    onOpenChange: (Boolean) -> Unit,
    animateOpen: (Boolean) -> Unit,
) {
    if (release == SheetRelease.STEP_UP) {
        stages?.onStepUp?.invoke()
        return
    }
    val nowOpen = release == SheetRelease.OPEN
    // A downward swipe from a stage above the first steps down one stage and stays open.
    if (!nowOpen && open && stages?.onStepDown?.invoke() == true) {
        animateOpen(true)
        return
    }
    animateOpen(nowOpen)
    if (nowOpen != open) onOpenChange(nowOpen)
}

/** What a released drag does to the sheet: step up a stage, or settle open or shut. */
internal enum class SheetRelease { STEP_UP, OPEN, SHUT }

/**
 * A released drag: a pull past fully open (or a fast upward fling from there) steps the sheet up
 * when it can; otherwise it settles by [carouselSheetSettlesOpen].
 */
internal fun carouselSheetRelease(
    canStepUp: Boolean,
    pulledUp: Boolean,
    fractionShut: Float,
    velocityDpPerSec: Float,
): SheetRelease = when {
    canStepUp && (pulledUp || velocityDpPerSec <= -SHEET_FLING_DP_PER_SEC) -> SheetRelease.STEP_UP
    carouselSheetSettlesOpen(fractionShut, velocityDpPerSec) -> SheetRelease.OPEN
    else -> SheetRelease.SHUT
}

/**
 * Where the carousel sheet settles when a drag ends: [fractionShut] is how far down it is (0 = open,
 * 1 = shut), [velocityDpPerSec] the release velocity (positive = downward). A decisive fling wins;
 * otherwise it goes to whichever side it is nearer.
 */
internal fun carouselSheetSettlesOpen(fractionShut: Float, velocityDpPerSec: Float): Boolean = when {
    velocityDpPerSec <= -SHEET_FLING_DP_PER_SEC -> true
    velocityDpPerSec >= SHEET_FLING_DP_PER_SEC -> false
    else -> fractionShut < SHEET_HALFWAY
}

/** The scrim's opacity: gone while [hidden], else fading out as the sheet shuts ([offset] of [max]). */
private fun scrimAlpha(hidden: Boolean, offset: Float, max: Float): Float = when {
    hidden -> 0f
    max <= 0f -> 1f
    else -> (1f - offset / max).coerceIn(0f, 1f)
}

/** How fast the sheet slides back once the post-stroke hold is over: quick, for the next pick. */
internal const val REOPEN_MS = 120
