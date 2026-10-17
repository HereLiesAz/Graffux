package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.LayerFrame
import kotlin.math.ceil

/**
 * Knobs for the zoom-aware crisp view (the "high-res overlay", see HighResOverlayController).
 * Flip [ENABLED] to false to switch the whole feature off: nothing is rendered, nothing is drawn,
 * and the editor behaves exactly as it did before it existed.
 */
internal object HighResOverlayConfig {
    const val ENABLED = true

    /** The overlay is built only while the viewport zoom exceeds this. */
    const val TRIGGER_ZOOM = 1.5f

    /** Quiet time after the last gesture / stroke / edit before a render starts. */
    const val DEBOUNCE_MS = 250L

    const val MIN_SCALE = 2
    const val MAX_SCALE = 4

    /** Pixels across ALL overlays together (4096 x 4096), and the longest side of any one. */
    const val MAX_TOTAL_PIXELS = 4096L * 4096L
    const val MAX_SIDE = 4096

    /** Extra margin around the visible region, as a fraction of it, so small pans stay covered. */
    const val MARGIN_FRACTION = 0.25f

    /** Magnifications this close above a whole number do not bump the scale. */
    const val SCALE_EPSILON = 1e-3

    /** A layer scale / pixel fit factor below this is treated as degenerate. */
    const val MIN_LAYER_SCALE = 1e-4f
}

/** An integer pixel rectangle in a layer bitmap's space (right/bottom exclusive). */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** A float rectangle in a layer bitmap's space. */
internal data class FloatBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** What identifies a layer's pixels: when any part changes, an overlay built from them is stale. */
internal data class OverlayContentKey(
    val bitmapId: Int,
    val generation: Int,
    val width: Int,
    val height: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val originX: Int,
    val originY: Int,
)

/** The slice of a layer the overlay logic needs. */
internal data class OverlayLayerInput(
    val id: String,
    val key: OverlayContentKey,
    val frame: LayerFrame,
    val scale: Float,
    val offset: Offset,
    val rotationZ: Float,
    val isActive: Boolean,
)

/** Everything the overlay controller reacts to; any change restarts the debounce. */
internal data class OverlayInputs(
    val zoom: Float,
    val viewportOffset: Offset,
    val viewportRotation: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Eligible-looking layers (visible raster, no perspective tilt, no group parent). */
    val layers: List<OverlayLayerInput>,
    /** The layer a stroke is being painted on right now, if any. */
    val liveLayerId: String?,
    /** Wrap-around tiling or animation onion skins: the overlay does not model them. */
    val unsupportedMode: Boolean,
) {
    private val hasCanvas: Boolean get() = canvasWidth > 0 && canvasHeight > 0

    /** Nothing in the editor's mode or input state rules an overlay out (zoom aside). */
    val canBuild: Boolean get() = hasCanvas && !unsupportedMode && liveLayerId == null
}

/** Where and how large an overlay should be rendered. */
internal data class RegionPlan(val region: PixelRect, val scale: Int)

/** The pure decisions behind the overlay: when to build, how fine, what makes one stale. */
internal object HighResOverlayPlan {

    fun shouldBuild(inputs: OverlayInputs, enabled: Boolean = HighResOverlayConfig.ENABLED): Boolean =
        enabled && inputs.canBuild && inputs.zoom > HighResOverlayConfig.TRIGGER_ZOOM

    /** Screen pixels per layer-bitmap pixel for [layer]: how magnified the base bitmap looks. */
    fun magnification(inputs: OverlayInputs, layer: OverlayLayerInput): Float {
        val safeLayerScale = if (layer.scale > HighResOverlayConfig.MIN_LAYER_SCALE) layer.scale else 1f
        val fit = HighResOverlayRegion.fitScale(inputs.canvasWidth, inputs.canvasHeight, layer.frame)
        val bitmapPerScreen = fit / safeLayerScale
        return if (bitmapPerScreen > 0f) inputs.zoom / bitmapPerScreen else inputs.zoom
    }

    /** Integer render scale for a [magnification]: enough texels per screen pixel, within limits. */
    fun chooseScale(magnification: Float): Int =
        ceil(magnification.toDouble() - HighResOverlayConfig.SCALE_EPSILON).toInt()
            .coerceIn(HighResOverlayConfig.MIN_SCALE, HighResOverlayConfig.MAX_SCALE)

    /** The overlay pixel budget each of [layerCount] layers may use out of the shared total. */
    fun budgetPerLayer(layerCount: Int, total: Long = HighResOverlayConfig.MAX_TOTAL_PIXELS): Long =
        if (layerCount <= 0) 0L else total / layerCount

    /** An overlay built from [builtFrom] no longer matches the layer: drop it, never draw it. */
    fun isStale(builtFrom: OverlayContentKey, current: OverlayContentKey?): Boolean = builtFrom != current

    /**
     * Whether an existing overlay (null = none) should be re-rendered for [plan]: it is missing,
     * too coarse, or no longer covers the visible part (and a different region would).
     */
    fun needsRefresh(existing: RegionPlan?, plan: RegionPlan, visible: FloatBounds): Boolean = when {
        existing == null || existing.scale < plan.scale -> true
        covers(existing.region, visible) -> false
        else -> existing.region != plan.region
    }

    fun covers(region: PixelRect, visible: FloatBounds): Boolean =
        region.left <= visible.left && region.top <= visible.top &&
            region.right >= visible.right && region.bottom >= visible.bottom
}
