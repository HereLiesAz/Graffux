package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.util.RadialMenuGeometry
import kotlin.math.cos
import kotlin.math.sin

/** One wedge of the [QuickMenu]: what it says, and what it does when released on. */
data class QuickAction(
    val label: String,
    val enabled: Boolean = true,
    val onInvoke: () -> Unit,
)

private val MENU_RADIUS = 104.dp
private val INNER_RADIUS = 34.dp

/**
 * Procreate's QuickMenu: a radial ring of six actions that opens **at the finger**, so the action
 * you want is a flick away rather than a trip to a panel.
 *
 * The whole interaction is one continuous gesture — the menu opens under the fingers that summoned
 * it, you drag toward a wedge, and releasing fires it. Releasing in the middle (the dead zone) or
 * on a disabled wedge dismisses without doing anything, so there is always a way to back out that
 * doesn't require hitting a target.
 *
 * A tap anywhere also dismisses: the menu is modal over the canvas, and an unrecognised tap should
 * close it rather than fall through to paint on the artwork underneath.
 */
@Composable
fun QuickMenu(
    center: Offset,
    actions: List<QuickAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val outerPx = with(density) { MENU_RADIUS.toPx() }
    val innerPx = with(density) { INNER_RADIUS.toPx() }
    val measurer = rememberTextMeasurer()
    var highlighted by remember(center) { mutableStateOf<Int?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(center, actions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    highlighted = RadialMenuGeometry.slotAt(center, down.position, actions.size, innerPx)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        highlighted =
                            RadialMenuGeometry.slotAt(center, change.position, actions.size, innerPx)
                        change.consume()
                        if (!change.pressed) {
                            val slot = highlighted
                            val action = slot?.let { actions.getOrNull(it) }
                            // Dismiss first: an action that changes the tool or the layer set would
                            // otherwise leave the menu drawn over the result of its own work.
                            onDismiss()
                            highlighted = null
                            if (action != null && action.enabled) action.onInvoke()
                            break
                        }
                    }
                }
            }
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // Scrim: dims the artwork so the ring reads, and makes it obvious the canvas is not
            // taking paint right now.
            drawRect(Color.Black.copy(alpha = 0.35f))
            drawQuickMenu(center, outerPx, innerPx, actions, highlighted, measurer)
        }
    }
}

private fun DrawScope.drawQuickMenu(
    center: Offset,
    outer: Float,
    inner: Float,
    actions: List<QuickAction>,
    highlighted: Int?,
    measurer: TextMeasurer,
) {
    val slots = actions.size
    if (slots == 0) return
    val wedge = 360f / slots
    val topLeft = Offset(center.x - outer, center.y - outer)
    val size = Size(outer * 2, outer * 2)

    actions.forEachIndexed { i, action ->
        // Sweeps are drawn from the wedge's leading edge; slot 0 is centred on straight up, so the
        // arc starts half a wedge before it.
        val start = RadialMenuGeometry.slotCenterAngle(i, slots) - wedge / 2f
        val isOn = highlighted == i
        val fill = when {
            !action.enabled -> Color.White.copy(alpha = 0.05f)
            isOn -> Color.White.copy(alpha = 0.28f)
            else -> Color.Black.copy(alpha = 0.55f)
        }
        // Inset by 1px so adjacent wedges don't share antialiased edge pixels and shimmer.
        drawArc(fill, start + 0.6f, wedge - 1.2f, useCenter = true, topLeft = topLeft, size = size)
        drawArc(
            Color.White.copy(alpha = if (isOn) 0.9f else 0.25f),
            start + 0.6f, wedge - 1.2f, useCenter = true,
            topLeft = topLeft, size = size, style = Stroke(width = 1.dp.toPx()),
        )

        val mid = Math.toRadians(RadialMenuGeometry.slotCenterAngle(i, slots).toDouble())
        val labelR = (outer + inner) / 2f
        val anchor = Offset(
            center.x + (cos(mid) * labelR).toFloat(),
            center.y + (sin(mid) * labelR).toFloat(),
        )
        val style = TextStyle(
            color = if (action.enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
        )
        val laid = measurer.measure(action.label, style)
        drawText(
            laid, topLeft = Offset(
                anchor.x - laid.size.width / 2f,
                anchor.y - laid.size.height / 2f,
            )
        )
    }

    // The dead zone, drawn so it's visibly a place you can let go without committing.
    drawCircle(Color.Black.copy(alpha = 0.75f), radius = inner, center = center)
    drawCircle(
        Color.White.copy(alpha = 0.35f), radius = inner, center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
}
