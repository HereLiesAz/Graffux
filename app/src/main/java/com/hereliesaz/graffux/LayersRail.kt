@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.RailIcons
import com.hereliesaz.graffitixr.design.theme.Amber
import kotlin.math.abs

/** What the layers rail asks of the screen around it. */
internal class LayersRailCallbacks(
    val onActivate: (String) -> Unit,
    /** A layer's menu: long-press, or a second tap on the active layer. */
    val onOpenMenu: (String) -> Unit,
    /** A group row or the rail's own header expanding or collapsing, by rail id. */
    val onExpand: (String, Boolean) -> Unit,
    /**
     * A drag-reorder: the dragged item's siblings in their new order, top-first and
     * [layerRailId]-prefixed, with the parent they share (null at the top level) — exactly what
     * `EditorViewModel.onLayerRailRelocated` takes.
     */
    val onRelocate: (List<String>, String?) -> Unit,
    val onAdd: () -> Unit,
)

/**
 * The rows the layers rail shows: [layerRailRows]'s depth-first, top-first order, with every row
 * under a collapsed group left out.
 */
internal fun visibleLayerRows(layers: List<Layer>, expansion: Map<String, Boolean>): List<LayerRailRow> {
    val byId = layers.associateBy { it.id }
    fun shown(layer: Layer): Boolean {
        var parent = layer.parentId?.let(byId::get)
        val seen = HashSet<String>()
        while (parent != null && seen.add(parent.id)) {
            if (expansion[layerRailId(parent.id)] != true) return false
            parent = parent.parentId?.let(byId::get)
        }
        return true
    }
    return layerRailRows(layers).filter { shown(it.layer) }
}

/**
 * Where [movedId] lands among its siblings when dropped with its centre at [dropCenterY]: the
 * siblings' new order, top-first. [centers] holds each sibling's on-screen centre; a sibling with
 * no recorded centre keeps its place relative to the others.
 */
internal fun droppedSiblingOrder(
    siblingsTopFirst: List<String>,
    movedId: String,
    dropCenterY: Float,
    centers: Map<String, Float>,
): List<String> {
    val others = siblingsTopFirst.filter { it != movedId }
    val index = others.count { (centers[it] ?: Float.NEGATIVE_INFINITY) < dropCenterY }
    return others.toMutableList().apply { add(index, movedId) }
}

/**
 * The right layers rail (left, for the left-handed), as the mockup draws it: + new layer, then the
 * layers top-first — each its own thumbnail, the active one ringed, a hidden one dimmed and badged,
 * a locked one badged. Tap selects; a second tap or a long-press opens the layer's menu (Delete is
 * there); long-press and drag reorders a layer among its siblings (a group carries its children).
 * The whole rail is hidden from View… → Layers panel.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun LayersRail(
    layers: List<Layer>,
    activeLayerId: String?,
    secondary: Set<String>,
    expansion: Map<String, Boolean>,
    callbacks: LayersRailCallbacks,
    modifier: Modifier = Modifier,
) {
    val centers = remember { HashMap<String, Float>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    RailPill(modifier.testTag("rail.layers")) {
        RailButton(label = "Add Layer", testTag = "rail.layer.add", onClick = callbacks.onAdd) {
            RailGlyph(RailIcons.Plus)
        }
        RailDivider()
        visibleLayerRows(layers, expansion).forEach { row ->
            val layer = row.layer
            val id = layerRailId(layer.id)
            val isGroup = row.isGroup
            val expanded = expansion[id] == true
            val siblings = layers.filter { it.parentId == layer.parentId }.reversed().map { layerRailId(it.id) }
            val latestSiblings by rememberUpdatedState(siblings)
            val tap: () -> Unit = {
                when {
                    isGroup -> {
                        callbacks.onActivate(layer.id)
                        callbacks.onExpand(id, !expanded)
                    }
                    layer.id == activeLayerId -> callbacks.onOpenMenu(layer.id)
                    else -> callbacks.onActivate(layer.id)
                }
            }
            val latestTap by rememberUpdatedState(tap)
            Box(
                modifier = Modifier
                    .zIndex(if (dragging == id) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging == id) dragDy else 0f }
                    .onGloballyPositioned { centers[id] = it.boundsInRoot().center.y }
                    .pointerInput(id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val press = awaitLongPressOrCancellation(down.id)
                            if (press == null) {
                                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                val still = up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                                if (up != null && up.changedToUp() && still) {
                                    up.consume()
                                    latestTap()
                                }
                                return@awaitEachGesture
                            }
                            press.consume()
                            dragging = id
                            dragDy = 0f
                            val start = centers[id] ?: 0f
                            drag(press.id) { change ->
                                dragDy += change.positionChange().y
                                change.consume()
                            }
                            val moved = abs(dragDy) > viewConfiguration.touchSlop
                            val drop = start + dragDy
                            dragging = null
                            dragDy = 0f
                            if (moved) {
                                val order = droppedSiblingOrder(latestSiblings, id, drop, centers)
                                if (order != latestSiblings) callbacks.onRelocate(order, layer.parentId)
                            } else {
                                callbacks.onOpenMenu(layer.id)
                            }
                        }
                    }
                    .semantics {
                        onClick(label = layer.name) { tap(); true }
                        customActions = listOf(
                            CustomAccessibilityAction("Layer menu") { callbacks.onOpenMenu(layer.id); true },
                        )
                    },
            ) {
                LayerThumb(
                    layer = layer,
                    light = RailLight(active = layer.id == activeLayerId, secondary = id in secondary),
                    nested = layer.parentId != null,
                    expanded = expanded,
                )
            }
        }
    }
}

/**
 * One layer's button, as the mockup draws it: a 44dp rounded square with a hairline border (2dp
 * and ink for the active layer), the layer's content inset 3dp inside it, dimmed with an eye-off
 * badge at the corner while hidden.
 */
