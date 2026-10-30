@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "FunctionNaming", "LongMethod")

package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/** What the layers rail asks of the screen around it. */
internal class LayersRailCallbacks(
    val onActivate: (String) -> Unit,
    /** A layer's menu: a click on the active layer, or a right-click on any. */
    val onOpenMenu: (String) -> Unit,
    /** A drag-reorder: the layer and how many places it moved up (negative: down). */
    val onMove: (String, Int) -> Unit,
    val onAdd: () -> Unit,
)

/**
 * The layers rail, as Android's (`app/LayersRail.kt`) draws it: a pill with Add at the top, then one
 * 44dp tile per layer, top layer first. A click activates a layer, a second click (or a right-click)
 * opens its menu, and a vertical drag moves it through the stack.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LayersRail(
    layers: List<DesktopLayer>,
    activeLayerId: String?,
    callbacks: LayersRailCallbacks,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { (RailTargetSize + 6.dp).toPx() }
    RailPill(modifier) {
        RailButton(label = "Add Layer", onClick = callbacks.onAdd, icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(RailGlyphs.Plus), shortcut = Shortcuts.NewLayer.label)
        RailDivider()
        layers.asReversed().forEach { layer ->
            val tap by rememberUpdatedState {
                if (layer.id == activeLayerId) callbacks.onOpenMenu(layer.id) else callbacks.onActivate(layer.id)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(RailTargetSize)
                    .zIndex(if (dragging == layer.id) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging == layer.id) dragDy else 0f }
                    .onClick(onClick = { tap() })
                    .onClick(matcher = androidx.compose.foundation.PointerMatcher.mouse(PointerButton.Secondary)) { callbacks.onOpenMenu(layer.id) }
                    .pointerInput(layer.id) {
                        detectDragGestures(
                            onDragStart = { dragging = layer.id; dragDy = 0f },
                            onDragEnd = {
                                // Top-first on screen: dragging down moves the layer down the stack.
                                val places = -(dragDy / rowPx).roundToInt()
                                dragging = null
                                dragDy = 0f
                                if (places != 0) callbacks.onMove(layer.id, places)
                            },
                            onDragCancel = { dragging = null; dragDy = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragDy += amount.y
                        }
                    },
            ) {
                LayerThumb(layer, active = layer.id == activeLayerId)
            }
        }
    }
}

/**
 * One layer's tile, as the mockup draws it: a 44dp rounded square with a hairline border (2dp and
 * ink for the active layer), the layer's pixels inset 3dp inside it, dimmed with an eye-off badge
 * at the corner while hidden.
 */
@Composable
private fun LayerThumb(layer: DesktopLayer, active: Boolean) {
    val thumb = rememberThumbnail(layer.image)
    Box(
        modifier = Modifier
            .size(RailButtonSize)
            .border(if (active) 2.dp else 1.dp, if (active) RailInk else RailBorder, ThumbShape)
            .semantics {
                contentDescription = layer.name + if (layer.visible) "" else ", hidden"
                selected = active
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(ThumbInnerShape)
                .background(ThumbEmpty)
                .alpha(if (layer.visible) 1f else HIDDEN_ALPHA),
        ) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (!layer.visible) {
            Icon(
                androidx.compose.ui.graphics.vector.rememberVectorPainter(RailGlyphs.EyeOff),
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
}

/** A small copy of [image] for a tile; made once per image (layers are immutable). */
@Composable
private fun rememberThumbnail(image: BufferedImage): ImageBitmap = remember(image) {
    val scale = THUMB_PX.toDouble() / maxOf(image.width, image.height, 1)
    val w = (image.width * scale).roundToInt().coerceAtLeast(1)
    val h = (image.height * scale).roundToInt().coerceAtLeast(1)
    BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
    }.toComposeImageBitmap()
}

private const val THUMB_PX = 96
private const val HIDDEN_ALPHA = 0.35f
private val ThumbShape = RoundedCornerShape(12.dp)
private val ThumbInnerShape = RoundedCornerShape(8.dp)
private val ThumbEmpty = Color(0xFF141416)
private val EyeOffInk = Color(0xFFB4B4BA)
private val EyeOffFill = Color(0xFF161618)
