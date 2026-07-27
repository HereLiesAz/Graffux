// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Defines the functional role of a layer in the scene graph.
 */
@Serializable
enum class LayerType {
    RASTER,
    VECTOR,
    GROUP,
    FILTER,
    MASK
}

/**
 * Represents a single graphical layer in the editor with all aesthetic and spatial parameters.
 * Serializable fields cover all wire-transferable state. Bitmap is runtime-only and excluded.
 */
@Serializable
data class Layer(
    val id: String,
    val name: String,
    val type: LayerType = LayerType.RASTER,
    val parentId: String? = null,
    @Serializable(with = UriSerializer::class)
    val uri: Uri? = null,
    @Transient
    val bitmap: Bitmap? = null,
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    /**
     * Procreate's Alpha Lock: while set, painting on this layer only lands where pixels already
     * have alpha — strokes recolour existing content and never extend its silhouette.
     */
    val alphaLock: Boolean = false,
    /**
     * Procreate's Clipping Mask: while set, this layer only paints where the layer(s) below it
     * (down to the next non-clipped layer, or the stack's base) already have alpha — same idea as
     * Alpha Lock, but clipping to what's *underneath* rather than to the layer's own existing pixels.
     */
    val clipToLayerBelow: Boolean = false,
    val isSketch: Boolean = false,
    val textParams: TextLayerParams? = null,
    val isLinked: Boolean = false,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver,
    val warpMesh: List<Float> = emptyList(),
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scale: Float = 1.0f,
    val isInverted: Boolean = false,
    // Vector content. When non-empty this is a vector layer (rendered from these shapes via Canvas);
    // when empty the layer is the usual raster layer backed by [bitmap]. Defaulted for back-compat.
    val shapes: List<VectorShape> = emptyList()
)

/**
 * The subset of a layer's visual/aesthetic properties that can be changed without replacing
 * the whole layer. Used by Op.LayerPropsChange to stream property-only mutations over the wire.
 */
@Serializable
data class LayerProps(
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val isImageLocked: Boolean = false,
    val alphaLock: Boolean = false,
    val isInverted: Boolean = false,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver
)

/**
 * A completed brush stroke, coarse-grained: only emitted once the user lifts their finger.
 * Points are encoded as interleaved [x0, y0, x1, y1, ...] for compact wire transfer.
 */
@Serializable
data class BrushStroke(
    val points: List<Float> = emptyList(),
    val colorArgb: Long = 0xFFFFFFFF,
    val brushSize: Float = 50f,
    val brushFeathering: Float = 0f,
    val blendModeOrdinal: Int = 3 // BlendMode.SrcOver ordinal
)

/**
 * UI panels available for interaction within the editor interface.
 */
enum class EditorPanel {
    NONE,
    LAYERS,
    ADJUSTMENTS,
    TRANSFORM,
    COLOR,
    ADJUST,
    EXTENSIONS
}

/**
 * Transformation axes for 3D manipulation.
 */
enum class RotationAxis {
    X,
    Y,
    Z
}

/**
 * The global state for the Editor UI.
 */
