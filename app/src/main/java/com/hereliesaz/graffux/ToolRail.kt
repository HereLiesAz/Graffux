@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.RailIcons
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

/** The editor calls the tool rail makes. EditorViewModel's own methods, as an interface tests can fake. */
internal interface ToolRailActions {
    fun setActiveTool(tool: Tool)
    fun onColorClicked()
    fun resetViewport()
    fun onUndoClicked()
    fun onRedoClicked()
    fun setBrushSize(size: Float)
    fun setBrushFeathering(feathering: Float)
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
)

/**
 * The left tool rail (right, for the left-handed), laid out as the approved mockup draws it: the
 * brush-size chip and the colour; then Fill, Text, Vector, Selection and Crop & Transform; then Undo
 * and Redo. Two items the mockup does not draw stay, after Transform, because nothing else reaches
 * them as directly: the Eraser, and Fit (only while the view has moved).
 *
 * The brush itself is picked from the sheet, so it has no button here; its long-press menu (Brush
 * Studio, My Brushes, Brush Tips) is on the size chip.
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
            // Tapping the tool already in hand puts it down (Procreate's rule, and the sheet's).
            onClick = { vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool) },
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
        // Not in the mockup; kept because the rail is the eraser's only always-there button.
        tool(Tool.ERASER, "Eraser") { RailGlyph(TOOL_CATALOG.getValue(Tool.ERASER).icon, active = it) }
        val viewMoved = uiState.viewportZoom != 1f ||
            uiState.viewportOffset != CanvasViewport.DEFAULT_OFFSET ||
            uiState.viewportRotation != 0f
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

/** The axis a brush-chip drag is locked to. */
internal enum class DragAxis { VERTICAL, HORIZONTAL }

/** The axis a drag that has moved [total] so far is locked to: whichever it moved further along. */
internal fun dominantAxis(total: Offset): DragAxis =
    if (abs(total.x) > abs(total.y)) DragAxis.HORIZONTAL else DragAxis.VERTICAL

/** The Adjust circle's rail id: lit while the sheet shows the photo tabs. */
internal const val PHOTO_RAIL_ID = "area.photo"

/**
 * Frame and Adjust photo, after Transform. Lit as the mockup draws them: a filled ink circle with
 * the glyph in the canvas's near-black, not the accent ring of the other tools, because each turns
 * the sheet (and, on a tablet, the side panel) into a mode of its own.
 */
@Composable
private fun ModeButton(label: String, tag: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    RailButton(
        label = label,
        testTag = tag,
        onClick = onClick,
    ) {
        if (on) Box(Modifier.fillMaxSize().background(RailInk, CircleShape))
        androidx.compose.material3.Icon(
            icon,
            contentDescription = null,
            tint = if (on) AdjustOnInk else RailInk,
            modifier = Modifier.size(22.dp),
        )
    }
}

private val AdjustOnInk = androidx.compose.ui.graphics.Color(0xFF0E0E0F)

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
                            vm.setBrushFeathering((latest.brushFeathering + drag.x * 0.005f).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            .testTag("rail.adj.brush"),
    ) {
        Box(Modifier.size(RailButtonSize).clip(CircleShape).background(RailChip))
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
