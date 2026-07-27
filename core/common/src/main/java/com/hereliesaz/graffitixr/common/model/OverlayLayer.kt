package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import java.util.UUID

@Serializable
data class TextLayerParams(
    val text: String = "",
    val fontName: String = "Roboto",
    val fontSizeDp: Float = 150f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val letterSpacingEm: Float = 0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val hasOutline: Boolean = false,
    val outlineWidthDp: Float = 4f,
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    val hasDropShadow: Boolean = false,
    val shadowRadiusDp: Float = 8f,
    val shadowDxDp: Float = 4f,
    val shadowDyDp: Float = 4f,
    val shadowColorArgb: Int = 0x88000000.toInt()
)

@Serializable
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",
    @Serializable(with = UriSerializer::class)
    val uri: Uri,
    val scale: Float = 1.0f,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val opacity: Float = 1.0f,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    // Neutral colour-balance multiplier is 1.0 (matches Layer/LegacyVisuals). The old 0f default
    // meant a save missing these keys decoded to 0 and rendered every channel black on load.
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    val alphaLock: Boolean = false,
    val clipToLayerBelow: Boolean = false,
    // Layer hierarchy — Layer already carried these at runtime, but this mapping never persisted
    // them, so a group (or a group membership) silently reset to flat/RASTER on reload.
    val parentId: String? = null,
    val layerType: LayerType = LayerType.RASTER,
    // Component links, persisted for the same reason as the hierarchy above: without them a main
    // component and its instances would reload as unrelated copies that no longer track each other.
    val componentId: String? = null,
    val instanceOf: String? = null,
    val isVisible: Boolean = true,
    val warpMesh: List<Float>? = null,
    val isSketch: Boolean = false,
    val textParams: TextLayerParams? = null,
    val isLinked: Boolean = false,
    val isInverted: Boolean = false,
    // Vector content — non-empty for a vector layer (uri is Uri.EMPTY in that case). Defaulted for
    // back-compat with projects saved before vector layers existed.
    val shapes: List<VectorShape> = emptyList()
)