@file:Suppress("MagicNumber", "FunctionNaming", "LongParameterList", "LongMethod", "MatchingDeclarationName", "MaxLineLength", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.isFrame
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

/*
 * The design tools' properties (the mockup's "Design tools" row). The rail's Frame circle puts the
 * Frame tool in hand; the properties of the selected layer then show either in the bottom sheet,
 * one tab at a time above the design tab list (phones, and tablets set to "Bottom sheet"), or all at
 * once in a 320dp side panel (tablets and desktop by default). Both draw the same sections.
 */

/** The design sheet's tabs, in the mockup's order, under the type heading each sits under. */
internal enum class DesignTab(val label: String, val section: String) {
    FRAME("Frame", "LAYOUT"),
    ;

    val key: String get() = "design.${name.lowercase()}"

    companion object {
        fun ofKey(key: String?): DesignTab? = entries.firstOrNull { it.key == key }
    }
}

/** The tabs this build offers. */
internal val DESIGN_TABS: List<DesignTab> = DesignTab.entries

/** The design tab list's sections, drawn by [CarouselTabList] as it draws the brush packs. */
internal fun designTabSections(tabs: List<DesignTab> = DESIGN_TABS): List<CarouselTabSection> =
    tabs.groupBy { it.section }.map { (section, inSection) ->
        CarouselTabSection(section, inSection.map { CarouselTabGroup(it.key, it.label, CarouselCategory.BRUSHES, emptyList()) })
    }

/** The selected layer, as the properties read it. Null fields show a dash and are not editable. */
internal data class DesignTarget(
    val id: String,
    val name: String,
    /** FRAME, VECTOR, TEXT, LAYER or GROUP: the panel header's kind line. */
    val kind: String,
    val box: Rect?,
    val rotation: Float,
    val opacityPercent: Float,
    /** A frame's or a rectangle's corner radius; null for a layer that has none. */
    val radius: Float?,
    val isFrame: Boolean,
    val clipsContent: Boolean,
    val fillArgb: Long?,
)

/** [layer] as the properties panel shows it, its box measured by [box]. */
internal fun designTargetOf(layer: Layer, box: Rect?): DesignTarget {
    val rect = layer.shapes.firstOrNull { it.kind == ShapeKind.RECTANGLE }
    return DesignTarget(
        id = layer.id,
        name = layer.name,
        kind = when {
            layer.isFrame -> "FRAME"
            layer.textParams != null -> "TEXT"
            layer.shapes.isNotEmpty() -> "VECTOR"
            layer.type == LayerType.GROUP -> "GROUP"
            else -> "LAYER"
        },
        box = box,
        rotation = layer.rotationZ,
        opacityPercent = layer.opacity * 100f,
        radius = when {
            layer.isFrame -> layer.frameRadius
            rect != null -> rect.cornerRadius
            else -> null
        },
        isFrame = layer.isFrame,
        clipsContent = layer.clipsContent,
        fillArgb = layer.frameFillArgb,
    )
}

/** The selected layer of [state] as a [DesignTarget], or null with nothing selected. */
internal fun designTargetIn(state: EditorUiState, box: (String) -> Rect?): DesignTarget? =
    state.layers.firstOrNull { it.id == state.activeLayerId }?.let { designTargetOf(it, box(it.id)) }

/** The editor calls the design properties make, as an interface tests can fake. */
internal interface DesignActions {
    fun setBox(x: Float, y: Float, width: Float, height: Float)
    fun setRotation(degrees: Float)
    fun setOpacityPercent(percent: Float)
    fun setRadius(radius: Float)
    fun setClip(clips: Boolean)
    fun setFill(argb: Long?)
    fun fillWithColorInHand()
    fun addFrame()
}

internal fun EditorViewModel.designActions(): DesignActions = object : DesignActions {
    override fun setBox(x: Float, y: Float, width: Float, height: Float) = onSetLayerBox(x, y, width, height)
    override fun setRotation(degrees: Float) = onSetLayerRotation(degrees)
    override fun setOpacityPercent(percent: Float) = onSetLayerOpacityPercent(percent)
    override fun setRadius(radius: Float) = onSetCornerRadius(radius)
    override fun setClip(clips: Boolean) = onSetFrameClip(clips)
    override fun setFill(argb: Long?) = onSetFrameFill(argb)
    override fun fillWithColorInHand() = onFillFrameWithActiveColor()
    override fun addFrame() = onAddFrame()
}

/** The sheet's sizes, the same as the photo sheet's (PhotoSheetStyle). */
private val SheetHeaderHeight = 44.dp
private val SheetBodyHeight = 240.dp
private val SheetBodyFade = 28.dp

/**
 * What the sheet holds with the Frame tool in hand: the open tab's controls for the selected
 * layer (a header with its name, then a 240dp body that scrolls) above the design tab list.
 * Swiped down to its first stage, only the tab list.
 */
@Composable
internal fun DesignSheetBody(
    tab: DesignTab?,
    showingControls: Boolean,
    target: DesignTarget?,
    actions: DesignActions,
    onSelectTab: (DesignTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showingControls && tab != null) {
            Column(Modifier.widthIn(max = 390.dp).fillMaxWidth().testTag("design.panel.${tab.key}")) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(SheetHeaderHeight).padding(start = 20.dp, end = 12.dp),
                ) {
                    Text(
                        target?.name ?: "Frames",
                        color = DesignInk.Ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = RobotoCondensedFamily,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).testTag("design.title"),
                    )
                    if (target == null) DesignChip("Add frame", on = false, tag = "design.addFrame", onClick = actions::addFrame)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(SheetBodyHeight).fadeBottom()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (target == null) {
                            DesignEmpty()
                        } else {
                            when (tab) {
                                DesignTab.FRAME -> FrameSection(target, actions, DesignDensity.SHEET)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        CarouselTabList(designTabSections(), tab?.key, onSelect = { group -> DesignTab.ofKey(group.key)?.let(onSelectTab) })
    }
}

@Composable
private fun DesignEmpty() {
    Text(
        "Drag on the canvas to draw a frame, or tap a layer to see its properties.",
        color = DesignInk.Muted,
        fontSize = 14.sp,
        fontFamily = RobotoCondensedFamily,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("design.empty"),
    )
}

/** The body's last 28dp fade out, as the mockup's mask does. */
private fun Modifier.fadeBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = SheetBodyFade.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
            size = androidx.compose.ui.geometry.Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * The Frame section: position and size, rotation, corner radius and opacity, then a frame's own
 * clip and fill. In the sheet (the mockup's Properties board) the fields run four to a row; in the
 * side panel (its Tablet · properties side panel board) two to a row, under a "Frame" heading.
 */
@Composable
internal fun FrameSection(target: DesignTarget, actions: DesignActions, density: DesignDensity) {
    val box = target.box
    fun setX(v: Float) = box?.let { actions.setBox(v, it.y, it.width, it.height) }
    fun setY(v: Float) = box?.let { actions.setBox(it.x, v, it.width, it.height) }
    fun setW(v: Float) = box?.let { actions.setBox(it.x, it.y, v, it.height) }
    fun setH(v: Float) = box?.let { actions.setBox(it.x, it.y, it.width, v) }
    if (density == DesignDensity.SHEET) {
        Column {
            FieldRow {
                DesignNumberField("X", box?.x, density, "design.x", { setX(it) }, Modifier.weight(1f))
                DesignNumberField("Y", box?.y, density, "design.y", { setY(it) }, Modifier.weight(1f))
                DesignNumberField("W", box?.width, density, "design.w", { setW(it) }, Modifier.weight(1f))
                DesignNumberField("H", box?.height, density, "design.h", { setH(it) }, Modifier.weight(1f))
            }
            FieldRow {
                DesignNumberField("°", target.rotation, density, "design.rotation", actions::setRotation, Modifier.weight(1f))
                DesignNumberField("R", target.radius, density, "design.radius", actions::setRadius, Modifier.weight(1f))
                DesignNumberField("%", target.opacityPercent, density, "design.opacity", actions::setOpacityPercent, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            if (target.isFrame) {
                DesignRow("Clip", density) {
                    DesignChoice(listOf(true to "Clip", false to "Show all"), target.clipsContent, "design.clip", actions::setClip)
                }
                DesignRow("Fill", density) { FillControls(target, actions, density) }
            }
        }
    } else {
        DesignPanelSection("design.section.frame") {
            DesignSectionHeader("Frame")
            PanelGrid(
                listOf(
                    Triple("X", box?.x) { v: Float -> setX(v) },
                    Triple("Y", box?.y) { v: Float -> setY(v) },
                    Triple("W", box?.width) { v: Float -> setW(v) },
                    Triple("H", box?.height) { v: Float -> setH(v) },
                    Triple("°", target.rotation, actions::setRotation),
                    Triple("R", target.radius, actions::setRadius),
                ),
            )
            if (target.isFrame) {
                DesignRow("Clip", density) {
                    DesignChoice(listOf(true to "Clip", false to "Show all"), target.clipsContent, "design.clip", actions::setClip)
                }
            }
        }
        if (target.isFrame) {
            DesignPanelSection("design.section.fill") {
                DesignSectionHeader("Fill", action = "+", actionLabel = "Fill with the colour in hand", onAction = actions::fillWithColorInHand)
                FillControls(target, actions, density)
            }
        }
    }
}

@Composable
private fun FieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
        content = content,
    )
}

@Composable
private fun PanelGrid(fields: List<Triple<String, Float?, (Float) -> Unit>>) {
    fields.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            pair.forEach { (label, value, onCommit) ->
                val tag = when (label) {
                    "°" -> "design.rotation"
                    "R" -> "design.radius"
                    else -> "design.${label.lowercase()}"
                }
                DesignNumberField(label, value, DesignDensity.PANEL, tag, onCommit, Modifier.weight(1f))
            }
        }
    }
}

/** A frame's fill: its swatch and hex, the colour in hand, or none. */
@Composable
private fun FillControls(target: DesignTarget, actions: DesignActions, density: DesignDensity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DesignSwatch(target.fillArgb, if (density == DesignDensity.SHEET) 28.dp else 20.dp)
        Text(
            target.fillArgb?.let(::designHex) ?: "None",
            color = DesignInk.Ink,
            fontSize = density.fontSize.sp,
            fontFamily = RobotoCondensedFamily,
            modifier = Modifier.widthIn(min = 64.dp).testTag("design.fill.value"),
        )
        if (density == DesignDensity.SHEET) {
            DesignChip("Colour", on = false, tag = "design.fill.color", onClick = actions::fillWithColorInHand)
        }
        if (target.fillArgb != null) DesignChip("None", on = false, tag = "design.fill.none", onClick = { actions.setFill(null) })
    }
}

