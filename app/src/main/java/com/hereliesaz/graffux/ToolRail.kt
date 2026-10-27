@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.CanvasViewport
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.theme.Amber
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import kotlin.math.roundToInt

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
)

/**
 * The left tool rail (right, for the left-handed): brush, eraser, the size pad, colour, fill, text,
 * the Vector group; then Crop & Transform, Selection and Fit; then Undo and Redo.
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
    fun tool(tool: Tool, label: String, badge: String? = null, onLongClick: (() -> Unit)? = null) {
        val entry = TOOL_CATALOG.getValue(tool)
        RailButton(
            label = label,
            light = light(entry.id),
            badge = badge,
            onLongClick = onLongClick,
            testTag = "rail.${entry.id}",
            // Tapping the tool already in hand puts it down (Procreate's rule, and the sheet's).
            onClick = { vm.setActiveTool(if (uiState.activeTool == tool) Tool.NONE else tool) },
        ) { RailGlyph(entry.icon, active = entry.id in lit) }
    }

    @Composable
    fun group(id: String, label: String, icon: Int, category: CarouselCategory, badge: String? = null) {
        RailButton(
            label = label,
            light = light(id),
            badge = badge,
            testTag = "rail.$id",
            onClick = { callbacks.onToolGroup(category) },
        ) { RailGlyph(icon, active = id in lit) }
    }

    RailPill(modifier.testTag("rail.tools")) {
        val size = uiState.brushSize.roundToInt().toString()
        tool(Tool.BRUSH, uiState.activeBrushName ?: "Brush", badge = size, onLongClick = callbacks.onBrushMenu)
        tool(Tool.ERASER, "Eraser")
        // Drag up/down for size, across for hardness; tap for the item's card.
        Box(Modifier.padding(vertical = 2.dp).size(width = RailButtonSize, height = 64.dp).testTag("rail.adj.brush")) {
            BrushSizePad(uiState, vm, callbacks.onToolOptions)
        }
        RailButton(
            label = "Colour",
            light = light("tool.color"),
            testTag = "rail.tool.color",
            onClick = { vm.onColorClicked() },
        ) { Box(Modifier.size(32.dp)) { PaletteSwatch(uiState.activeColor) } }
        tool(Tool.FILL, "Fill")
        tool(Tool.TEXT, "Text")
        group("grp.vector", "Vector", GraffuxIcons.PenInk, CarouselCategory.VECTOR)
        RailDivider()
        group("grp.transform", "Crop & Transform", GraffuxIcons.Crop, CarouselCategory.TRANSFORM)
        group(
            "grp.select", "Selection", GraffuxIcons.SelectAll, CarouselCategory.SELECTION,
            badge = uiState.savedSelections.size.takeIf { it > 0 }?.toString(),
        )
        val viewMoved = uiState.viewportZoom != 1f ||
            uiState.viewportOffset != CanvasViewport.DEFAULT_OFFSET ||
            uiState.viewportRotation != 0f
        // Only there while there is something to reset.
        if (viewMoved) {
            RailButton(label = "Fit the canvas to the screen", testTag = "rail.view.fit", onClick = { vm.resetViewport() }) {
                RailGlyph(GraffuxIcons.ZoomFit)
            }
        }
        RailDivider()
        // Always on the rail: the sheet shuts during every stroke, and undo must never go with it.
        RailButton(
            label = "Undo", enabled = uiState.undoCount > 0, testTag = "rail.history.undo",
            onClick = { vm.onUndoClicked() },
        ) { RailGlyph(GraffuxIcons.Undo) }
        RailButton(
            label = "Redo", enabled = uiState.redoCount > 0, testTag = "rail.history.redo",
            onClick = { vm.onRedoClicked() },
        ) { RailGlyph(GraffuxIcons.Redo) }
    }
}

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

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun BrushSizePad(state: EditorUiState, vm: ToolRailActions, onOpenOptions: () -> Unit) {
    // The drag reads the latest values, not the ones it started with.
    val latest by rememberUpdatedState(state)
    // The pad is otherwise drag-only — a two-axis pointer gesture with no click, no keyboard
    // path, and nothing for TalkBack to announce beyond "brush size pad". A tap grows the item's
    // carousel card (Tool Options), whose Size slider is the accessible path to the same value.
    val feather = state.brushFeathering.coerceIn(0f, 1f)
    val hardnessPercent = ((1f - feather) * 100f).roundToInt()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Size, above the preview: the number the drag's vertical axis is currently set to, read
        // at a glance without waiting for the drag to settle or opening the item's card.
        Text(
            text = "${state.brushSize.roundToInt()}px",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(onClickLabel = "Set brush size", onClick = onOpenOptions)
                // TalkBack: a slider of the size (volume keys / swipe up-down adjust it), with the
                // hardness read out alongside, so the drag isn't the only way in.
                .semantics {
                    contentDescription = "Brush size, hardness $hardnessPercent percent"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        state.brushSize,
                        MIN_BRUSH_SIZE..MAX_BRUSH_SIZE,
                    )
                    setProgress { target ->
                        vm.setBrushSize(target.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE))
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        if (drag.y != 0f) {
                            val cur = latest.brushSize
                            // Clamped to the brush's real range, NOT to this pad's rendered width. The
                            // pad used to cap the value at its own measured size, which was harmless
                            // only while a rail slider could still reach 200 px; it is the sole size
                            // control now, so that ceiling would silently become the maximum brush in
                            // the app — and a pad narrower than the current size would snap it down on
                            // the first touch. The width still bounds the *preview* below, which is all
                            // it was ever entitled to bound.
                            vm.setBrushSize((cur - drag.y * 0.5f).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE))
                        }
                        if (drag.x != 0f) {
                            // Hardness, always — never flow. The axis used to mean feathering for the
                            // round brush and stamp-brush flow for anything else, so the same gesture
                            // did two unrelated things depending on state you could not see from the
                            // pad, and the preview had to show opacity to reflect it. Flow is a stamp
                            // brush's own parameter and lives in Tool Options with the other dials.
                            val cur = latest.brushFeathering
                            vm.setBrushFeathering((cur + drag.x * 0.005f).coerceIn(0f, 1f))
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The preview is the brush's actual footprint at its actual size, so what you see while
            // dragging is what the next stroke puts down.
            //
            // **Softness, drawn as softness.** A hard brush is a disc with a hard edge; a soft one
            // fades out over its radius; and the horizontal drag moves continuously between the two,
            // which is exactly what a radial gradient expresses. This used to be a flat 30%-alpha ring
            // around a shrunken solid core — two hard edges standing in for one soft one, which reads
            // as a translucent brush rather than a soft one. The pad ended up demonstrating
            // *opacity*, the one property it does not control.
            //
            // Nothing here is ever drawn translucent. Alpha is opacity, the pad does not set opacity,
            // and a preview that dims is a preview that lies about which dial you are on.
            //
            // The swatch behind the dab now matches the document's own canvas background rather than
            // this composable's own (transparent/rail) background, so the preview's contrast reads
            // the same as painting onto the actual document would.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(color = state.canvasBackground)
                    val maxR = maxOf(minOf(size.width, size.height) / 2f, 1f)
                    val radius = (state.brushSize / 2f).coerceIn(1.5f, maxR)
                    // Where the solid core ends and the falloff begins: the whole radius when hard,
                    // the centre point when fully soft.
                    val core = (1f - feather).coerceIn(0f, 1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            // Two stops at the same offset give the hard-edged case a genuine hard
                            // edge rather than a one-pixel ramp.
                            0f to Cyan,
                            core to Cyan,
                            1f to Cyan.copy(alpha = 0f),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            }
            // Hardness, under the preview -- the number the drag's horizontal axis is
            // currently set to.
            Text(
                text = "$hardnessPercent%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** A real painter's-palette glyph: silhouette + thumb hole + separate paint wells. */