@Suppress("FunctionNaming")
@Composable
private fun LayerThumb(layer: Layer, light: RailLight, nested: Boolean, expanded: Boolean) {
    val isGroup = layer.type == LayerType.GROUP
    val label = layer.name + (if (layer.adjustment != null) " adjustment layer" else "") +
        (if (layer.isVisible) "" else ", hidden") + (if (isGroup && expanded) ", open" else "")
    Box(contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .size(RailButtonSize)
                .border(
                    width = if (light.active) 2.dp else 1.dp,
                    color = when {
                        light.active -> RailInk
                        light.secondary -> Amber
                        else -> RailBorder
                    },
                    shape = ThumbShape,
                )
                // The row around this handles the touch: tap, long-press and drag are one gesture.
                .semantics {
                    contentDescription = label
                    selected = light.active
                }
                .testTag("rail.${layerRailId(layer.id)}"),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(ThumbInnerShape)
                    .background(ThumbEmpty)
                    .alpha(if (layer.isVisible) 1f else HIDDEN_ALPHA),
            ) {
                val bitmap = layer.bitmap
                val adjustment = layer.adjustment
                when {
                    isGroup -> RailGlyph(GraffuxIcons.LayerGroup)
                    // An adjustment layer shows its tool's glyph, not its render (the mockup).
                    adjustment != null -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().background(AdjustmentTileFill),
                    ) {
                        Icon(
                            photoToolGlyph(adjustment.tool),
                            contentDescription = null,
                            tint = RailInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    bitmap != null -> Image(
                        painter = BitmapPainter(bitmap.asImageBitmap()),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (layer.alphaLock) {
                Text(
                    "🔒",
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp),
                )
            }
            if (!layer.isVisible) {
                Icon(
                    RailIcons.EyeOff,
                    contentDescription = null,
                    tint = EyeOffInk,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(14.dp)
                        .background(EyeOffFill, CircleShape),
                )
            }
        }
        if (nested) {
            // A child of a group: a short bar at the inner edge says it sits inside one.
            Box(Modifier.width(2.dp).height(20.dp).background(Amber.copy(alpha = 0.6f)))
        }
    }
}

/** An adjustment layer's glyph on its tile (RailIcons; the mockup draws Light, Color, Curves, Heal). */
internal fun photoToolGlyph(tool: com.hereliesaz.graffitixr.common.photo.PhotoTool): androidx.compose.ui.graphics.vector.ImageVector =
    when (tool) {
        com.hereliesaz.graffitixr.common.photo.PhotoTool.LIGHT -> RailIcons.Adjust
        com.hereliesaz.graffitixr.common.photo.PhotoTool.COLOR,
        com.hereliesaz.graffitixr.common.photo.PhotoTool.HSL -> RailIcons.ColorHalf
        com.hereliesaz.graffitixr.common.photo.PhotoTool.CURVES -> RailIcons.Curve
        com.hereliesaz.graffitixr.common.photo.PhotoTool.DETAIL -> RailIcons.Detail
        com.hereliesaz.graffitixr.common.photo.PhotoTool.EFFECTS -> RailIcons.Effects
        com.hereliesaz.graffitixr.common.photo.PhotoTool.HEAL -> RailIcons.Heal
        com.hereliesaz.graffitixr.common.photo.PhotoTool.RED_EYE -> RailIcons.Eye
    }

/** Behind an adjustment layer's glyph. */
private val AdjustmentTileFill = Color(0xFF232326)

private val ThumbShape = RoundedCornerShape(12.dp)
private val ThumbInnerShape = RoundedCornerShape(8.dp)

/** Behind a thumbnail with nothing drawn yet. */
private val ThumbEmpty = Color(0xFF141416)
private val EyeOffInk = Color(0xFFB4B4BA)
private val EyeOffFill = Color(0xFF161618)

private const val HIDDEN_ALPHA = 0.3f
