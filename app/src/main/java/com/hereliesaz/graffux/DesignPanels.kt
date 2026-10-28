@file:Suppress("MagicNumber", "FunctionNaming", "LongParameterList", "LongMethod", "MaxLineLength", "TooManyFunctions", "CyclomaticComplexMethod")

package com.hereliesaz.graffux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.ColorStyle
import com.hereliesaz.graffitixr.common.model.ComponentOps
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.Constraints
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.TextStyle
import com.hereliesaz.graffitixr.design.RailIcons
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.design.theme.RobotoMonoFamily
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

/*
 * The constraints, styles and components panels (Phase 1 of the design tools), on the models that
 * already existed: Constraints / LayoutOps, StyleOps' colour and text tokens, and ComponentOps'
 * mains and instances. Each is a tab in the bottom sheet and a section in the side panel.
 */

/** What the constraints, styles and components panels read from the selected layer. */
internal data class DesignLinks(
    /** Null when the layer has no frame to be constrained in (or its parent lays it out itself). */
    val constraints: Constraints?,
    val hasShape: Boolean,
    val fillArgb: Long?,
    val strokeArgb: Long?,
    val strokeWidth: Float,
    val fillStyleId: String?,
    val strokeStyleId: String?,
    /** Null for anything but a text layer. */
    val textStyleId: String?,
    val isText: Boolean,
    val componentId: String?,
    val instanceOf: String?,
)

/** The document's tokens and components, for the panels' lists. */
internal data class DesignLibrary(
    val colorStyles: List<ColorStyle> = emptyList(),
    val textStyles: List<TextStyle> = emptyList(),
    val components: List<ComponentEntry> = emptyList(),
)

/** One main component: its layer, its id and how many instances it has. */
internal data class ComponentEntry(val layerId: String, val componentId: String, val name: String, val instances: Int)

internal fun designLinksOf(layer: Layer, layers: List<Layer>): DesignLinks {
    val parent = layer.parentId?.let { id -> layers.firstOrNull { it.id == id } }
    val shape = layer.shapes.firstOrNull()
    return DesignLinks(
        constraints = layer.constraints.takeIf { parent != null && parent.autoLayout.direction == LayoutDirection.NONE },
        hasShape = shape != null,
        fillArgb = shape?.fillArgb,
        strokeArgb = shape?.strokeArgb,
        strokeWidth = shape?.strokeWidth ?: 0f,
        fillStyleId = shape?.fillStyleId,
        strokeStyleId = shape?.strokeStyleId,
        textStyleId = layer.textParams?.styleId,
        isText = layer.textParams != null,
        componentId = layer.componentId,
        instanceOf = layer.instanceOf,
    )
}

internal fun designLibraryOf(state: EditorUiState): DesignLibrary = DesignLibrary(
    colorStyles = state.colorStyles,
    textStyles = state.textStyles,
    components = ComponentOps.components(state.layers).mapNotNull { main ->
        main.componentId?.let { id -> ComponentEntry(main.id, id, main.name, ComponentOps.instancesOf(state.layers, id).size) }
    },
)

/** The editor calls the constraints, styles and components panels make. */
internal interface DesignLibraryActions {
    fun setConstraints(constraints: Constraints)
    fun newColorStyle(name: String)
    fun applyFillStyle(styleId: String?)
    fun applyStrokeStyle(styleId: String?)
    fun updateColorStyle(styleId: String)
    fun deleteColorStyle(styleId: String)
    fun newTextStyle(name: String)
    fun applyTextStyle(styleId: String?)
    fun updateTextStyle(styleId: String)
    fun deleteTextStyle(styleId: String)
    fun makeComponent()
    fun placeInstance(componentId: String)
    fun detachInstance()
    fun releaseComponent()
    fun selectLayer(layerId: String)
}

