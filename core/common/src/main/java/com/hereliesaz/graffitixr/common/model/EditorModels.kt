// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
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
    /**
     * Row-major paint-thickness map (roadmap item 12, `ImpastoEngine`), same dimensions as
     * [bitmap]. Runtime-only like [bitmap] itself -- not yet persisted to disk or across app
     * restarts, an explicit, documented limitation rather than a silent gap. `null` means no
     * stamp-brush stroke with a positive `impastoThicknessRate` has painted on this layer yet.
     */
    @Transient
    val heightMap: FloatArray? = null,
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
    val shapes: List<VectorShape> = emptyList(),
    /**
     * Figma's components. When set, this layer is a MAIN component with this id — the definition
     * instances copy their content from. When [instanceOf] is set instead, this layer is an INSTANCE
     * of that component id. A layer is never both; see [ComponentOps] for the rules and for which
     * fields an instance inherits versus owns.
     */
    val componentId: String? = null,
    val instanceOf: String? = null,
    /**
     * Figma's constraints: how this layer reacts when its parent frame resizes. Ignored when the
     * parent declares an [autoLayout], which owns its children's placement outright — see [LayoutOps].
     */
    val constraints: Constraints = Constraints(),
    /** Auto-layout settings for this layer AS A FRAME, i.e. applied to its children. */
    val autoLayout: AutoLayout = AutoLayout(),
    /**
     * Layout extent for a layer with no shape to measure (a raster or group frame). A raster layer's
     * true pixel size lives on its bitmap, which this pure module can't see, so the size that
     * participates in layout is declared rather than guessed.
     */
    val layoutWidth: Float = 0f,
    val layoutHeight: Float = 0f,
)

/**
 * Whether Alpha Lock means anything for this layer.
 *
 * It confines paint to pixels that already carry alpha, so it needs a layer that is painted into and
 * has pixels of its own. A **group** has no bitmap — it composites its children — and a **vector**
 * layer is rendered from its shapes, so a stroke does not go into it either. Toggling it on either
 * sets a flag that no paint will ever consult.
 *
 * Declared here because three separate places offered the control and each used a different rule:
 * the layer row's menu allowed anything that was not a group, the layer window required no shapes,
 * and the quick menu asked nothing at all beyond a layer being active. So the same layer could offer
 * Alpha Lock in one place, refuse it in another, and silently accept a no-op in a third.
 */
val Layer.supportsAlphaLock: Boolean
    get() = type == LayerType.RASTER && shapes.isEmpty()

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
    val blendMode: BlendMode = BlendMode.SrcOver,
    val clipToLayerBelow: Boolean = false,
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
    val blendModeOrdinal: Int = 3, // BlendMode.SrcOver ordinal
    val opacity: Float = 1f,
    // Aligned 1:1 with `points` (one per x,y pair, not per float). Empty reads as full pressure.
    val pressures: List<Float> = emptyList(),
)

/**
 * UI panels available for interaction within the editor interface.
 *
 * No `LAYERS` and no `ADJUSTMENTS`. The layers moved into their own floating rail, so the panel that
 * constant selected was deleted along with the intent that used to select it — and `ADJUSTMENTS` was
 * never referenced anywhere at all, a second spelling of `ADJUST` that no reducer produced and no
 * screen rendered. A constant nothing can produce is a branch every reader has to rule out.
 */
