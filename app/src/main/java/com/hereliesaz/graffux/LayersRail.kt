@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.isFrame
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    /** A layer dragged onto the trash that appears while dragging: delete it (undoable). */
    val onDelete: (String) -> Unit = {},
)

/** Whether a drag released at [finger] (window px) lands on the trash at [trash] (window px). */
internal fun droppedOnTrash(finger: Offset, trash: Rect?): Boolean =
    trash != null && !trash.isEmpty && trash.inflate(TRASH_SLOP_PX).contains(finger)

/**
 * Where the trash shows while a layer is dragged: centred at the bottom of the window, clear of
 * the rails. Records the rectangle it placed so the drop can be hit-tested in the same space.
 */
private class TrashPosition(private val onPlaced: (Rect) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (windowSize.width - popupContentSize.width) / 2
        val y = windowSize.height - popupContentSize.height - (windowSize.height / TRASH_BOTTOM_FRACTION)
        onPlaced(Rect(x.toFloat(), y.toFloat(), (x + popupContentSize.width).toFloat(), (y + popupContentSize.height).toFloat()))
        return IntOffset(x, y)
    }
}

/** The trash a dragged layer is dropped on; lit while the finger is over it. */
@Composable
private fun LayerTrash(hot: Boolean, onPlaced: (Rect) -> Unit) {
    val provider = remember { TrashPosition(onPlaced) }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TrashSize)
                .clip(CircleShape)
                .background(if (hot) RailInk else TrashFill)
                .border(1.dp, if (hot) RailInk else RailBorder, CircleShape)
                .semantics { contentDescription = "Delete layer" }
                .testTag("rail.layer.trash"),
        ) {
            Icon(
                painterResource(GraffuxIcons.LayerDelete),
                contentDescription = null,
                tint = if (hot) TrashFill else RailInk,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

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
 * there); long-press and drag reorders a layer among its siblings (a group carries its children),
 * and dropping it on the trash that appears while dragging deletes it (undoable).
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
    /** Masked adjustment layers' mask thumbnails (ALPHA_8), for their badge. */
    maskThumbs: Map<String, android.graphics.Bitmap> = emptyMap(),
) {
    val centers = remember { HashMap<String, Float>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var overTrash by remember { mutableStateOf(false) }
    val trashRect = remember { arrayOfNulls<Rect>(1) }
    val windowOrigins = remember { HashMap<String, Offset>() }
    if (dragging != null) LayerTrash(hot = overTrash) { trashRect[0] = it }
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
                    // Before graphicsLayer, so the gesture reads untranslated coordinates.
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
                            dragDx = 0f
                            overTrash = false
                            trashRect[0] = null
                            val start = centers[id] ?: 0f
                            val downInWindow = (windowOrigins[id] ?: Offset.Zero) + press.position
                            drag(press.id) { change ->
                                val delta = change.positionChange()
                                dragDy += delta.y
                                dragDx += delta.x
                                overTrash = droppedOnTrash(downInWindow + Offset(dragDx, dragDy), trashRect[0])
                                change.consume()
                            }
                            val moved = abs(dragDy) > viewConfiguration.touchSlop || abs(dragDx) > viewConfiguration.touchSlop
                            val trashed = droppedOnTrash(downInWindow + Offset(dragDx, dragDy), trashRect[0])
                            val drop = start + dragDy
                            dragging = null
                            dragDy = 0f
                            dragDx = 0f
                            overTrash = false
                            if (trashed) {
                                callbacks.onDelete(layer.id)
                            } else if (moved) {
                                val order = droppedSiblingOrder(latestSiblings, id, drop, centers)
                                if (order != latestSiblings) callbacks.onRelocate(order, layer.parentId)
                            } else {
                                callbacks.onOpenMenu(layer.id)
                            }
                        }
                    }
                    .graphicsLayer {
                        translationY = if (dragging == id) dragDy else 0f
                        translationX = if (dragging == id) dragDx else 0f
                    }
                    .onGloballyPositioned {
                        if (dragging != id) {
                            centers[id] = it.boundsInRoot().center.y
                            windowOrigins[id] = it.boundsInWindow().topLeft
                        }
                    }
                    .semantics {
                        onClick(label = layer.name) { tap(); true }
                        customActions = listOf(
                            CustomAccessibilityAction("Layer menu") { callbacks.onOpenMenu(layer.id); true },
                            CustomAccessibilityAction("Delete layer") { callbacks.onDelete(layer.id); true },
                        )
                    },
            ) {
                LayerThumb(
                    maskThumb = maskThumbs[layer.id]?.takeIf { layer.adjustment?.mask?.enabled == true },
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
private fun LayerThumb(
    layer: Layer,
    light: RailLight,
    nested: Boolean,
    expanded: Boolean,
    maskThumb: android.graphics.Bitmap? = null,
) {
    val isGroup = layer.isContainer
    val label = layer.name + (if (layer.adjustment != null) " adjustment layer" else "") +
        (if (maskThumb != null) ", masked" else "") +
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
                    // A frame shows the mockup's # glyph; a plain group its stack glyph.
                    layer.isFrame -> RailGlyph(RailIcons.Frame)
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
            if (maskThumb != null) {
                // The mask badge: a real thumbnail of the mask, white on black, at the bottom-left.
                Image(
                    painter = BitmapPainter(maskThumb.asImageBitmap()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(RailInk),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-3).dp, y = 3.dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black)
                        .border(1.dp, MaskBadgeBorder, RoundedCornerShape(4.dp))
                        .testTag("rail.mask.${layer.id}"),
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
private val MaskBadgeBorder = Color(0xFF8A8A90)
private val EyeOffInk = Color(0xFFB4B4BA)
private val EyeOffFill = Color(0xFF161618)

private const val HIDDEN_ALPHA = 0.3f

private val TrashSize = 56.dp
private val TrashFill = Color(0xFF161618)
private const val TRASH_SLOP_PX = 24f
private const val TRASH_BOTTOM_FRACTION = 8