internal fun EditorViewModel.designLibraryActions(): DesignLibraryActions = object : DesignLibraryActions {
    override fun setConstraints(constraints: Constraints) = onSetConstraints(constraints)
    override fun newColorStyle(name: String) = onCreateColorStyleFromActive(name)
    override fun applyFillStyle(styleId: String?) = onApplyColorStyle(styleId)
    override fun applyStrokeStyle(styleId: String?) = onApplyStrokeStyle(styleId)
    override fun updateColorStyle(styleId: String) = onUpdateColorStyleToActiveColor(styleId)
    override fun deleteColorStyle(styleId: String) = onDeleteColorStyle(styleId)
    override fun newTextStyle(name: String) = onCreateTextStyleFromActive(name)
    override fun applyTextStyle(styleId: String?) = onApplyTextStyle(styleId)
    override fun updateTextStyle(styleId: String) = onUpdateTextStyleToActive(styleId)
    override fun deleteTextStyle(styleId: String) = onDeleteTextStyle(styleId)
    override fun makeComponent() = onMakeComponent()
    override fun placeInstance(componentId: String) = onPlaceInstance(componentId)
    override fun detachInstance() = onDetachInstance()
    override fun releaseComponent() = onReleaseComponent()
    override fun selectLayer(layerId: String) = onLayerActivated(layerId)
}

/** Pin X's chips, in the mockup's order, and the anchor each sets. */
internal val PIN_X: List<Pair<ConstraintAnchor, String>> = listOf(
    ConstraintAnchor.START to "Left", ConstraintAnchor.END to "Right", ConstraintAnchor.STRETCH to "L+R",
    ConstraintAnchor.CENTER to "Center", ConstraintAnchor.SCALE to "Scale",
)

/** Pin Y's chips. */
internal val PIN_Y: List<Pair<ConstraintAnchor, String>> = listOf(
    ConstraintAnchor.START to "Top", ConstraintAnchor.END to "Bottom", ConstraintAnchor.STRETCH to "T+B",
    ConstraintAnchor.CENTER to "Center", ConstraintAnchor.SCALE to "Scale",
)

/** A short line of explanation in place of controls that do not apply. */
@Composable
private fun DesignNote(text: String, density: DesignDensity, tag: String) {
    Text(
        text,
        color = DesignInk.Muted,
        fontSize = density.fontSize.sp,
        fontFamily = RobotoCondensedFamily,
        modifier = Modifier.padding(horizontal = if (density == DesignDensity.SHEET) 20.dp else 0.dp, vertical = 8.dp).testTag(tag),
    )
}

/** Constraints: how the layer follows its frame when the frame is resized (Pin X / Pin Y). */
@Composable
internal fun ConstraintsSection(links: DesignLinks, actions: DesignLibraryActions, density: DesignDensity) {
    val body: @Composable () -> Unit = {
        val c = links.constraints
        if (c == null) {
            DesignNote("Put this layer in a frame to pin it to the frame's edges.", density, "design.constraints.none")
        } else {
            DesignRow("Pin X", density) {
                DesignChoice(PIN_X, c.horizontal, "design.pinx") { actions.setConstraints(c.copy(horizontal = it)) }
            }
            DesignRow("Pin Y", density) {
                DesignChoice(PIN_Y, c.vertical, "design.piny") { actions.setConstraints(c.copy(vertical = it)) }
            }
        }
    }
    if (density == DesignDensity.SHEET) {
        Column { body() }
    } else {
        DesignPanelSection("design.section.constraints") {
            DesignSectionHeader("Constraints")
            body()
        }
    }
}

/** One colour token as a row: swatch, name, and the actions on it. */
@Composable
private fun ColorStyleRow(style: ColorStyle, linked: Boolean, density: DesignDensity, onApply: () -> Unit, onUpdate: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (density == DesignDensity.SHEET) 44.dp else 32.dp)
            .padding(horizontal = if (density == DesignDensity.SHEET) 20.dp else 0.dp)
            .testTag("design.style.${style.name}"),
    ) {
        DesignSwatch(style.argb, if (density == DesignDensity.SHEET) 28.dp else 20.dp)
        Text(style.name, color = DesignInk.Ink, fontSize = density.fontSize.sp, fontFamily = RobotoCondensedFamily, maxLines = 1, modifier = Modifier.weight(1f))
        DesignChip(if (linked) "Linked" else "Apply", on = linked, tag = "design.style.${style.name}.apply", onClick = onApply)
        DesignChip("Update", on = false, tag = "design.style.${style.name}.update", onClick = onUpdate)
        DesignChip("×", on = false, tag = "design.style.${style.name}.delete", onClick = onDelete)
    }
}

