package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.model.AnimationLoopMode
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerProps
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.TextLayerParams
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * State-changing user intents for the editor — the "Intent" of MVI. Each is handled by the pure
 * [EditorReducer] to produce the next [com.hereliesaz.graffitixr.common.model.EditorUiState].
 *
 * These cover the state-only transitions. Side effects an intent may also require (history
 * snapshot, persistence, co-op op emission, OpenCV rasterization) are orchestrated by
 * EditorViewModel around the dispatch — they are intentionally not part of the intent or reducer.
 */
internal sealed interface EditorIntent {
    // ── Active-layer visual properties ────────────────────────────────────────
    data class SetOpacity(val value: Float) : EditorIntent
    data class SetBrightness(val value: Float) : EditorIntent
    data class SetContrast(val value: Float) : EditorIntent
    data class SetSaturation(val value: Float) : EditorIntent
    data class SetColorBalanceR(val value: Float) : EditorIntent
    data class SetColorBalanceG(val value: Float) : EditorIntent
    data class SetColorBalanceB(val value: Float) : EditorIntent
    data class SetScale(val value: Float) : EditorIntent
    /** Pan is incremental: [delta] is ADDED to the active layer's current offset. */
    data class AddOffset(val delta: Offset) : EditorIntent
    data class SetRotationX(val value: Float) : EditorIntent
    data class SetRotationY(val value: Float) : EditorIntent
    data class SetRotationZ(val value: Float) : EditorIntent
    data class SetLayerTransform(val scale: Float, val offset: Offset, val rx: Float, val ry: Float, val rz: Float) : EditorIntent
    data object ToggleInvert : EditorIntent
    data object ToggleImageLock : EditorIntent
    /** Toggles Procreate-style Alpha Lock on layer [id] (paint only lands on existing alpha). */
    data class ToggleAlphaLock(val id: String) : EditorIntent
    data object CycleRotationAxis : EditorIntent

    // ── Layer list ────────────────────────────────────────────────────────────
    data class ReorderLayers(val order: List<String>) : EditorIntent
    data class RenameLayer(val id: String, val name: String) : EditorIntent
    data class ToggleVisibility(val id: String) : EditorIntent
    data class ActivateLayer(val id: String) : EditorIntent

    /** Appends [layer], makes it active, and clears the tool. [resetActivePanel] mirrors the
     *  two call patterns: adds dismiss the panel, duplicate leaves it as-is. [activeToolOverride]
     *  keeps that tool active instead of clearing it — used when the layer exists only because a
     *  tool needed a canvas to paint on (see EditorViewModel.setActiveTool), so picking a brush on
     *  an empty project activates it immediately instead of snapping back to none. */
    data class AddLayer(val layer: Layer, val resetActivePanel: Boolean = true, val activeToolOverride: Tool? = null) : EditorIntent
    /** Removes [id]; if it was active, activates the first remaining layer. Clears the tool. */
    data class RemoveLayer(val id: String) : EditorIntent
    /** Replaces the whole layer set (e.g. flatten) with [layers], activating [activeId]. */
    data class ReplaceLayers(val layers: List<Layer>, val activeId: String?) : EditorIntent

    // ── Tool / panel / gesture ────────────────────────────────────────────────
    data class SetActiveTool(val tool: Tool) : EditorIntent
    data object ToggleAdjustPanel : EditorIntent
    data object ToggleTransformPanel : EditorIntent
    data object DismissPanel : EditorIntent
    data class SetGestureInProgress(val inProgress: Boolean) : EditorIntent

    // ── Effect-result / transient flags (dispatched by the VM around async work) ───
    data class SetLoading(val loading: Boolean) : EditorIntent
    data class SetBackgroundBitmap(val bitmap: Bitmap?) : EditorIntent

