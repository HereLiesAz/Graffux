package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

fun Layer.toOverlayLayer(): OverlayLayer {
    return OverlayLayer(
        id = id,
        name = name,
        uri = uri ?: android.net.Uri.EMPTY,
        scale = scale,
        offset = offset,
        rotationX = rotationX,
        rotationY = rotationY,
        rotationZ = rotationZ,
        opacity = opacity,
        blendMode = blendMode.toModelBlendMode(),
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = colorBalanceR,
        colorBalanceG = colorBalanceG,
        colorBalanceB = colorBalanceB,
        isImageLocked = isImageLocked,
        alphaLock = alphaLock,
        clipToLayerBelow = clipToLayerBelow,
        parentId = parentId,
        // A frame is saved as a group plus isFrame, so an older app opens it as a group.
        layerType = if (type == com.hereliesaz.graffitixr.common.model.LayerType.FRAME) {
            com.hereliesaz.graffitixr.common.model.LayerType.GROUP
        } else {
            type
        },
        isFrame = type == com.hereliesaz.graffitixr.common.model.LayerType.FRAME,
        frameFillArgb = frameFillArgb,
        clipsContent = clipsContent,
        frameRadius = frameRadius,
        componentId = componentId,
        instanceOf = instanceOf,
        constraints = constraints,
        autoLayout = autoLayout,
        layoutWidth = layoutWidth,
        layoutHeight = layoutHeight,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        originX = originX,
        originY = originY,
        isVisible = isVisible,
        warpMesh = warpMesh,
        isSketch = isSketch,
        textParams = textParams,
        isLinked = isLinked,
        isInverted = isInverted,
        shapes = shapes,
        adjustment = adjustment,
    )
}

fun OverlayLayer.toLayer(): Layer {
    return Layer(
        id = id,
        name = name,
        type = if (isFrame) com.hereliesaz.graffitixr.common.model.LayerType.FRAME else layerType,
        frameFillArgb = frameFillArgb,
        clipsContent = clipsContent,
        frameRadius = frameRadius,
        parentId = parentId,
        componentId = componentId,
        instanceOf = instanceOf,
        constraints = constraints,
        autoLayout = autoLayout,
        layoutWidth = layoutWidth,
        layoutHeight = layoutHeight,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        originX = originX,
        originY = originY,
        uri = if (uri == android.net.Uri.EMPTY) null else uri,
        scale = scale,
        offset = offset,
        rotationX = rotationX,
        rotationY = rotationY,
        rotationZ = rotationZ,
        opacity = opacity,
        blendMode = blendMode.toComposeBlendMode(),
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = colorBalanceR,
        colorBalanceG = colorBalanceG,
        colorBalanceB = colorBalanceB,
        isImageLocked = isImageLocked,
        alphaLock = alphaLock,
        clipToLayerBelow = clipToLayerBelow,
        isVisible = isVisible,
        warpMesh = warpMesh ?: emptyList(),
        isSketch = isSketch,
        textParams = textParams,
        isLinked = isLinked,
        isInverted = isInverted,
        shapes = shapes,
        adjustment = adjustment,
    )
}

fun ComposeBlendMode.toModelBlendMode(): ModelBlendMode {
    return when (this) {
        ComposeBlendMode.SrcOver -> ModelBlendMode.SrcOver
        ComposeBlendMode.Multiply -> ModelBlendMode.Multiply
        ComposeBlendMode.Screen -> ModelBlendMode.Screen
        ComposeBlendMode.Overlay -> ModelBlendMode.Overlay
        ComposeBlendMode.Darken -> ModelBlendMode.Darken
        ComposeBlendMode.Lighten -> ModelBlendMode.Lighten
        ComposeBlendMode.ColorDodge -> ModelBlendMode.ColorDodge
        ComposeBlendMode.ColorBurn -> ModelBlendMode.ColorBurn
        ComposeBlendMode.Hardlight -> ModelBlendMode.HardLight
        ComposeBlendMode.Softlight -> ModelBlendMode.SoftLight
        ComposeBlendMode.Difference -> ModelBlendMode.Difference
        ComposeBlendMode.Exclusion -> ModelBlendMode.Exclusion
        ComposeBlendMode.Hue -> ModelBlendMode.Hue
        ComposeBlendMode.Saturation -> ModelBlendMode.Saturation
        ComposeBlendMode.Color -> ModelBlendMode.Color
        ComposeBlendMode.Luminosity -> ModelBlendMode.Luminosity
        ComposeBlendMode.Clear -> ModelBlendMode.Clear
        ComposeBlendMode.Src -> ModelBlendMode.Src
        ComposeBlendMode.Dst -> ModelBlendMode.Dst
        ComposeBlendMode.DstOver -> ModelBlendMode.DstOver
        ComposeBlendMode.SrcIn -> ModelBlendMode.SrcIn
        ComposeBlendMode.DstIn -> ModelBlendMode.DstIn
        ComposeBlendMode.SrcOut -> ModelBlendMode.SrcOut
        ComposeBlendMode.DstOut -> ModelBlendMode.DstOut
        ComposeBlendMode.SrcAtop -> ModelBlendMode.SrcAtop
        ComposeBlendMode.DstAtop -> ModelBlendMode.DstAtop
        ComposeBlendMode.Xor -> ModelBlendMode.Xor
        ComposeBlendMode.Plus -> ModelBlendMode.Plus
        ComposeBlendMode.Modulate -> ModelBlendMode.Modulate
        else -> ModelBlendMode.SrcOver
    }
}
