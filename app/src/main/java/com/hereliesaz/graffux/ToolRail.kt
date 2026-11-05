@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.CanvasViewport
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.surfaceDrawArmed
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.is3DIn
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.RailIcons
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.NeonGreen
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** The editor calls the tool rail makes. EditorViewModel's own methods, as an interface tests can fake. */
internal interface ToolRailActions {
    fun setActiveTool(tool: Tool)
    fun onColorClicked()
    fun resetViewport()
    fun onUndoClicked()
    fun onRedoClicked()
    fun setBrushSize(size: Float)
    fun setBrushFeathering(feathering: Float)
    /**
     * The axis slot (spec/3d-space.md): on a 3D layer it steps X -> Y -> Z, the axis a two-finger
     * twist turns about; on a 2D layer, where it reads "3D", it makes the layer 3D.
     */
    fun onAxisSlotTapped() = Unit
    /** The wire-mesh toggle: shows or hides the active group's surface mesh (view state). */
    fun onToggleSurfaceGrid() = Unit
}

/** [ToolRailActions] on the real view-model. */
internal fun EditorViewModel.toolRailActions(): ToolRailActions = object : ToolRailActions {
    override fun setActiveTool(tool: Tool) = this@toolRailActions.setActiveTool(tool)
    override fun onColorClicked() = this@toolRailActions.onColorClicked()
    override fun resetViewport() = this@toolRailActions.resetViewport()
    override fun onUndoClicked() = this@toolRailActions.onUndoClicked()
    override fun onRedoClicked() = this@toolRailActions.onRedoClicked()
    override fun setBrushSize(size: Float) = this@toolRailActions.setBrushSize(size)
    override fun setBrushFeathering(feathering: Float) = this@toolRailActions.setBrushFeathering(feathering)
    override fun onAxisSlotTapped() = this@toolRailActions.onAxisSlotTapped()
    override fun onToggleSurfaceGrid() = this@toolRailActions.onToggleSurfaceGrid()
}

/** What the tool rail asks of the screen around it. */
internal class ToolRailCallbacks(
    /** Tool Options: grow the in-hand item's card (the size pad's tap). */
    val onToolOptions: () -> Unit,
    /** A tool group's rail item: put the sheet on that group. */
    val onToolGroup: (CarouselCategory) -> Unit,
    /** The brush's long-press: Brush Studio, My Brushes, Brush Tips. */
    val onBrushMenu: () -> Unit,
    /** Adjust photo: put the sheet on the photo tabs. */
    val onAdjust: () -> Unit = {},
    /** The Brush item picked up from another tool: bring up the brush sheet. */
    val onBrush: () -> Unit = {},
    /** The Brush or Eraser item tapped while it is already in hand: open or shut the pack wheel. */
    val onPackWheelToggle: () -> Unit = {},
    /** A long-press on the Brush or Eraser item: open the pack wheel ([RailPackWheel]). */
    val onPackWheelOpen: () -> Unit = {},
    /** Where the Brush or Eraser item sits, in root coordinates: the pack wheel's anchor. */
    val onPackAnchor: (Tool, Rect) -> Unit = { _, _ -> },
)

