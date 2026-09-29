package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * An invisible bottom sheet: no surface, no scrim — only [content] and a small grab pill above it.
 * Drag it down to shut it (the pill stays, to pull it back up), drag or fling it up to open it, or
 * tap the pill. It settles open or shut by [carouselSheetSettlesOpen]; [onOpenChange] reports where.
 *
 * Why not AzNavRail's own `azBottomSheet` (guide §10): at PEEK that shell lays a full-screen
 * transparent tap catcher over the app (§10.4), which would stop the canvas taking a single stroke
 * while the carousel is open; its detents step on to HALF/FULL with a scrim; and it would compete for
 * the bottom edge with the shortcuts sheet that already uses it. So this lives in the carousel's own
 * `background()` page and only the sheet's own pixels take touches.
 */
@Suppress("FunctionNaming")
@Composable
internal fun CarouselSheet(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var contentHeight by remember { mutableIntStateOf(0) }
    // 0 = open; contentHeight = shut (only the grab pill still shows).
    val offset = remember { Animatable(0f) }
    var settledOnce by remember { mutableStateOf(false) }
    LaunchedEffect(open, contentHeight) {
        if (contentHeight == 0) return@LaunchedEffect
        val target = if (open) 0f else contentHeight.toFloat()
        if (settledOnce) offset.animateTo(target) else offset.snapTo(target)
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
    Box(modifier = modifier.clipToBounds()) {
        Column(
            modifier = Modifier
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GrabHandle(open) { onOpenChange(!open) }
            Box(Modifier.onSizeChanged { contentHeight = it.height }) { content() }
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