/** Where the properties show on a tablet or desktop (a phone always uses the sheet). */
internal enum class PropertiesPlacement(val key: String, val label: String) {
    SIDE_PANEL("side_panel", "Side panel"),
    BOTTOM_SHEET("bottom_sheet", "Bottom sheet"),
    ;

    companion object {
        fun ofKey(key: String?): PropertiesPlacement? = entries.firstOrNull { it.key == key }

        /**
         * The placement in effect: phones keep the bottom sheet whatever is stored; a tablet or
         * desktop (smallest width 600dp and up) takes the stored choice, the side panel by default.
         */
        fun resolve(stored: String?, smallestWidthDp: Int): PropertiesPlacement =
            if (smallestWidthDp < TABLET_MIN_WIDTH_DP) BOTTOM_SHEET else ofKey(stored) ?: SIDE_PANEL

        const val TABLET_MIN_WIDTH_DP = 600
    }
}

/** The side panel's width. */
internal val PropertiesPanelWidth = 320.dp

/**
 * The properties side panel (the mockup's "Tablet · properties side panel" board): the selected
 * layer's kind and name, the Design tab, every section stacked and scrolling, and at its foot the
 * "Show properties in" setting. The logo menu keeps its corner, over the panel's header, which
 * leaves it room ([logoClearance]).
 */
