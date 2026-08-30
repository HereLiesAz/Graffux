// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BlendModePicker.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * The compositing modes offered by the picker, with human labels. This is the standard
 * Photoshop/Figma separable + non-separable set — a curated subset of the full 29-entry
 * [BlendMode] enum, since the rest (Src/Dst/Xor/etc. Porter-Duff operators) aren't meaningful
 * layer-blend choices for a designer.
 */
private val BLEND_MODE_CHOICES: List<Pair<BlendMode, String>> = listOf(
    BlendMode.SrcOver to "Normal",
    BlendMode.Multiply to "Multiply",
    BlendMode.Screen to "Screen",
    BlendMode.Overlay to "Overlay",
    BlendMode.Darken to "Darken",
    BlendMode.Lighten to "Lighten",
    BlendMode.ColorDodge to "Color Dodge",
    BlendMode.ColorBurn to "Color Burn",
    BlendMode.HardLight to "Hard Light",
    BlendMode.SoftLight to "Soft Light",
    BlendMode.Difference to "Difference",
    BlendMode.Exclusion to "Exclusion",
    BlendMode.Hue to "Hue",
    BlendMode.Saturation to "Saturation",
    BlendMode.Color to "Color",
    BlendMode.Luminosity to "Luminosity",
)

/**
 * A tap-to-pick compositing-mode dialog for the active layer. [current] is highlighted; selecting a
 * mode fires [onSelect] and the caller closes the dialog. Replaces the old cycle-through-all-29
 * interaction, which was unusable for finding a specific mode.
 */
@Composable
fun BlendModePicker(
    current: BlendMode,
    onSelect: (BlendMode) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Blend mode", onDismiss = onDismiss) {
        // Plain Column, not LazyColumn: this window's content already sits inside FloatingWindow's
        // own scrollable Column, and a lazy list nested there is built on SubcomposeLayout --
        // AzWindow's sizing asks its content for an intrinsic measurement, which Compose refuses
        // to do across a SubcomposeLayout boundary, crashing the instant this window opened. A
        // fixed 16-entry list has nothing to virtualize anyway.
        Column {
            BLEND_MODE_CHOICES.forEach { (mode, label) ->
                val selected = mode == current
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}