/**
 * The left tool rail (right, for the left-handed), laid out as the approved mockup draws it: the
 * brush-size chip and the colour; then Brush with the Eraser directly below it (the pair the pack
 * wheel, [RailPackWheel], opens from), Fill, Text, Vector, Selection, Crop & Transform and
 * Retouch; then Undo and Redo. Items the mockup does not draw stay because nothing else reaches them
 * as directly: the Brush and Retouch (every drawing and retouching tool must be on the rail, not only
 * in the sheet), the Eraser, and Fit (only while the view has moved).
 *
 * Tapping the Brush or the Eraser while it is already in hand opens (or shuts) the pack wheel, and a
 * long-press on either opens it too; picking either up from another tool does not.
 *
 * The brush's long-press menu (Brush Studio, My Brushes, Brush Tips) is on the size chip.
 *
 * [lit] is [activeRailClassifiers] (plus the tool group whose sheet is open) and [secondary] is
 * [secondaryRailClassifiers]: an item is lit by its own id, so the rail and those functions read one
 * mapping and cannot disagree about which item is on.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun ToolRail(
    vm: ToolRailActions,
    uiState: EditorUiState,
    lit: Set<String>,
    secondary: Set<String>,
    callbacks: ToolRailCallbacks,
    modifier: Modifier = Modifier,
) {
    fun light(id: String) = RailLight(active = id in lit, secondary = id in secondary)

    @Composable
    fun tool(tool: Tool, label: String, glyph: @Composable (Boolean) -> Unit) {
        val entry = TOOL_CATALOG.getValue(tool)
        RailButton(
            label = label,
            light = light(entry.id),
            testTag = "rail.${entry.id}",
            modifier = if (tool in PACK_WHEEL_TOOLS) {
                Modifier.onGloballyPositioned { callbacks.onPackAnchor(tool, it.boundsInRoot()) }
            } else {
                Modifier
            },
            // Tapping the tool already in hand puts it down (Procreate's rule, and the sheet's),
            // except the Brush and the Eraser, whose second tap opens or shuts the pack wheel.
            onClick = {
                when {
                    tool in PACK_WHEEL_TOOLS && uiState.activeTool == tool -> callbacks.onPackWheelToggle()
                    tool == Tool.BRUSH -> {
                        vm.setActiveTool(Tool.BRUSH)
                        callbacks.onBrush()
                    }
                    else -> vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool)
                }
            },
            onLongClick = if (tool in PACK_WHEEL_TOOLS) {
                {
                    if (uiState.activeTool != tool) vm.setActiveTool(tool)
                    callbacks.onPackWheelOpen()
                }
            } else {
                null
            },
        ) { glyph(entry.id in lit) }
    }

    @Composable
    fun group(id: String, label: String, icon: ImageVector, category: CarouselCategory, badge: String? = null) {
        RailButton(
            label = label,
            light = light(id),
            badge = badge,
            testTag = "rail.$id",
            onClick = { callbacks.onToolGroup(category) },
        ) { RailGlyph(icon, active = id in lit) }
    }

    // Two pills: the tools scroll when the screen is short (a small phone, any phone in
    // landscape), while Undo and Redo sit in their own pill below that never scrolls away.
    Column(
        verticalArrangement = Arrangement.spacedBy(RailEdgeGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.testTag("rail.tools"),
    ) {
    RailPill(Modifier.weight(1f, fill = false)) {
        // Tap for the item's card, drag up/down for size and across for hardness, long-press for
        // the brush menu.
        BrushSizeChip(uiState, vm, callbacks.onToolOptions, callbacks.onBrushMenu)
        RailButton(
            label = "Colour",
            light = light("tool.color"),
            testTag = "rail.tool.color",
            onClick = { vm.onColorClicked() },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(uiState.activeColor, CircleShape)
                    .border(2.dp, RailInk, CircleShape),
            )
        }
        RailDivider()
        // The brush, back on the rail: the sheet is shut while drawing, and picking the brush up
        // again must not need it. Its long-press menu stays on the size chip above.
        tool(Tool.BRUSH, "Brush") { RailGlyph(TOOL_CATALOG.getValue(Tool.BRUSH).icon, active = it) }
        // Directly below the Brush: the pair the pack wheel opens from, centred between them.
        tool(Tool.ERASER, "Eraser") { RailGlyph(TOOL_CATALOG.getValue(Tool.ERASER).icon, active = it) }
        tool(Tool.FILL, "Fill") { RailGlyph(RailIcons.Fill, active = it) }
        tool(Tool.TEXT, "Text") { active ->
            Text(
                "T",
                color = if (active) MaterialTheme.colorScheme.primary else RailInk,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = RobotoCondensedFamily,
            )
        }
        group("grp.vector", "Vector", RailIcons.Vector, CarouselCategory.VECTOR)
        group(
            "grp.select", "Selection", RailIcons.Select, CarouselCategory.SELECTION,
            badge = uiState.savedSelections.size.takeIf { it > 0 }?.toString(),
        )
        group("grp.transform", "Crop & Transform", RailIcons.Transform, CarouselCategory.TRANSFORM)
        // Retouch: Heal, Clone, Blur, Sharpen, Smudge, Liquify, Dodge, Burn and Colorize, as one
        // group that puts the sheet on the Effects page (lit while one of them is in hand).
        RailButton(
            label = "Retouch",
            light = light(RETOUCH_RAIL_ID),
            testTag = "rail.$RETOUCH_RAIL_ID",
            onClick = { callbacks.onToolGroup(CarouselCategory.EFFECTS) },
        ) { RailGlyph(TOOL_CATALOG.getValue(Tool.HEAL).icon, active = RETOUCH_RAIL_ID in lit) }
        // The design tools: frames, the properties and the panels that act on them.
        ModeButton(
            label = "Frame",
            tag = "rail.tool.frame",
            icon = RailIcons.Frame,
            on = uiState.activeTool == Tool.FRAME,
            onClick = { vm.setActiveTool(if (uiState.activeTool == Tool.FRAME) Tool.NONE else Tool.FRAME) },
        )
        ModeButton(
            label = "Adjust photo",
            tag = "rail.$PHOTO_RAIL_ID",
            icon = RailIcons.Adjust,
            on = PHOTO_RAIL_ID in lit,
            onClick = callbacks.onAdjust,
        )
        // The axis slot, always there. On a 3D layer: the current rotation axis as its letter in
        // the axis's colour, so which axis a twist turns about is always on screen; a tap steps
        // X -> Y -> Z, the same state a double-tap on the canvas steps. 3D means the active layer's
        // GROUP has a surface (or it is 3D ink or a model); otherwise "3D", and a tap arms "Draw
        // surface" on the layer's group.
        val active3D = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }?.is3DIn(uiState.layers) == true
        AxisButton(uiState.activeRotationAxis.takeIf { active3D }, armed = uiState.surfaceDrawArmed) { vm.onAxisSlotTapped() }
        // The wire mesh of the active layer's group surface: a guide only, there while the layer is
        // paint on a surface (spec/3d-space.md, "Guide grid").
        if (com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(uiState.layers, uiState.activeLayerId) != null) {
            SurfaceMeshButton(on = uiState.surfaceGridVisible) { vm.onToggleSurfaceGrid() }
        }
        val viewMoved = uiState.viewportZoom != 1f ||
            uiState.viewportOffset != CanvasViewport.DEFAULT_OFFSET ||
            uiState.viewportRotation != 0f ||
            // A twist on X or Y orbits the 3D camera (spec/3d-space.md); Fit puts it back too.
            !uiState.sceneCamera.isDefault
        // Only there while there is something to reset.
        if (viewMoved) {
            RailButton(label = "Fit the canvas to the screen", testTag = "rail.view.fit", onClick = { vm.resetViewport() }) {
                RailGlyph(GraffuxIcons.ZoomFit)
            }
        }
    }
    // Always on screen: the sheet shuts during every stroke, and undo must never go with it.
    RailPill(Modifier.testTag("rail.history")) {
        RailButton(
            label = "Undo", enabled = uiState.undoCount > 0, testTag = "rail.history.undo",
            onClick = { vm.onUndoClicked() },
        ) { RailGlyph(RailIcons.Undo) }
        RailButton(
            label = "Redo", enabled = uiState.redoCount > 0, testTag = "rail.history.redo",
            onClick = { vm.onRedoClicked() },
        ) { RailGlyph(RailIcons.Redo) }
    }
    }
}

/** The rail items the pack wheel ([RailPackWheel]) opens from: they share the brush packs. */
internal val PACK_WHEEL_TOOLS: Set<Tool> = setOf(Tool.BRUSH, Tool.ERASER)