@Suppress("FunctionNaming")
@Composable
private fun PaletteSwatch(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.88f, h * 0.42f)
            cubicTo(w * 0.88f, h * 0.16f, w * 0.67f, h * 0.05f, w * 0.43f, h * 0.07f)
            cubicTo(w * 0.18f, h * 0.09f, w * 0.06f, h * 0.28f, w * 0.08f, h * 0.52f)
            cubicTo(w * 0.10f, h * 0.78f, w * 0.30f, h * 0.94f, w * 0.52f, h * 0.84f)
            cubicTo(w * 0.64f, h * 0.78f, w * 0.59f, h * 0.66f, w * 0.72f, h * 0.63f)
            cubicTo(w * 0.82f, h * 0.61f, w * 0.88f, h * 0.54f, w * 0.88f, h * 0.42f)
            close()
        }
        val thumb = Path().apply { addOval(Rect(w * 0.55f, h * 0.62f, w * 0.72f, h * 0.80f)) }
        val silhouette = Path().apply { op(body, thumb, PathOperation.Difference) }
        drawPath(silhouette, color = Color.White.copy(alpha = 0.10f))
        drawPath(silhouette, color = Color.White.copy(alpha = 0.88f), style = Stroke(width = 1.5.dp.toPx()))

        fun paintWell(center: Offset, radius: Float, paint: Color) {
            drawCircle(Color.Black.copy(alpha = 0.72f), radius = radius + 1.dp.toPx(), center = center)
            drawCircle(paint, radius = radius, center = center)
            drawCircle(
                Color.White.copy(alpha = 0.55f), radius = radius, center = center,
                style = Stroke(width = 0.7.dp.toPx()),
            )
        }

        val r = minOf(w, h) * 0.085f
        paintWell(Offset(w * 0.31f, h * 0.29f), r, Cyan)
        paintWell(Offset(w * 0.51f, h * 0.23f), r, HotPink)
        paintWell(Offset(w * 0.68f, h * 0.34f), r, Amber)
        paintWell(Offset(w * 0.34f, h * 0.56f), r * 1.35f, color)
    }
}

