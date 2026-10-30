@file:Suppress("MagicNumber", "MaxLineLength", "FunctionNaming", "LongMethod")

package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The docked properties panel's width (the spec's side panel: 320dp on the layers' side). */
internal val PropertiesPanelWidth = 320.dp

/**
 * The properties side panel (spec/design-tools.md, "Side panel"): the selected layer's kind and
 * name, then its Design section and the document's. What it can set is what the desktop layer has
 * (name, opacity, visibility); X/Y/W/H, frames and constraints need the shared `Layer` model, which
 * is Android-only (see DESKTOP.md).
 */
@Composable
internal fun PropertiesPanel(
    state: CanvasState,
    documentName: String,
    modifier: Modifier = Modifier,
) {
    val layer = state.activeLayer
    Column(
        modifier = modifier
            .width(PropertiesPanelWidth)
            .fillMaxHeight()
            .background(RailFill)
            .border(1.dp, RailBorder)
            .verticalScroll(rememberScrollState())
            // The logo menu keeps its corner over the header.
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
    ) {
        Text("RASTER LAYER", color = ShortcutInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        Text(
            layer?.name ?: "No layer",
            color = RailInk,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp, end = 52.dp),
        )
        Section("DESIGN")
        if (layer != null) {
            Field("Name") { NameField(layer.id, layer.name) { state.rename(layer.id, it) } }
            Field("Opacity") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = layer.opacity,
                        onValueChange = { state.setOpacity(layer.id, it) },
                        onValueChangeFinished = { state.endContinuousEdit() },
                        colors = SliderDefaults.colors(thumbColor = RailAccent, activeTrackColor = RailAccent, inactiveTrackColor = RailBorder),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(layer.opacity * 100).roundToInt()}%", color = RailInk, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(44.dp).padding(start = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = layer.visible,
                    onCheckedChange = { state.setVisible(layer.id, it) },
                    colors = CheckboxDefaults.colors(checkedColor = RailAccent, uncheckedColor = ShortcutInk, checkmarkColor = Color.Black),
                )
                Text("Visible", color = RailInk, fontSize = 13.sp)
            }
            Field("Size") { Value("${layer.image.width} × ${layer.image.height} px") }
        }
        Section("DOCUMENT")
        Field("File") { Value(documentName + if (state.isDirty) " •" else "") }
        Field("Layers") { Value(state.layers.size.toString()) }
        state.layers.firstOrNull()?.image?.let { Field("Canvas") { Value("${it.width} × ${it.height} px") } }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(RailBorder))
    Text(title, color = ShortcutInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = ShortcutInk, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun Value(text: String) {
    Text(text, color = RailInk, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
}

/** A layer name, applied on Enter or when focus leaves. */
@Composable
private fun NameField(id: String, name: String, onCommit: (String) -> Unit) {
    var text by remember(id, name) { mutableStateOf(name) }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = RailInk, fontSize = 14.sp),
        cursorBrush = SolidColor(RailAccent),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RailBorder, FieldShape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onFocusChanged { if (!it.isFocused && text != name) onCommit(text) }
            .onKeyEvent { if (it.key == Key.Enter) { onCommit(text); true } else false },
    )
}

private val FieldShape = RoundedCornerShape(8.dp)
