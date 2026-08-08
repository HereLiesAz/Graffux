// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/TextEditDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt

/** Swatch palette offered for text colour. */
private val TEXT_COLORS = listOf(
    Color.White, Color.Black, Color.Red, Color(0xFFFF9800),
    Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta,
)

/**
 * Edits a text layer's content, font, size, kerning, colour, and bold/italic/outline/drop-shadow
 * style. Every change applies live — the callbacks re-rasterize the layer through the view model.
 * Seeded from the layer's current params.
 */
@Composable
fun TextEditDialog(
    initialText: String,
    initialFontName: String,
    initialSizeDp: Float,
    initialKerningEm: Float,
    initialColorArgb: Int,
    initialBold: Boolean,
    initialItalic: Boolean,
    initialHasOutline: Boolean,
    initialHasDropShadow: Boolean,
    onTextChange: (String) -> Unit,
    onFontChange: (String) -> Unit,
    onSizeChange: (Float) -> Unit,
    // Brackets one drag of the size/kerning sliders (undo + persist) — see
    // EditorViewModel.onLayerEditStart/onLayerEditEnd. Without these, resizing text here reverted
    // on relaunch and couldn't be undone, unlike every other control in this dialog (each of which
    // is a single tap, not a drag, so it can push history and save inline).
    onSizeStart: () -> Unit,
    onSizeCommit: () -> Unit,
    onKerningChange: (Float) -> Unit,
    onKerningStart: () -> Unit,
    onKerningCommit: () -> Unit,
    onColorChange: (Int) -> Unit,
    onStyleChange: (bold: Boolean, italic: Boolean, hasOutline: Boolean, hasDropShadow: Boolean) -> Unit,
    onAlign: (AlignMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var fontName by remember { mutableStateOf(initialFontName) }
    var size by remember { mutableFloatStateOf(initialSizeDp.coerceIn(8f, 300f)) }
    var kerning by remember { mutableFloatStateOf(initialKerningEm.coerceIn(-0.2f, 1f)) }
    var colorArgb by remember { mutableIntStateOf(initialColorArgb) }
    var bold by remember { mutableStateOf(initialBold) }
    var italic by remember { mutableStateOf(initialItalic) }
    var hasOutline by remember { mutableStateOf(initialHasOutline) }
    var hasDropShadow by remember { mutableStateOf(initialHasDropShadow) }
    // True from the first onValueChange of a drag until onValueChangeFinished — see
    // LayerOptionsDialog's identical isDraggingOpacity for why a stock Slider needs this.
    var isDraggingSize by remember { mutableStateOf(false) }
    var isDraggingKerning by remember { mutableStateOf(false) }

    FloatingWindow(title = "Text", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onTextChange(it) },
                    label = { Text("Content") },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Any Google Fonts family name — GoogleFontCache resolves it through the Downloadable
                // Fonts provider, so this is a free-text field rather than a fixed picker list.
                OutlinedTextField(
                    value = fontName,
                    onValueChange = { fontName = it; onFontChange(it) },
                    label = { Text("Font") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Size ${size.roundToInt()} dp")
                Slider(
                    value = size,
                    onValueChange = {
                        if (!isDraggingSize) { isDraggingSize = true; onSizeStart() }
                        size = it
                        onSizeChange(it)
                    },
                    onValueChangeFinished = { isDraggingSize = false; onSizeCommit() },
                    valueRange = 8f..300f,
                )

                Text("Kerning ${"%.2f".format(kerning)} em")
                Slider(
                    value = kerning,
                    onValueChange = {
                        if (!isDraggingKerning) { isDraggingKerning = true; onKerningStart() }
                        kerning = it
                        onKerningChange(it)
                    },
                    onValueChangeFinished = { isDraggingKerning = false; onKerningCommit() },
                    valueRange = -0.2f..1f,
                )

                // Colour swatches.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_COLORS.forEach { swatch ->
                        val argb = swatch.toArgb()
                        val selected = argb == colorArgb
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(swatch, CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color(0xFF00E5FF) else Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { colorArgb = argb; onColorChange(argb) },
                        )
                    }
                }

                // Bold / italic / outline / drop-shadow toggles.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzButton(
                        text = if (bold) "Bold ✓" else "Bold",
                        onClick = { bold = !bold; onStyleChange(bold, italic, hasOutline, hasDropShadow) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                    AzButton(
                        text = if (italic) "Italic ✓" else "Italic",
                        onClick = { italic = !italic; onStyleChange(bold, italic, hasOutline, hasDropShadow) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzButton(
                        text = if (hasOutline) "Outline ✓" else "Outline",
                        onClick = { hasOutline = !hasOutline; onStyleChange(bold, italic, hasOutline, hasDropShadow) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                    AzButton(
                        text = if (hasDropShadow) "Shadow ✓" else "Shadow",
                        onClick = { hasDropShadow = !hasDropShadow; onStyleChange(bold, italic, hasOutline, hasDropShadow) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }

                // Layer alignment on the artboard.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzButton(text = "Left", onClick = { onAlign(AlignMode.LEFT) }, shape = AzButtonShape.RECTANGLE)
                    AzButton(text = "Center", onClick = { onAlign(AlignMode.H_CENTER) }, shape = AzButtonShape.RECTANGLE)
                    AzButton(text = "Right", onClick = { onAlign(AlignMode.RIGHT) }, shape = AzButtonShape.RECTANGLE)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzButton(text = "Top", onClick = { onAlign(AlignMode.TOP) }, shape = AzButtonShape.RECTANGLE)
                    AzButton(text = "Middle", onClick = { onAlign(AlignMode.V_CENTER) }, shape = AzButtonShape.RECTANGLE)
                    AzButton(text = "Bottom", onClick = { onAlign(AlignMode.BOTTOM) }, shape = AzButtonShape.RECTANGLE)
                }

                AzButton(text = "Done", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        }
    }
}
