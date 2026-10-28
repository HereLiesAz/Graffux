@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Height of the shut sheet's swipe strip along the bottom edge (above the navigation bar). */
internal val EdgeStripHeight = 16.dp

private val SheetShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
private const val SWIPE_PX = 24f

/**
 * A sheet that lives along the bottom edge: shut, it is a thin swipe strip and nothing else, so the
 * canvas keeps the screen; a swipe up (or a tap on the strip) opens it, a swipe down shuts it. No
 * scrim and no tap-catcher — a touch on the canvas shuts it (see [CanvasTouch]).
 */
@Suppress("FunctionNaming")
@Composable
internal fun EdgeSheet(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Travel since the drag began; a plain holder, since it never needs to recompose anything.
    val travel = remember { FloatArray(1) }
    val drag = rememberDraggableState { delta ->
        travel[0] += delta
        if (!open && travel[0] < -SWIPE_PX) { travel[0] = 0f; onOpenChange(true) }
        if (open && travel[0] > SWIPE_PX) { travel[0] = 0f; onOpenChange(false) }
    }
    val swipe = Modifier.draggable(drag, Orientation.Vertical, onDragStarted = { travel[0] = 0f })
    if (!open) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(EdgeStripHeight)
                .then(swipe)
                .clickable(onClickLabel = "Open $label") { onOpenChange(true) }
                .semantics { contentDescription = "$label, shut" }
                .testTag("edgeSheet.strip"),
        )
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .clip(SheetShape)
            .background(RailFill)
            .border(1.dp, RailBorder, SheetShape)
            .then(swipe)
            .navigationBarsPadding()
            .testTag("edgeSheet.open"),
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.4f))
                .clickable(onClickLabel = "Shut $label") { onOpenChange(false) },
        )
        content()
    }
}