    // ── Settings / tool / brush / color ───────────────────────────────────────
    data class SetCanvasBackground(val color: Color) : EditorIntent
    /** Sets the artboard / document pixel dimensions. */
    data class SetDocumentSize(val width: Int, val height: Int) : EditorIntent
    /** Sets the infinite-canvas camera (pan offset + zoom). */
    data class SetViewport(val offset: Offset, val zoom: Float, val rotation: Float = 0f) : EditorIntent
    /** Sets the active snap guide lines (world-space) shown while dragging; empty clears them. */
    data class SetSnapGuides(val x: List<Float>, val y: List<Float>) : EditorIntent
    data object ToggleHandedness : EditorIntent
    /** Mirrors the persisted Settings "Right-handed" toggle into live UiState — see EditorViewModel's
     *  init collector. Distinct from [ToggleHandedness] (an in-session flip with no [value] to pass). */
    data class SetHandedness(val value: Boolean) : EditorIntent
    /** Mirrors the persisted Settings "Imperial units" toggle into live UiState. */
    data class SetImperialUnits(val value: Boolean) : EditorIntent
    /** Mirrors the persisted per-gesture action mapping into live UiState. */
    data class SetGestureMapping(val mapping: Map<GestureSlot, GestureAction>) : EditorIntent
    data object ToggleDiagOverlay : EditorIntent
    data object FeedbackShown : EditorIntent
    data class SetBrushSize(val value: Float) : EditorIntent
    data class SetBrushFeathering(val value: Float) : EditorIntent
    data class SetBrushOpacity(val value: Float) : EditorIntent
    data class SetBrushFlow(val value: Float) : EditorIntent
    data class SetStabilizerLevel(val level: Int) : EditorIntent
    data class SetStabilizerAlgorithm(val algorithm: com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm) : EditorIntent
    /** Ceiling on rendered touch samples per second while drawing; 0 is unthrottled. */
    data class SetInputSampleRateHz(val hz: Int) : EditorIntent
    /** Fraction of screen resolution new layers allocate at. */
    data class SetCanvasRenderScale(val scale: Float) : EditorIntent
    data object ToggleWrapAroundMode : EditorIntent
    /** Toggles the vertical-mirror symmetry guide for painting. */
    /** Flips between [SymmetryMode.NONE] and [SymmetryMode.VERTICAL] — the rail toggle's simple on/off. */
    data object ToggleSymmetry : EditorIntent
    /** Directly selects any symmetry mode (including NONE) — the mode picker's each-option action. */
    data class SetSymmetryMode(val mode: SymmetryMode) : EditorIntent
    /** Flips time-lapse recording on/off; the actual GIF encoder lives in EditorViewModel. */
    data object ToggleTimeLapseRecording : EditorIntent
    /** Enters/exits Animation Assist — see AnimationFrames for how frames map onto layers. */
    data object ToggleAnimationMode : EditorIntent
    data class SetAnimationPlaying(val playing: Boolean) : EditorIntent
    /**
     * Moves the animation frame cursor. [followActiveLayer] also points the active layer at that
     * frame so the next stroke lands on the frame being looked at — playback passes false, since
     * retargeting the active layer dozens of times a second would clobber the user's selection.
     */
    data class SetActiveFrameIndex(val index: Int, val followActiveLayer: Boolean = false) : EditorIntent
    data object ToggleOnionSkin : EditorIntent
    data class SetOnionSkinFrameCount(val count: Int) : EditorIntent
    data class SetAnimationFrameDurationMs(val ms: Int) : EditorIntent
    data class SetAnimationLoopMode(val mode: AnimationLoopMode) : EditorIntent
    /**
     * Opens Brush Studio on [draft] (null closes it). [editingId] is the saved-brush id being
     * edited, or null for a brand-new brush.
     */
    data class SetBrushStudioDraft(
        val draft: com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush?,
        val editingId: String? = null,
    ) : EditorIntent
    /** Promotes a layer to a main component (Figma's "create component"). */
    data class MakeComponent(val layerId: String, val componentId: String) : EditorIntent
    /** Adds a new instance layer of a component to the top of the stack. */
    data class PlaceInstance(val instance: Layer) : EditorIntent
    /** Breaks an instance's link to its main, keeping the content it currently shows. */
    data class DetachInstance(val instanceId: String) : EditorIntent
    /** Removes a component definition, detaching its instances so none are left dangling. */
    data class ReleaseComponent(val componentId: String) : EditorIntent
    /**
     * Re-propagates every main component's content onto its instances. Fired after edits that could
     * have touched a main — running it unconditionally is cheaper and safer than trying to work out
     * which edits qualify, and it's idempotent.
     */
    data object SyncComponents : EditorIntent
    // ── Layout (constraints + auto-layout) ───────────────────────────────────────────────────
    data class SetLayerConstraints(
        val layerId: String,
        val constraints: com.hereliesaz.graffitixr.common.model.Constraints,
    ) : EditorIntent
    data class SetAutoLayout(
        val frameId: String,
        val layout: com.hereliesaz.graffitixr.common.model.AutoLayout,
    ) : EditorIntent
    /** Re-runs a frame's auto-layout over its children. */
    data class RelayoutFrame(val frameId: String) : EditorIntent