/**
 * Fill or Stroke with colour styles: the shape's colour and the token it is bound to ("style"),
 * the document's colour tokens to bind it to (tap a bound one to detach), and + to make a token
 * from the shape's fill.
 */
@Composable
internal fun ColorStylesSection(
    stroke: Boolean,
    links: DesignLinks,
    library: DesignLibrary,
    actions: DesignLibraryActions,
    density: DesignDensity,
) {
    val title = if (stroke) "Stroke" else "Fill"
    val boundId = if (stroke) links.strokeStyleId else links.fillStyleId
    val bound = library.colorStyles.firstOrNull { it.id == boundId }
    val apply: (String?) -> Unit = if (stroke) actions::applyStrokeStyle else actions::applyFillStyle
    val newStyle = { actions.newColorStyle("Colour ${library.colorStyles.size + 1}") }
    val body: @Composable () -> Unit = {
        if (!links.hasShape) {
            DesignNote("Select a shape to give its ${title.lowercase()} a colour style.", density, "design.${title.lowercase()}.none")
        } else {
            DesignRow(title, density) {
                DesignSwatch(if (stroke) links.strokeArgb else links.fillArgb, if (density == DesignDensity.SHEET) 28.dp else 20.dp)
                Text(
                    bound?.name ?: (if (stroke) links.strokeArgb else links.fillArgb)?.let(::designHex).orEmpty() +
                        if (stroke) " · ${designNumber(links.strokeWidth)}" else "",
                    color = DesignInk.Ink,
                    fontSize = density.fontSize.sp,
                    fontFamily = RobotoCondensedFamily,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 10.dp).testTag("design.${title.lowercase()}.value"),
                )
                if (bound != null) {
                    Text("style", color = DesignInk.Faint, fontSize = 11.sp, fontFamily = RobotoMonoFamily)
                }
            }
            library.colorStyles.forEach { style ->
                val linked = style.id == boundId
                ColorStyleRow(
                    style, linked, density,
                    onApply = { apply(if (linked) null else style.id) },
                    onUpdate = { actions.updateColorStyle(style.id) },
                    onDelete = { actions.deleteColorStyle(style.id) },
                )
            }
            if (density == DesignDensity.SHEET) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    DesignChip("New style from fill", on = false, tag = "design.${title.lowercase()}.newStyle", onClick = newStyle)
                }
            }
        }
    }
    if (density == DesignDensity.SHEET) {
        Column { body() }
    } else {
        DesignPanelSection("design.section.${title.lowercase()}") {
            DesignSectionHeader(
                title, action = if (links.hasShape) "+" else null, actionLabel = "New colour style from the fill",
                onAction = if (links.hasShape) newStyle else null,
            )
            body()
        }
    }
}

/** Text styles: the text layer's token, the document's text tokens, and + to make one from it. */
@Composable
internal fun TextStylesSection(links: DesignLinks, library: DesignLibrary, actions: DesignLibraryActions, density: DesignDensity) {
    val newStyle = { actions.newTextStyle("Text ${library.textStyles.size + 1}") }
    val body: @Composable () -> Unit = {
        if (!links.isText) {
            DesignNote("Select a text layer to give it a text style.", density, "design.text.none")
        } else {
            library.textStyles.forEach { style ->
                val linked = style.id == links.textStyleId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (density == DesignDensity.SHEET) 44.dp else 32.dp)
                        .padding(horizontal = if (density == DesignDensity.SHEET) 20.dp else 0.dp)
                        .testTag("design.textStyle.${style.name}"),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(style.name, color = DesignInk.Ink, fontSize = density.fontSize.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily, maxLines = 1)
                        Text(
                            "${style.fontName} · ${designNumber(style.fontSizeDp)}" + if (style.isBold) " · Bold" else "",
                            color = DesignInk.Faint, fontSize = 11.sp, fontFamily = RobotoMonoFamily, maxLines = 1,
                        )
                    }
                    DesignChip(if (linked) "Linked" else "Apply", on = linked, tag = "design.textStyle.${style.name}.apply") {
                        actions.applyTextStyle(if (linked) null else style.id)
                    }
                    DesignChip("Update", on = false, tag = "design.textStyle.${style.name}.update") { actions.updateTextStyle(style.id) }
                    DesignChip("×", on = false, tag = "design.textStyle.${style.name}.delete") { actions.deleteTextStyle(style.id) }
                }
            }
            if (density == DesignDensity.SHEET) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    DesignChip("New text style", on = false, tag = "design.text.newStyle", onClick = newStyle)
                }
            }
        }
    }
    if (density == DesignDensity.SHEET) {
        Column { body() }
    } else {
        DesignPanelSection("design.section.text") {
            DesignSectionHeader("Text", action = if (links.isText) "+" else null, actionLabel = "New text style from the layer", onAction = if (links.isText) newStyle else null)
            body()
        }
    }
}