/** The axis a brush-chip drag is locked to. */
internal enum class DragAxis { VERTICAL, HORIZONTAL }

/** The axis a drag that has moved [total] so far is locked to: whichever it moved further along. */
internal fun dominantAxis(total: Offset): DragAxis =
    if (abs(total.x) > abs(total.y)) DragAxis.HORIZONTAL else DragAxis.VERTICAL

/** The Retouch group's rail id: lit while an [EFFECT_TOOLS] tool is in hand or its page is open. */
internal const val RETOUCH_RAIL_ID = "grp.effects"


/** The Adjust circle's rail id: lit while the sheet shows the photo tabs. */
internal const val PHOTO_RAIL_ID = "area.photo"

/**
 * Frame and Adjust photo, after Transform. Lit as the mockup draws them: a filled ink circle with
 * the glyph in the canvas's near-black, not the accent ring of the other tools, because each turns
 * the sheet (and, on a tablet, the side panel) into a mode of its own. With no [icon], [text] is
 * drawn as the glyph instead.
 */
@Composable
private fun ModeButton(
    label: String,
    tag: String,
    icon: ImageVector?,
    on: Boolean,
    text: String? = null,
    onClick: () -> Unit,
) {
    RailButton(
        label = label,
        testTag = tag,
        onClick = onClick,
    ) {
        if (on) Box(Modifier.fillMaxSize().background(RailInk, CircleShape))
        val tint = if (on) AdjustOnInk else RailInk
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        } else if (text != null) {
            Text(text, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val AdjustOnInk = androidx.compose.ui.graphics.Color(0xFF0E0E0F)

/** The 3D slot while "Draw surface" is armed: bright white fill, near-black label. */
internal val AXIS_ARMED_FILL = androidx.compose.ui.graphics.Color.White
internal val AXIS_ARMED_INK = androidx.compose.ui.graphics.Color(0xFF0E0E0F)

/** The axis cycler's rail id. */
internal const val AXIS_RAIL_ID = "view.axis"

/** Each rotation axis's colour, as the "Axis:" chip names them: X cyan, Y pink, Z green. */
internal fun RotationAxis.railColor() = when (this) {
    RotationAxis.X -> Cyan
    RotationAxis.Y -> HotPink
    RotationAxis.Z -> NeonGreen
}

/**
 * The axis slot: [axis] on a 3D layer, or null on a 2D one, which reads "3D". While "Draw surface"
 * is [armed] it reads "Draw" on a filled accent circle (the way the lit mode buttons are drawn) and
 * a tap cancels.
 */
@Composable
private fun AxisButton(axis: RotationAxis?, armed: Boolean = false, onClick: () -> Unit) {
    val surfaceStrings = com.hereliesaz.graffitixr.design.theme.rememberAppStrings().surface
    RailButton(
        label = when {
            armed -> surfaceStrings.slotArmedDescription
            axis != null -> "Rotation axis ${axis.name}. Tap to change"
            else -> "Make this layer 3D"
        },
        testTag = "rail.$AXIS_RAIL_ID",
        onClick = onClick,
    ) {
        if (armed) {
            // Setup is under way: the slot is bright white, filled, so the change is plain at a glance.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AXIS_ARMED_FILL, CircleShape)
                    .semantics { stateDescription = surfaceStrings.slotArmedState },
            )
            Text(surfaceStrings.slotArmed, color = AXIS_ARMED_INK, fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = RobotoCondensedFamily)
        } else {
            Text(axis?.name ?: "3D", color = axis?.railColor() ?: RailInk, fontSize = if (axis != null) 18.sp else 16.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = RobotoCondensedFamily)
        }
    }
}

/**
 * The wire-mesh toggle: a filled disc and a selected state while the active group's surface mesh
 * shows, so it reads on and off at a glance and to TalkBack.
 */
@Composable
private fun SurfaceMeshButton(on: Boolean, onClick: () -> Unit) {
    RailButton(
        label = "Surface wire mesh",
        testTag = "rail.$SURFACE_MESH_RAIL_ID",
        onClick = onClick,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    selected = on
                    stateDescription = if (on) "Shown" else "Hidden"
                },
        ) {
            if (on) Box(Modifier.fillMaxSize().background(RailInk, CircleShape))
        }
        Text("#", color = if (on) AdjustOnInk else RailInk, fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold, fontFamily = RobotoCondensedFamily)
    }
}