    // ── Shared styles ────────────────────────────────────────────────────────────────────────
    data class AddColorStyle(val style: com.hereliesaz.graffitixr.common.model.ColorStyle) : EditorIntent
    data class UpdateColorStyle(val style: com.hereliesaz.graffitixr.common.model.ColorStyle) : EditorIntent
    data class DeleteColorStyle(val styleId: String) : EditorIntent
    data class AddTextStyle(val style: com.hereliesaz.graffitixr.common.model.TextStyle) : EditorIntent
    data class UpdateTextStyle(val style: com.hereliesaz.graffitixr.common.model.TextStyle) : EditorIntent
    data class DeleteTextStyle(val styleId: String) : EditorIntent
    /** Links (or with null, unlinks) a layer's shape fill / stroke / text to a token. */
    data class SetShapeFillStyle(val layerId: String, val styleId: String?) : EditorIntent
    data class SetShapeStrokeStyle(val layerId: String, val styleId: String?) : EditorIntent
    data class SetLayerTextStyle(val layerId: String, val styleId: String?) : EditorIntent

    /** Enters node-edit mode on a PATH layer, or leaves it with null. */
    data class SetPathEditLayer(val layerId: String?) : EditorIntent
    data class SelectPathNode(val index: Int?) : EditorIntent
    /** Replaces the edited layer's single path shape — the result of any node operation. */
    data class SetPathShape(val layerId: String, val shape: com.hereliesaz.graffitixr.common.model.VectorShape) : EditorIntent
    /** Opens the radial QuickMenu at a screen point, or closes it with null. */
    data class SetQuickMenu(val at: Offset?) : EditorIntent
    /** Replaces the selection, or clears it with null (deselect). */
    data class SetSelection(val selection: com.hereliesaz.graffitixr.common.model.Selection?) : EditorIntent
    /** Picks how the next selection drag is read: freehand, rectangle or ellipse. */
    data class SetSelectionShape(val shape: com.hereliesaz.graffitixr.common.model.SelectionShape) : EditorIntent
    /** Picks what the next selection drag does to the current selection: replace, add or remove. */
    data class SetSelectionOp(val op: com.hereliesaz.graffitixr.common.model.SelectionOp) : EditorIntent
    /** Per-channel colour distance the Automatic selection treats as the same colour, 0..255. */
    data class SetMagicWandTolerance(val tolerance: Int) : EditorIntent
    /** Softens the current selection's edge by a radius in screen px; 0 is a hard edge. */
    data class SetSelectionFeather(val featherPx: Float) : EditorIntent
    /** Aims the Clone tool at a screen point, or unaims it with null. */
    data class SetCloneSource(val at: Offset?) : EditorIntent
    /** The editor canvas's measured size. */
    data class SetCanvasSize(val size: IntSize) : EditorIntent
    /** Picks the Transform panel's mode: freeform, distort or warp. */
    data class SetTransformMode(
        val mode: com.hereliesaz.graffitixr.common.model.TransformMode,
    ) : EditorIntent
    /** Replaces the live warp handle grid (screen space). */
    data class SetWarpHandles(val handles: List<Offset>) : EditorIntent
    /** Records whether the selection clipboard holds anything. */
    data class SetHasClipboard(val has: Boolean) : EditorIntent
    /** Replaces the list of saved (named) selections. */
    data class SetSavedSelections(
        val selections: List<com.hereliesaz.graffitixr.common.model.SavedSelection>,
    ) : EditorIntent
    /** Flips the active selection inside-out; a no-op when nothing is selected. */
    data object InvertSelection : EditorIntent
    /** Live eyedropper state: sampling in progress, current colour + loupe position. */
    data class SetEyedrop(val active: Boolean, val color: Color? = null, val position: Offset = Offset.Zero) : EditorIntent
    /** Selects an azphalt stamp brush by name, or clears back to the built-in round brush (null). */
    data class SetActiveBrush(val name: String?) : EditorIntent
    data object ShowColorPicker : EditorIntent
    data object DismissColorPicker : EditorIntent
    /** Sets the active foreground brush color. */
    data class SetActiveColor(val color: Color) : EditorIntent
    /** Sets the background/secondary brush color used by gradient Source/Mix brushes. */
    data class SetSecondaryColor(val color: Color) : EditorIntent
    data object SwapBrushColors : EditorIntent
    /** Sets the active layer's compositing / blend mode (from the blend-mode picker). */
    data class SetBlendMode(val mode: com.hereliesaz.graffitixr.common.model.BlendMode) : EditorIntent
    /** Replaces the vector shapes on [layerId] (recolour, resize, edit). */
    data class SetLayerShapes(val layerId: String, val shapes: List<com.hereliesaz.graffitixr.common.model.VectorShape>) : EditorIntent
    /** Applies a freshly-rasterized text bitmap and its params to [layerId]. */
    data class RenderTextLayer(val layerId: String, val bitmap: Bitmap, val params: TextLayerParams) : EditorIntent