/**
 * Components: what the selected layer is (a main, an instance, or neither, with Make component /
 * Detach / Release), then every main component in the document with its instance count, to
 * select (Go to) or place a new instance of.
 */
@Composable
internal fun ComponentsSection(links: DesignLinks, library: DesignLibrary, actions: DesignLibraryActions, density: DesignDensity) {
    val pad = if (density == DesignDensity.SHEET) 20.dp else 0.dp
    val body: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = pad, vertical = 6.dp)) {
            when {
                links.componentId != null -> DesignChip("Release component", on = false, tag = "design.component.release", onClick = actions::releaseComponent)
                links.instanceOf != null -> {
                    val main = library.components.firstOrNull { it.componentId == links.instanceOf }
                    if (main != null) DesignChip("Go to main", on = false, tag = "design.component.main") { actions.selectLayer(main.layerId) }
                    DesignChip("Detach", on = false, tag = "design.component.detach", onClick = actions::detachInstance)
                }
                else -> DesignChip("Make component", on = false, tag = "design.component.make", onClick = actions::makeComponent)
            }
        }
        if (library.components.isEmpty()) {
            DesignNote("No components yet. Make one from a layer, then place instances of it.", density, "design.components.none")
        }
        library.components.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (density == DesignDensity.SHEET) 44.dp else 32.dp)
                    .padding(horizontal = pad)
                    .testTag("design.component.${entry.name}"),
            ) {
                Icon(RailIcons.Component, contentDescription = null, tint = DesignInk.Ink, modifier = Modifier.size(18.dp))
                Text(
                    entry.name,
                    color = DesignInk.Ink,
                    fontSize = density.fontSize.sp,
                    fontFamily = RobotoCondensedFamily,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button) { actions.selectLayer(entry.layerId) },
                )
                Text(
                    if (entry.instances == 1) "1 instance" else "${entry.instances} instances",
                    color = DesignInk.Faint, fontSize = 11.sp, fontFamily = RobotoMonoFamily,
                )
                DesignChip("Place", on = false, tag = "design.component.${entry.name}.place") { actions.placeInstance(entry.componentId) }
            }
        }
    }
    if (density == DesignDensity.SHEET) {
        Column { body() }
    } else {
        DesignPanelSection("design.section.components") {
            DesignSectionHeader("Components")
            body()
        }
    }
}

/** The Frame tab's Fill and Stroke rows: the colour (or its style) and the stroke's weight. */
@Composable
internal fun PaintSummary(links: DesignLinks, library: DesignLibrary) {
    val density = DesignDensity.SHEET
    val fillStyle = library.colorStyles.firstOrNull { it.id == links.fillStyleId }
    DesignRow("Fill", density) {
        DesignSwatch(links.fillArgb, 28.dp)
        Text(
            fillStyle?.name ?: links.fillArgb?.let(::designHex).orEmpty(),
            color = DesignInk.Ink, fontSize = 14.sp, fontFamily = RobotoCondensedFamily, maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 10.dp).testTag("design.summary.fill"),
        )
        if (fillStyle != null) Text("style", color = DesignInk.Faint, fontSize = 12.sp, fontFamily = RobotoMonoFamily)
    }
    DesignRow("Stroke", density) {
        val none = links.strokeWidth <= 0f
        Text(
            if (none) "\u2013" else "${designNumber(links.strokeWidth)} \u00b7 ${links.strokeArgb?.let(::designHex).orEmpty()}",
            color = DesignInk.Ink, fontSize = 13.sp, fontFamily = RobotoMonoFamily, maxLines = 1,
            modifier = Modifier.weight(1f).testTag("design.summary.stroke"),
        )
        if (none) Text("None", color = DesignInk.Faint, fontSize = 13.sp, fontFamily = RobotoCondensedFamily)
    }
}