@Composable
internal fun PropertiesPanel(
    target: DesignTarget?,
    actions: DesignActions,
    placement: PropertiesPlacement,
    onPlacement: (PropertiesPlacement) -> Unit,
    modifier: Modifier = Modifier,
    logoClearance: androidx.compose.ui.unit.Dp = 64.dp,
) {
    Column(
        modifier
            .width(PropertiesPanelWidth)
            .background(DesignInk.Panel)
            .drawStartRule()
            .testTag("design.sidePanel"),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().height(68.dp).padding(start = 16.dp, end = logoClearance),
        ) {
            Text(
                target?.kind ?: "DESIGN",
                color = DesignInk.Faint,
                fontSize = 11.sp,
                letterSpacing = 0.88.sp,
                fontFamily = com.hereliesaz.graffitixr.design.theme.RobotoMonoFamily,
            )
            Text(
                target?.name ?: "Nothing selected",
                color = DesignInk.Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = RobotoCondensedFamily,
                maxLines = 1,
                modifier = Modifier.testTag("design.sidePanel.name"),
            )
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesignChip("Design", on = true, tag = "design.sidePanel.tab.design", onClick = {}, height = 36.dp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (target == null) {
                DesignPanelSection("design.section.empty") {
                    DesignSectionHeader("Frames", action = "+", actionLabel = "Add a frame", onAction = actions::addFrame)
                    Text(
                        "Drag on the canvas to draw a frame, or tap a layer to see its properties.",
                        color = DesignInk.Muted,
                        fontSize = 13.sp,
                        fontFamily = RobotoCondensedFamily,
                    )
                }
            } else {
                FrameSection(target, actions, DesignDensity.PANEL)
            }
        }
        PlacementSetting(placement, onPlacement)
    }
}

/** "Show properties in: Side panel / Bottom sheet", the panel's foot (and a Settings row). */
@Composable
internal fun PlacementSetting(placement: PropertiesPlacement, onPlacement: (PropertiesPlacement) -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().drawTopRuleFull().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp).testTag("design.placement"),
    ) {
        Text("Show properties in", color = DesignInk.Faint, fontSize = 12.sp, fontFamily = RobotoCondensedFamily, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        PropertiesPlacement.entries.forEach { p ->
            DesignChip(p.label, on = p == placement, tag = "design.placement.${p.key}", onClick = { onPlacement(p) })
        }
    }
}

private fun Modifier.drawStartRule(): Modifier = drawBehind {
    drawLine(DesignInk.Line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
}

private fun Modifier.drawTopRuleFull(): Modifier = drawBehind {
    drawLine(DesignInk.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
}
