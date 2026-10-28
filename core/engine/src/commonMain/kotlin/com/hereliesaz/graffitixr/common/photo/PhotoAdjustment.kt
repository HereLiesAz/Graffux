package com.hereliesaz.graffitixr.common.photo

import kotlinx.serialization.Serializable

/**
 * The photo tool an adjustment layer belongs to: one per tab of the photo sheet that makes its own
 * layer. Crop is not here (it is a whole-document setting, see [DocumentCrop]); Local is not here
 * either (it paints the mask of the selected adjustment layer, whatever its tool).
 */
@Serializable
enum class PhotoTool(val label: String) {
    LIGHT("Light"),
    COLOR("Color"),
    HSL("HSL"),
    CURVES("Curves"),
    DETAIL("Detail"),
    EFFECTS("Effects"),
    HEAL("Heal"),
    RED_EYE("Red-eye"),
}

/** One control point of a curve, both axes 0..1. */
@Serializable
data class CurvePoint(val x: Float, val y: Float)

/** The curve channels, in the order the Curves tab offers them. */
@Serializable
enum class CurveChannel(val label: String) { RGB("RGB"), RED("Red"), GREEN("Green"), BLUE("Blue") }

/**
 * An adjustment layer's mask. [enabled] false: the layer applies everywhere (a new layer). Once
 * painted it is a full-resolution alpha image, kept outside project.json as [file] (a PNG beside
 * the layer files, rewritten on every change, so [revision] tells a stale cache apart).
 */
@Serializable
data class AdjustmentMask(
    val enabled: Boolean = false,
    val inverted: Boolean = false,
    val file: String? = null,
    val revision: Int = 0,
)

/** A content-aware spot fix on a Heal layer: where it was painted and where it borrowed from. */
@Serializable
data class HealSpot(
    val x: Float,
    val y: Float,
    val radius: Float,
    val sourceX: Float,
    val sourceY: Float,
    val feather: Float = 0.4f,
    val opacity: Float = 1f,
)

/** A red-eye fix: a circle whose red is pulled down to its other channels. */
@Serializable
data class RedEyeSpot(val x: Float, val y: Float, val radius: Float)

/**
 * The settings of one adjustment layer (`Layer.adjustment`): its [tool], every slider that moved
 * off its default ([values], by [PhotoSlider.id]; a missing id is that slider's default), the
 * Curves tab's curves, and the mask. Non-destructive: the layer keeps no pixels of its own, the
 * editor renders it from the layers below every time they or these settings change.
 */
@Serializable
data class PhotoAdjustment(
    val tool: PhotoTool,
    val values: Map<String, Float> = emptyMap(),
    val curves: Map<CurveChannel, List<CurvePoint>> = emptyMap(),
    val mask: AdjustmentMask = AdjustmentMask(),
    val healSpots: List<HealSpot> = emptyList(),
    val redEyeSpots: List<RedEyeSpot> = emptyList(),
) {
    /** [slider]'s value: the stored one, or its default. */
    fun value(slider: PhotoSlider): Float = values[slider.id] ?: slider.default

    /** With [slider] set to [v] (clamped to its range); its default drops the key. */
    fun with(slider: PhotoSlider, v: Float): PhotoAdjustment {
        val clamped = v.coerceIn(slider.min, slider.max)
        return copy(values = if (clamped == slider.default) values - slider.id else values + (slider.id to clamped))
    }

    /** Every slider back to its default, every curve straight; the mask stays. */
    fun reset(): PhotoAdjustment = copy(values = emptyMap(), curves = emptyMap(), healSpots = emptyList(), redEyeSpots = emptyList())

    /** True when nothing would change a pixel (the layer is still worth keeping for its mask). */
    val isNeutral: Boolean
        get() = values.isEmpty() && curves.values.all { it.isEmpty() || it == IDENTITY_CURVE } &&
            healSpots.isEmpty() && redEyeSpots.isEmpty()

    companion object {
        /** A straight curve: the Curves tab's starting points. */
        val IDENTITY_CURVE: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/**
 * Crop is a whole-document setting, not a layer: the full image is kept and re-opening Crop shows
 * it again. [left]..[bottom] are fractions of the document (0..1); [straighten] degrees;
 * [vertical]/[horizontal] perspective -100..100; [quarterTurns] clockwise; flips.
 */
@Serializable
data class DocumentCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val aspect: String = "free",
    val straighten: Float = 0f,
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    val isIdentity: Boolean
        get() = this == DocumentCrop() || this == DocumentCrop(aspect = aspect)
}