/** The wire-mesh toggle's rail id. */
internal const val SURFACE_MESH_RAIL_ID = "view.surfaceMesh"

/** The brush's long-press menu. */
internal fun MenuBuilder.brushMenu(
    vm: EditorViewModel,
    uiState: EditorUiState,
    customBrushes: List<com.hereliesaz.graffitixr.data.brush.CustomBrush>,
    onOpenBrushGallery: () -> Unit,
    onOpenBrushTipsManager: () -> Unit,
) {
    listItem("Brush Studio") {
        // Edit the brush in hand when it is a custom one; otherwise start a new one.
        vm.onOpenBrushStudio(customBrushes.firstOrNull { it.brush.name == uiState.activeBrushName }?.id)
    }
    listItem("My Brushes", onOpenBrushGallery)
    listItem("Brush Tips", onOpenBrushTipsManager)
}

/**
 * The brush-size chip: the size in hand, on a filled circle. Tap grows the item's carousel card
 * (Tool Options, whose Size slider is the accessible path to the same value); drag up/down sets the
 * size and across sets the hardness; long-press opens the brush menu.
 */
/**
 * The brush feathering after a horizontal drag of [dx] px on the brush chip. The chip shows
 * HARDNESS (1 - feathering), and like every slider in the app it goes up to the right, so a drag
 * right lowers the feathering.
 */
