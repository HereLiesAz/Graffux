@file:Suppress("MaxLineLength", "FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.isFrame

/**
 * A group or a frame on the canvas: a full-canvas box carrying the layer's transform, opacity and
 * blend mode, with its children drawn inside. A frame also fills its box behind the children and,
 * when it clips, clips them to it — on the layer's own RenderNode, so the clip costs nothing per
 * frame beyond itself.
 */
@Composable
internal fun ContainerLayerBox(layer: Layer, modifier: Modifier, frameAlpha: Float, content: @Composable () -> Unit) {
    val isFrame = layer.isFrame
    val frameShape = remember(layer.layoutWidth, layer.layoutHeight, layer.frameRadius) {
        FrameBoxShape(layer.layoutWidth, layer.layoutHeight, layer.frameRadius)
    }
    val fill = layer.frameFillArgb.takeIf { isFrame }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (isFrame && layer.clipsContent) {
                    clip = true
                    shape = frameShape
                }
                translationX = layer.offset.x
                translationY = layer.offset.y
                scaleX = layer.scale
                scaleY = layer.scale
                rotationX = layer.rotationX
                rotationY = layer.rotationY
                rotationZ = layer.rotationZ
                alpha = layer.opacity * frameAlpha
                transformOrigin = TransformOrigin.Center
                blendMode = layer.blendMode
                compositingStrategy = if (layer.blendMode != BlendMode.SrcOver) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
            }
            .then(
                if (fill != null) {
                    Modifier.drawBehind {
                        frameShape.boxIn(size)?.let { box ->
                            drawRoundRect(Color(fill.toInt()), box.topLeft, box.size, CornerRadius(layer.frameRadius))
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) { content() }
}

/**
 * A frame's box as a [Shape]: [width] x [height] with corner [radius], centred in whatever size it
 * is asked for (the full-canvas container the frame draws in).
 */
private class FrameBoxShape(val width: Float, val height: Float, val radius: Float) : Shape {
    fun boxIn(size: Size): Rect? =
        if (width <= 0f || height <= 0f) {
            null
        } else {
            Rect(size.width / 2f - width / 2f, size.height / 2f - height / 2f, size.width / 2f + width / 2f, size.height / 2f + height / 2f)
        }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val box = boxIn(size) ?: return Outline.Rectangle(Rect.Zero)
        return if (radius <= 0f) Outline.Rectangle(box) else Outline.Rounded(RoundRect(box, CornerRadius(radius)))
    }
}
