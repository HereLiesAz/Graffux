@file:Suppress("FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PaletteToolContribution
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface

/**
 * Hosts the Colour sheet and, behind its button, the wet-paint mixer.
 *
 * The Colour **sheet** edits the one brush colour and has no Swap, no Apply and no colour circles: every change
 * applies live. The **mixer** is a separate surface and keeps what is its own: its Swap, which swaps the two
 * brushes' paint, and a background hand. [secondaryColor], [onSelectSecondaryColor] and [onSwapColors] are
 * only for the mixer; the sheet never shows them.
 */
@Suppress("LongParameterList")
@Composable
fun TouchColorPickerHost(
    show: Boolean,
    paletteTools: List<PaletteToolContribution>,
    currentColor: Color,
    secondaryColor: Color,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit,
    onSwapColors: () -> Unit,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
    board: PaletteBoard? = null,
    mixes: List<SavedMix> = emptyList(),
    mixActions: MixActions = MixActions(),
    recentColors: List<Color> = emptyList(),
    onCommitColor: (Color) -> Unit = {},
    onDeleteRecentColor: (Color) -> Unit = {},
) {
    val held = board ?: remember { newMixerBoard() }
    val wetTool = paletteTools.firstOrNull { it.kind == PaletteToolContribution.WET_PAINT }
    var wetMixer by remember { mutableStateOf(false) }
    if (!show) {
        wetMixer = false
        return
    }
    if (wetMixer && wetTool != null) {
        PaletteMixerDialog(
            board = held,
            currentColor = currentColor,
            secondaryColor = secondaryColor,
            onSelectColor = onSelectColor,
            onSelectSecondaryColor = onSelectSecondaryColor,
            onSwapColors = onSwapColors,
            onDismiss = { wetMixer = false },
            onChanged = onChanged,
            mixes = mixes,
            mixActions = mixActions,
        )
    } else {
        TouchColorPicker(
            currentColor = currentColor,
            onSelectColor = onSelectColor,
            onCommitColor = onCommitColor,
            recentColors = recentColors,
            onDeleteRecentColor = onDeleteRecentColor,
            onDismiss = onDismiss,
            onOpenMixer = if (wetTool != null) ({ wetMixer = true }) else null,
            mixerLabel = wetTool?.name,
        )
    }
}

/**
 * Finger-first saturation/brightness plane, hue strip, hex field and recent colours; tap or drag anywhere, no
 * tiny handles. Every move applies live through [onSelectColor]; [onCommitColor] says when a colour is settled
 * (a drag ended, a tap landed, a valid hex rested or the sheet closed on it, a recent colour was picked) so it
 * can be remembered.
 */
@Suppress("LongParameterList")
@Composable
fun TouchColorPicker(
    currentColor: Color,
    onSelectColor: (Color) -> Unit,
    onCommitColor: (Color) -> Unit,
    recentColors: List<Color>,
    onDeleteRecentColor: (Color) -> Unit,
    onDismiss: () -> Unit,
    onOpenMixer: (() -> Unit)? = null,
    mixerLabel: String? = null,
) {
    val selected = currentColor
    val updatedSelected by rememberUpdatedState(selected)
    val updatedSelect by rememberUpdatedState(onSelectColor)
    val updatedCommit by rememberUpdatedState(onCommitColor)
    // The last colour this sheet applied, so a drag's end commits what the finger last chose even before
    // the new colour has come back down as [currentColor].
    var lastApplied by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) { lastApplied = selected }
    val apply: (Color) -> Unit = {
        lastApplied = it
        updatedSelect(it)
    }

    // clearOfRails: the left tool rail and the right layers rail draw over the screen's edges, so the sheet
    // sits between them.
    ToolSurface(
        kind = SurfaceKind.PICKER,
        title = stringResource(R.string.colour_sheet_title),
        onDismiss = onDismiss,
        clearOfRails = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.colour_plane_hint), style = MaterialTheme.typography.labelMedium)
            ColourPlane(selected, { updatedSelected }, apply, { updatedCommit(lastApplied) })
            Text(stringResource(R.string.colour_hue_hint), style = MaterialTheme.typography.labelMedium)
            HueStrip(selected, { updatedSelected }, apply, { updatedCommit(lastApplied) })
            HexColourField(selected, onApply = apply, onCommit = { updatedCommit(it) })
            RecentColorGrid(
                colors = recentColors,
                selected = selected,
                onPick = { colour ->
                    // Keeps the brush's working alpha: a recent colour is remembered as its RGB.
                    val picked = colour.copy(alpha = selected.alpha)
                    apply(picked)
                    updatedCommit(picked)
                },
                onDelete = onDeleteRecentColor,
            )
            if (onOpenMixer != null) {
                GraffuxButton(
                    text = mixerLabel ?: stringResource(R.string.colour_mixer_default),
                    onClick = onOpenMixer,
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("touchColor.mixer"),
                )
            }
        }
    }
}
