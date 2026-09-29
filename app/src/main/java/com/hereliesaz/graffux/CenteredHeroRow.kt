package com.hereliesaz.graffux

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A centred hero row: small · medium · HERO · medium · small, always symmetric about the centre.
 *
 * Why not M3: material3 1.5.0-alpha29's `HorizontalCenteredHeroCarousel` picks its own keylines and
 * drops the medium ones at phone width, and the custom-keyline API behind it (`Carousel`,
 * `keylineListOf`, `CarouselAlignment`) is `internal`. So each item's size and centre come from its
 * distance to the centre ([carouselSlot], pure and tested), and the row snaps one item at a time.
 *
 * [position] is the row's continuous scroll position (the fractional index in the hero slot),
 * hoisted so the caller can read it too (the preview crossfade above the hero). [centredIndex] is
 * the item to hold in the hero slot; the row animates there whenever it changes. When a drag or
 * fling comes to rest, [onSettle] gets the index now in the hero slot.
 * [itemContent] gets the item's laid-out width in px, which the caller turns into a tier.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun CenteredHeroRow(
    count: Int,
    position: Animatable<Float, AnimationVector1D>,
    centredIndex: Int?,
    widthPx: Float,
    spacing: Dp,
    onSettle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (index: Int, sizePx: Float) -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val sizes = carouselKeylineSizes(widthPx, spacingPx)
    val scope = rememberCoroutineScope()
    val maxIndex = (count - 1).coerceAtLeast(0).toFloat()
    val settle by rememberUpdatedState(onSettle)
    // External selection changes (the rail, undo, a settle's own pick) re-centre the row. A settle
    // selects the index it already rests on, so this animates nowhere and nothing loops.
    LaunchedEffect(centredIndex, count) {
        if (centredIndex != null) position.animateTo(centredIndex.toFloat().coerceIn(0f, maxIndex))
    }
    // One item's travel: from the hero keyline to the medium one.
    val stepPx = sizes.hero / 2f + spacingPx + sizes.medium / 2f
    val drag = rememberDraggableState { delta ->
        scope.launch { position.snapTo((position.value - delta / stepPx).coerceIn(0f, maxIndex)) }
    }
    val pos = position.value
    val first = (floor(pos).toInt() - VISIBLE_REACH).coerceAtLeast(0)
    val last = (floor(pos).toInt() + VISIBLE_REACH + 1).coerceAtMost(count - 1)
    Layout(
        modifier = modifier
            .clipToBounds()
            .draggable(
                drag,
                Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val target = carouselSnapTarget(position.value, -velocity / stepPx, count)
                    position.animateTo(target.toFloat())
                    if (count > 0) settle(target)
                },
            ),
        content = {
            for (i in first..last) {
                val slot = carouselSlot(i - pos, widthPx, spacingPx)
                key(i) { Box { itemContent(i, slot.size) } }
            }
        },
    ) { measurables, constraints ->
        val placements = measurables.mapIndexed { n, m ->
            val slot = carouselSlot(first + n - pos, widthPx, spacingPx)
            val w = slot.size.roundToInt().coerceAtLeast(1)
            m.measure(Constraints.fixed(w, constraints.maxHeight)) to (slot.center - w / 2f).roundToInt()
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placements.forEach { (p, x) -> p.place(x, 0) }
        }
    }
}

private const val VISIBLE_REACH = 3
