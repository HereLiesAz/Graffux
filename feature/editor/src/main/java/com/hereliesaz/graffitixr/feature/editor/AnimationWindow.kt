package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.AnimationLoopMode
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps a horizontal ruler coordinate to the nearest frame. The whole ruler is the hit target; the
 * visible playhead line is intentionally not the only draggable pixel.
 */
internal fun frameAtTimelinePosition(x: Float, width: Float, frameCount: Int): Int {
    if (frameCount <= 1 || width <= 0f) return 0
    return ((x.coerceIn(0f, width) / width) * (frameCount - 1))
        .roundToInt()
        .coerceIn(0, frameCount - 1)
}

internal fun timelinePositionForFrame(frame: Int, width: Float, frameCount: Int): Float {
    if (frameCount <= 1 || width <= 0f) return 0f
    return frame.coerceIn(0, frameCount - 1).toFloat() / (frameCount - 1).toFloat() * width
}

internal fun normalizedPlaybackRange(anchor: Int, current: Int): IntRange =
    min(anchor, current)..max(anchor, current)

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
 * The compact ruler here is deliberately a transport control, not a second frame model: every
 * top-level layer still *is* a frame (see `AnimationFrames`) and the layer rail remains the
 * authoritative frame list. The ruler only scrubs that same active-frame index and edits the same
 * playback range the numeric controls use.
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
    previewIsRendering: Boolean,
    previewIsReady: Boolean,
    previewRenderedFrames: Int,
    previewTotalFrames: Int,
    previewError: String?,
    onTogglePlayback: () -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onAddFrame: () -> Unit,
    onToggleOnionSkin: () -> Unit,
    onSetOnionSkinPastCount: (Int) -> Unit,
    onSetOnionSkinFutureCount: (Int) -> Unit,
    onSetLoopMode: (AnimationLoopMode) -> Unit,
    onSetFrameDurationMs: (Int) -> Unit,
    onSetRange: (start: Int, end: Int) -> Unit,
    onRenderPreview: () -> Unit,
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

            PlaybackRuler(
                frameCount = frameCount,
                activeFrameIndex = activeFrameIndex,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                onSeekFrame = onSeekFrame,
                onSetRange = onSetRange,
            )

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

            if (frameCount > 1) {
                AzButton(
                    text = when {
                        previewIsRendering -> "Rendering preview  $previewRenderedFrames/$previewTotalFrames"
                        previewIsReady -> "Re-render Low-quality Preview"
                        else -> "Render Low-quality Preview"
                    },
                    onClick = onRenderPreview,
                    shape = AzButtonShape.RECTANGLE,
                )
                Text(
                    when {
                        previewError != null -> "Preview failed: $previewError"
                        previewIsRendering -> "Buffering the selected play range."
                        previewIsReady -> "Preview buffer ready for this play range."
                        else -> "Pre-render this range for lighter playback."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
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


/**
 * Vegas-style transport ruler:
 * - tap anywhere to place the playhead;
 * - drag from the playhead's deliberately wide invisible hit zone to scrub;
 * - drag anywhere else to define the playback range, in either direction.
 */
@Composable
private fun PlaybackRuler(
    frameCount: Int,
    activeFrameIndex: Int,
    rangeStart: Int,
    rangeEnd: Int,
    onSeekFrame: (Int) -> Unit,
    onSetRange: (start: Int, end: Int) -> Unit,
) {
    val activeFrameState = rememberUpdatedState(activeFrameIndex)
    val seekState = rememberUpdatedState(onSeekFrame)
    val rangeState = rememberUpdatedState(onSetRange)
    val playheadHitRadiusPx = with(LocalDensity.current) { 18.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val rangeColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(frameCount) {
                detectTapGestures { at ->
                    if (frameCount > 0) {
                        seekState.value(frameAtTimelinePosition(at.x, size.width.toFloat(), frameCount))
                    }
                }
            }
            .pointerInput(frameCount, playheadHitRadiusPx) {
                var dragAnchor = 0
                var lastFrame = -1
                var scrubbingPlayhead = false
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        if (frameCount > 0) {
                            val playheadX = timelinePositionForFrame(
                                activeFrameState.value,
                                size.width.toFloat(),
                                frameCount,
                            )
                            scrubbingPlayhead = abs(at.x - playheadX) <= playheadHitRadiusPx
                            dragAnchor = frameAtTimelinePosition(at.x, size.width.toFloat(), frameCount)
                            lastFrame = dragAnchor
                            if (scrubbingPlayhead) {
                                seekState.value(dragAnchor)
                            } else {
                                rangeState.value(dragAnchor, dragAnchor)
                                seekState.value(dragAnchor)
                            }
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        if (frameCount > 0) {
                            val frame = frameAtTimelinePosition(
                                change.position.x,
                                size.width.toFloat(),
                                frameCount,
                            )
                            if (frame != lastFrame) {
                                if (scrubbingPlayhead) {
                                    seekState.value(frame)
                                } else {
                                    val selected = normalizedPlaybackRange(dragAnchor, frame)
                                    rangeState.value(selected.first, selected.last)
                                    seekState.value(frame)
                                }
                                lastFrame = frame
                            }
                        }
                        change.consume()
                    },
                )
            },
    ) {
        if (frameCount <= 0) return@Canvas

        val centerY = size.height * 0.58f
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )

        val interval = if (frameCount > 1) size.width / (frameCount - 1) else size.width
        val startX = timelinePositionForFrame(rangeStart, size.width, frameCount)
        val endX = timelinePositionForFrame(rangeEnd, size.width, frameCount)
        val left = (min(startX, endX) - interval * 0.35f).coerceAtLeast(0f)
        val right = (max(startX, endX) + interval * 0.35f).coerceAtMost(size.width)
        drawRect(
            color = rangeColor.copy(alpha = 0.22f),
            topLeft = Offset(left, size.height * 0.18f),
            size = Size((right - left).coerceAtLeast(2f), size.height * 0.68f),
        )

        val tickStep = if (frameCount <= 24) 1 else (frameCount / 12).coerceAtLeast(1)
        for (frame in 0 until frameCount step tickStep) {
            val x = timelinePositionForFrame(frame, size.width, frameCount)
            drawLine(
                color = trackColor,
                start = Offset(x, centerY - 5f),
                end = Offset(x, centerY + 5f),
                strokeWidth = 1f,
            )
        }
        if ((frameCount - 1) % tickStep != 0) {
            val x = timelinePositionForFrame(frameCount - 1, size.width, frameCount)
            drawLine(trackColor, Offset(x, centerY - 5f), Offset(x, centerY + 5f), 1f)
        }

        val playheadX = timelinePositionForFrame(activeFrameIndex, size.width, frameCount)
        drawLine(
            color = playheadColor,
            start = Offset(playheadX, 2f),
            end = Offset(playheadX, size.height - 2f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = playheadColor,
            radius = 5f,
            center = Offset(playheadX, 7f),
        )
    }
}