internal fun featheringAfterHardnessDrag(feathering: Float, dx: Float): Float =
    (feathering - dx * HARDNESS_PER_PX).coerceIn(0f, 1f)

private const val HARDNESS_PER_PX = 0.005f

@OptIn(ExperimentalFoundationApi::class)
@Suppress("FunctionNaming")
@Composable
private fun BrushSizeChip(state: EditorUiState, vm: ToolRailActions, onOpenOptions: () -> Unit, onBrushMenu: () -> Unit) {
    // The drag reads the latest values, not the ones it started with.
    val latest by rememberUpdatedState(state)
    val size = state.brushSize.roundToInt()
    val hardnessPercent = ((1f - state.brushFeathering.coerceIn(0f, 1f)) * 100f).roundToInt()
    // Which value this drag moves, decided once per gesture, so a drag up for size no longer drifts
    // the hardness with finger jitter (and vice versa).
    var axis by remember { mutableStateOf<DragAxis?>(null) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(RailTouchSize)
            .combinedClickable(
                onClickLabel = "Set brush size",
                onLongClickLabel = "Brush menu",
                onLongClick = onBrushMenu,
                onClick = onOpenOptions,
            )
            // TalkBack: a slider of the size (volume keys / swipe up-down adjust it), with the
            // hardness read out alongside, so the drag isn't the only way in.
            .semantics {
                contentDescription = "Brush size $size, hardness $hardnessPercent percent"
                progressBarRangeInfo = ProgressBarRangeInfo(state.brushSize, MIN_BRUSH_SIZE..MAX_BRUSH_SIZE)
                setProgress { target ->
                    vm.setBrushSize(target.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE))
                    true
                }
            }
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero; axis = null },
                    onDragEnd = { axis = null },
                    onDragCancel = { axis = null },
                ) { change, drag ->
                    change.consume()
                    total += drag
                    val locked = axis ?: dominantAxis(total).also { axis = it }
                    when (locked) {
                        // Clamped to the brush's real range, never to this chip's size.
                        DragAxis.VERTICAL -> if (drag.y != 0f) {
                            vm.setBrushSize((latest.brushSize - drag.y * 0.5f).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE))
                        }
                        // Hardness, always — never flow (a stamp brush's flow lives in Tool Options).
                        DragAxis.HORIZONTAL -> if (drag.x != 0f) {
                            vm.setBrushFeathering(featheringAfterHardnessDrag(latest.brushFeathering, drag.x))
                        }
                    }
                }
            }
            .testTag("rail.adj.brush"),
    ) {
        Box(Modifier.size(RailButtonSize).clip(CircleShape).background(RailChip))
        CappedFontScale {
            Text(
                // The chip shows whichever value the drag is moving.
                if (axis == DragAxis.HORIZONTAL) "$hardnessPercent%" else "$size",
                color = RailInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = RobotoCondensedFamily,
                maxLines = 1,
            )
        }
    }
}
