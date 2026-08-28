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
    onionSkinPastCount: Int,
    onionSkinFutureCount: Int,
    loopMode: AnimationLoopMode,
    frameDurationMs: Int,
    // The playback range, already resolved to real frame numbers (see
    // EditorViewModel.resolvedPlaybackRange) rather than the raw -1-for-"last frame" state fields.
    rangeStart: Int,
    rangeEnd: Int,
    // The RAW animationRangeEnd (may be -1), separate from the resolved [rangeEnd] above. The Start
    // slider must round-trip this, not [rangeEnd] -- passing the resolved value back through
    // onSetRange on every Start drag would silently pin the end to "whatever the last frame happens
    // to be right now", destroying the -1 sentinel's whole point (tracking new frames as they're
    // added) the very first time the user touches Start without ever having touched End.
    rawRangeEnd: Int,
    currentFrameHoldCount: Int,
    isTimeLapseRecording: Boolean,
    onTogglePlayback: () -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onAddFrame: () -> Unit,
    onToggleOnionSkin: () -> Unit,
    onSetOnionSkinPastCount: (Int) -> Unit,
    onSetOnionSkinFutureCount: (Int) -> Unit,
    onSetLoopMode: (AnimationLoopMode) -> Unit,
    onSetFrameDurationMs: (Int) -> Unit,
    onSetRange: (start: Int, end: Int) -> Unit,
    onSetFrameHoldCount: (Int) -> Unit,
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

            // Krita's hold frame: this frame plays for a multiple of the base frame duration
            // instead of one tick, without needing a duplicate layer to eat the extra ticks.
            Text(
                "Hold this frame  ${currentFrameHoldCount}×",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = currentFrameHoldCount.toFloat(),
                onValueChange = { onSetFrameHoldCount(it.roundToInt()) },
                valueRange = 1f..10f,
                steps = 8,
            )

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

            // Krita's playback range: Play and Export GIF cycle only [rangeStart, rangeEnd],
            // independent of which frames exist — a subrange can be previewed or exported without
            // touching the layer stack. Frame stepping (Prev/Next/Add) always reaches every frame.
            val lastFrame = (frameCount - 1).coerceAtLeast(0)
            Text(
                "Play range  ${rangeStart + 1}–${rangeEnd + 1}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Start", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = rangeStart.toFloat(),
                        onValueChange = { onSetRange(it.roundToInt().coerceAtMost(rangeEnd), rawRangeEnd) },
                        valueRange = 0f..lastFrame.toFloat(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("End", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = rangeEnd.toFloat(),
                        onValueChange = { onSetRange(rangeStart, it.roundToInt().coerceAtLeast(rangeStart)) },
                        valueRange = 0f..lastFrame.toFloat(),
                    )
                }
            }

            // Onion skin. Krita-style asymmetric: past and future neighbours fade in
            // independently, so e.g. history can show behind a clean line with nothing ahead of it.
            // The depth sliders are only shown while it's on — a ghost-frame count means nothing
            // when no ghosts are being drawn.
            AzButton(
                text = if (onionSkinEnabled) "● Onion Skin" else "Onion Skin",
                onClick = onToggleOnionSkin,
                shape = AzButtonShape.RECTANGLE,
            )
            if (onionSkinEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Past  $onionSkinPastCount", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = onionSkinPastCount.toFloat(),
                            onValueChange = { onSetOnionSkinPastCount(it.roundToInt()) },
                            valueRange = 0f..5f,
                            steps = 4,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Future  $onionSkinFutureCount", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = onionSkinFutureCount.toFloat(),
                            onValueChange = { onSetOnionSkinFutureCount(it.roundToInt()) },
                            valueRange = 0f..5f,
                            steps = 4,
                        )
                    }
                }
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