enum class EditorPanel {
    NONE,
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
    val lastCropTool: String = "tool.crop",
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
    // Stroke-level opacity ceiling [0..1] for the built-in round brush (Tool.BRUSH with no azphalt
    // stamp selected) — Procreate's "Opacity". Unlike flow (below), this caps the WHOLE stroke's
    // resulting coverage regardless of self-overlap, so a translucent stroke that loops back on
    // itself doesn't paint darker where it crosses. Ignored by every other tool and by stamp brushes,
    // which use flow instead.
    val brushOpacity: Float = 1f,
    // Selected azphalt stamp-brush name, or null for the built-in round brush. When set, the brush
    // control's second axis is flow (below) rather than hardness.
    val activeBrushName: String? = null,
    // Flow [0..1] for an azphalt stamp brush: per-dab paint build-up. Ignored by the built-in brush.
    val brushFlow: Float = 1f,
    val stabilizerLevel: Int = 0,
    // Which of StrokeStabilizer's three algorithms `stabilizerLevel` drives — see
    // docs/Native Rendering Engine Design.md §6.
    val stabilizerAlgorithm: StabilizerAlgorithm = StabilizerAlgorithm.STABILIZATION,
    // Ceiling on touch samples rendered per second while drawing (0 = unthrottled). The editor
    // renders a frame per recorded sample, so this is the main lever on drawing's power draw.
    val inputSampleRateHz: Int = 60,
    // Fraction of screen resolution new layers allocate at. Every layer is a full ARGB_8888 bitmap,
    // so halving this quarters the bytes per layer — the main lever on memory.
    val canvasRenderScale: Float = 1f,
    val wrapAroundMode: Boolean = false,
    // Procreate's symmetry guide (vertical mirror): strokes are mirrored across the layer's
    // vertical centre line as they're painted.
    val symmetryMode: SymmetryMode = SymmetryMode.NONE,
    /**
     * The mode the symmetry toggle restores when it is switched back on.
     *
     * Kept because the rail has two controls for one setting — a quick on/off and a five-way picker —
     * and without this the toggle was destructive: pick Radial 6, tap the toggle to turn symmetry off
     * for a moment, tap it again, and you were on Vertical with no way to know what you had lost.
     * Never [SymmetryMode.NONE], because "restore the off state" is not a thing to restore to.
     */
    val lastSymmetryMode: SymmetryMode = SymmetryMode.VERTICAL,
    // Procreate's time-lapse: while true, EditorViewModel streams a downsampled canvas snapshot to
    // a GIF file after every committed stroke (see TimeLapseRecorder). Transient UI state, not history.
    val isTimeLapseRecording: Boolean = false,
    // Procreate's Animation Assist: each top-level layer is a frame (see AnimationFrames). Entering
    // this mode doesn't touch any layer's persisted isVisible — the canvas renderer computes a
    // per-frame opacity override instead, so turning it off leaves every layer exactly as it was.
    val isAnimationMode: Boolean = false,
    val isAnimationPlaying: Boolean = false,
    val activeFrameIndex: Int = 0,
    val onionSkinEnabled: Boolean = false,
    // Frames shown faded on each side of the active one when onion skinning is on.
    val onionSkinFrameCount: Int = 1,
    val animationFrameDurationMs: Int = 100,
    val animationLoopMode: AnimationLoopMode = AnimationLoopMode.LOOP,
    // Brush Studio's working copy. Non-null means the editor is open; the draft is applied to the
    // live brush on every edit (so the canvas previews it), and only written to disk on Save.
    val brushStudioDraft: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush? = null,
    // Set when the draft came from an existing saved brush, so Save overwrites it instead of
    // forking a near-duplicate every time it's reopened.
    val brushStudioEditingId: String? = null,
    // Vector node editing (Figma's vector pen): the id of the PATH layer whose nodes are being
    // edited, or null when not in node-edit mode. Transient UI state — the *edits* are undoable,
    // entering the mode isn't.
    val pathEditLayerId: String? = null,
    val selectedNodeIndex: Int? = null,
    // Shared styles (see StyleOps). Unlike components, which are derived from the layer stack,
    // a style has no layer to live on — these ARE the definitions, and the artwork stores only ids.
    val colorStyles: List<ColorStyle> = emptyList(),
    val textStyles: List<TextStyle> = emptyList(),
    // Procreate's selection: while set, every raster tool is confined to this region and a
    // marching-ants outline traces it. Transient UI state, not history — the *edits* made inside
    // a selection are undoable, the act of selecting isn't.
    val selection: Selection? = null,
    // How the next drag on the selection canvas is read (Procreate's selection modes). It outlives
    // the selection it makes, so picking Ellipse once keeps drawing ellipses until it's changed —
    // the mode is a setting on the tool, not a property of the region it produced.
    val selectionShape: SelectionShape = SelectionShape.FREEHAND,
    // What a completed selection drag does to the selection already there. Orthogonal to the shape
    // — any shape can be added or removed, which is how a lasso subtracted from a rectangle gets a
    // region neither could draw on its own.
    val selectionOp: SelectionOp = SelectionOp.NEW,
    // Per-channel colour distance the Automatic selection treats as "the same colour", 0..255.
    // Shared with nothing: the paint bucket keeps its own default, because reaching for the wand
    // and reaching for the bucket are different intents even where the spread is identical.
    val magicWandTolerance: Int = 32,
    // Where Tool.CLONE samples from, in screen space. Null means the tool is armed but unaimed — a
    // tap sets it, and until then a drag would have nothing to copy. Survives strokes, so a source
    // picked once serves every stroke after it until it is reset.
    val cloneSource: Offset? = null,
    // Whether Paste has anything to paste. The pixels themselves live in the ViewModel, not here —
    // a full-canvas bitmap in UiState would be compared on every recomposition for no gain, and
    // the UI only ever needs the yes/no.
    val hasClipboard: Boolean = false,
    // Named selections the user has put aside (Procreate's Save & Load). Stored as part of the
    // project, so a region worked out once survives closing the app.
    val savedSelections: List<SavedSelection> = emptyList(),
    // Which Transform mode the panel is in, and — while it is a bending one — where the handles
    // currently sit, in screen space like every other gesture-authored geometry here. Empty means
    // the grid has not been laid over the layer yet.
    // The editor canvas's current size in px. Screen-space geometry — selections, clone sources,
    // warp handles — is only meaningful against the canvas it was authored on, and until now the
    // only record of that was whatever the last brush stroke happened to set.
    val canvasSize: IntSize = IntSize.Zero,
    val transformMode: TransformMode = TransformMode.FREEFORM,
    val warpHandles: List<Offset> = emptyList(),
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
    /** Krita-style background/secondary paint colour used by gradient Source/Mix brushes. */
    val secondaryColor: Color = Color.Black,
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
