package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.AnimationLoopMode
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt

/**
 * Animation Assist, in one window.
 *
 * Everything here used to be spread down the rail: a mode toggle in one place, a host with the
 * transport and loop modes under it, two sliders further down among the brush sliders, and the
 * time-lapse recorder several items above — each of them a rail button, none of them visibly
 * related to the others. Animation is not something you reach for mid-stroke the way you reach for
 * a brush size; it is a mode you set up and then work inside, watching frames go past. That is a
 * panel, and it is the same [FloatingWindow] the 3D viewport, the reference image and the brush
 * studio already use — draggable, collapsible, never dimming the canvas, so the artwork stays
 * visible while the transport runs.
 *
 * The frame timeline itself is deliberately absent: every top-level layer *is* a frame (see
 * `AnimationFrames`), so the layer rail already is the timeline, and drawing a second one here
 * would be a second place to select a frame that could disagree with the first.
 *
 * Time-lapse sits at the bottom because it is the other thing in this app that produces a moving
 * image — but it records your process rather than assembling frames, which is why it is below a
 * divider rather than mixed into the transport.
 */
@Composable
fun AnimationWindow(
    frameCount: Int,
    activeFrameIndex: Int,
    isPlaying: Boolean,
    onionSkinEnabled: Boolean,
    onionSkinFrameCount: Int,
    loopMode: AnimationLoopMode,
    frameDurationMs: Int,
    isTimeLapseRecording: Boolean,
    onTogglePlayback: () -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onAddFrame: () -> Unit,
    onToggleOnionSkin: () -> Unit,
    onSetOnionSkinFrameCount: (Int) -> Unit,
    onSetLoopMode: (AnimationLoopMode) -> Unit,
    onSetFrameDurationMs: (Int) -> Unit,
    onExport: () -> Unit,
    onToggleTimeLapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The read-out is clamped rather than the index: a frame count of zero would otherwise show
    // "Frame 1/0", and the count is derived from the layer stack, which can legitimately be empty.
    val shownFrame = (activeFrameIndex + 1).coerceAtMost(frameCount.coerceAtLeast(1))

    FloatingWindow(title = "Animation", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Frame $shownFrame / $frameCount", style = MaterialTheme.typography.titleSmall)

            // Transport.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AzButton(text = "Prev", onClick = onPreviousFrame, shape = AzButtonShape.RECTANGLE)
                AzButton(
                    text = if (isPlaying) "Pause" else "Play",
                    onClick = onTogglePlayback,
                    shape = AzButtonShape.RECTANGLE,
                )
                AzButton(text = "Next", onClick = onNextFrame, shape = AzButtonShape.RECTANGLE)
            }
            AzButton(text = "Add Frame", onClick = onAddFrame, shape = AzButtonShape.RECTANGLE)

            Text("Playback", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimationLoopMode.entries.forEach { mode ->
                    AzButton(
                        text = if (mode == loopMode) "● ${mode.label}" else mode.label,
                        onClick = { onSetLoopMode(mode) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
            }

            Text(
                "Frame duration  ${frameDurationMs}ms",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = frameDurationMs.toFloat(),
                onValueChange = { onSetFrameDurationMs(it.roundToInt()) },
                valueRange = 20f..500f,
            )

            // Onion skin. The depth slider is only shown while it is on — a count of ghost frames
            // means nothing when no ghosts are being drawn.
            AzButton(
                text = if (onionSkinEnabled) "● Onion Skin" else "Onion Skin",
                onClick = onToggleOnionSkin,
                shape = AzButtonShape.RECTANGLE,
            )
            if (onionSkinEnabled) {
                Text(
                    "Ghost frames  $onionSkinFrameCount",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = onionSkinFrameCount.toFloat(),
                    onValueChange = { onSetOnionSkinFrameCount(it.roundToInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                )
            }

            AzButton(text = "Export GIF", onClick = onExport, shape = AzButtonShape.RECTANGLE)

            Text("Time-lapse", style = MaterialTheme.typography.labelMedium)
            Text(
                "Records every committed stroke and saves the clip to Downloads when you stop.",
                style = MaterialTheme.typography.bodySmall,
            )
            AzButton(
                text = if (isTimeLapseRecording) "● Recording — Stop" else "Record",
                onClick = onToggleTimeLapse,
                shape = AzButtonShape.RECTANGLE,
            )
        }
    }
}