    // ── Spectator / remote-op application (by id; no active-layer side effects) ────
    data class AppendLayer(val layer: Layer) : EditorIntent
    data class RemoveLayerById(val id: String) : EditorIntent
    data class SetLayerTransformById(val id: String, val scale: Float, val offset: Offset, val rx: Float, val ry: Float, val rz: Float) : EditorIntent
    data class SetLayerProps(val id: String, val props: LayerProps) : EditorIntent

    /** Groups [aId] and [bId] under a new group layer — see LayerListOps.group. */
    data class GroupLayers(val aId: String, val bId: String, val newGroupId: String, val groupName: String) : EditorIntent
    /** Dissolves group [groupId], reparenting its children up one level — see LayerListOps.ungroup. */
    data class UngroupLayer(val groupId: String) : EditorIntent
    /** Flips a layer's "clip to layer below" flag (Procreate's Clipping Mask). */
    data class ToggleClipToLayerBelow(val id: String) : EditorIntent

    // ── Panels / gestures / layer set / project lifecycle ─────────────────────
    data object ToggleColorPanel : EditorIntent
    /** Opens/closes the installed-extensions panel (run a code extension's filter/tool). */
    data object ToggleExtensionsPanel : EditorIntent
    /** A transform gesture begins: flags it and dismisses any open panel. */
    data object BeginGesture : EditorIntent
    /** Replaces just the layer list, leaving active id / tool untouched (undo restore, reload). */
    data class SetLayers(val layers: List<Layer>) : EditorIntent
    /** Copies a source layer's aesthetic modifications (incl. warp mesh) onto [id]. */
    data class PasteLayerModifications(val id: String, val source: Layer) : EditorIntent
    data class LoadedProject(
        val projectId: String,
        val layers: List<Layer>,
        val colorStyles: List<com.hereliesaz.graffitixr.common.model.ColorStyle> = emptyList(),
        val textStyles: List<com.hereliesaz.graffitixr.common.model.TextStyle> = emptyList(),
    ) : EditorIntent
    data object ClearProject : EditorIntent
}