data class EditorUiState(
    val projectId: String? = null,
    val layers: List<Layer> = emptyList(),
    val backgroundBitmap: Bitmap? = null,
    val activeLayerId: String? = null,
    val activePanel: EditorPanel = EditorPanel.NONE,
    // FIX: Default to NONE so transform gestures are always the baseline
    val activeTool: Tool = Tool.NONE,
    val hideUiForCapture: Boolean = false,
    val isRightHanded: Boolean = true,
    /** Mirrors the persisted Settings "Imperial units" toggle — see EditorViewModel's init collector. */
    val isImperialUnits: Boolean = false,
    /** Mirrors the persisted per-gesture action mapping — see EditorViewModel's init collector. */
    val gestureMapping: Map<GestureSlot, GestureAction> = DEFAULT_GESTURE_MAPPING,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isLoading: Boolean = false,
    val brushSize: Float = 50f,
    // Feathering [0..1]: 0 = hard edge, 1 = fully soft (blur radius = brushSize)
    val brushFeathering: Float = 0f,
    // Selected azphalt stamp-brush name, or null for the built-in round brush. When set, the brush
    // control's second axis is flow (below) rather than hardness.
    val activeBrushName: String? = null,
    // Flow [0..1] for an azphalt stamp brush: per-dab paint build-up. Ignored by the built-in brush.
    val brushFlow: Float = 1f,
    val stabilizerLevel: Int = 0,
    // Ceiling on touch samples rendered per second while drawing (0 = unthrottled). The editor
    // renders a frame per recorded sample, so this is the main lever on drawing's power draw.
    val inputSampleRateHz: Int = 60,
    // Fraction of screen resolution new layers allocate at. Every layer is a full ARGB_8888 bitmap,
    // so halving this quarters the bytes per layer — the main lever on memory.
    val canvasRenderScale: Float = 1f,
    val wrapAroundMode: Boolean = false,
    // Procreate's symmetry guide (vertical mirror): strokes are mirrored across the layer's
    // vertical centre line as they're painted.
    val symmetryEnabled: Boolean = false,
    // Procreate's freehand selection: while set, every raster tool is confined to this region and
    // a marching-ants outline traces it. Transient UI state, not history — the *edits* made inside
    // a selection are undoable, the act of selecting isn't.
    val selection: Selection? = null,
    // Long-press eyedropper: true while the finger is held sampling the canvas; [eyedropColor] is
    // the colour currently under the finger (committed to activeColor on lift) and
    // [eyedropPosition] the screen point for the loupe overlay.
    val isEyedropping: Boolean = false,
    val eyedropColor: Color? = null,
    val eyedropPosition: Offset = Offset.Zero,
    // Transient HUD confirmations, Procreate-style: a short-lived pill ("Undo", "Redo") at the top
    // of the canvas, and a brush-diameter preview circle while the size slider moves.
    // Procreate's QuickMenu: the screen point the radial menu is open at, or null when closed.
    val quickMenuAt: Offset? = null,
    val hudMessage: String? = null,
    val brushHudVisible: Boolean = false,
    val activeColor: Color = Color.White,
    val showColorPicker: Boolean = false,
    val showDiagOverlay: Boolean = false,

    // Real-time stroke rendering: the mutable bitmap being actively drawn into.
    // Non-null only while a brush stroke is in progress (non-Liquify tools).
    val liveStrokeLayerId: String? = null,
    val liveStrokeBitmap: Bitmap? = null,
    // Incremented after each stroke segment so Compose re-reads the modified pixels.
    val liveStrokeVersion: Int = 0,
    val canvasBackground: Color = Color.Black,
    // Artboard / document dimensions in pixels (the fixed design/output size).
    val documentWidth: Int = 1080,
    val documentHeight: Int = 1080,
    // One-shot: set to a freshly-created text layer's id so the UI can immediately open its
    // edit-text box. Cleared once consumed.
    val autoEditTextLayerId: String? = null,

    // Infinite-canvas camera: maps the layer "world" (the screen-sized document space each layer's
    // offset/scale is expressed in) onto the physical screen. screen = viewportOffset + world *
    // viewportZoom (top-left origin). Identity (Zero / 1f) reproduces the pre-camera behaviour.
    val viewportOffset: Offset = Offset.Zero,
    val viewportZoom: Float = 1f,
    // Camera rotation in degrees (two-finger twist rotates the whole canvas about the pinch centroid).
    val viewportRotation: Float = 0f,
    // Active snap guide lines (world-space) shown while dragging a layer: vertical lines at these x's,
    // horizontal at these y's. Empty when not snapping.
    val snapGuidesX: List<Float> = emptyList(),
    val snapGuidesY: List<Float> = emptyList(),
)
