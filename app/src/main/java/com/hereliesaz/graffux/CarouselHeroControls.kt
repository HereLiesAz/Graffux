package com.hereliesaz.graffux

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The hero card's inline sliders, compact: each row is a one-line caption over a thin track.
 *
 * A drag that starts on a slider belongs to it: the slider consumes the horizontal drag, and the
 * carousel row's own `draggable` only moves on a gesture nobody below it consumed. This block sits
 * outside the card's click target (see `CarouselItem`), so a tap between sliders runs nothing.
 * "More" is [HeroMoreButton], which sits beside the details at the card's foot.
 */
@Suppress("FunctionNaming")
@Composable
internal fun HeroControls(
    entry: CarouselEntry,
    adjustments: List<HeroAdjustment>,
    content: CarouselContent,
    tint: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .testTag("carousel.hero.controls"),
    ) {
        adjustments.forEach { a ->
            Column(Modifier.fillMaxWidth().height(SLIDER_ROW_DP.dp)) {
                Text(
                    heroAdjustmentText(a),
                    color = tint,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = CAPTION_SP.sp,
                        lineHeight = CAPTION_SP.sp,
                    ),
                    maxLines = 1,
                )
                Slider(
                    value = a.value.coerceIn(a.range),
                    onValueChange = { content.onAdjust(entry, a.setter, heroSetterValue(a, it)) },
                    valueRange = a.range,
                    colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SLIDER_TRACK_DP.dp)
                        .testTag("carousel.hero.slider.${a.id}"),
                )
            }
        }
    }
}

/** The hero's "More": [entry]'s full adjustments. Compact, so it fits beside the details. */
@Suppress("FunctionNaming")
@Composable
internal fun HeroMoreButton(entry: CarouselEntry, onMore: (CarouselEntry) -> Unit, tint: Color) {
    TextButton(
        onClick = { onMore(entry) },
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier
            .height(MORE_ROW_DP.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
            .testTag("carousel.hero.more"),
    ) {
        Text("More", color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Heights of the compact rows above. */
internal const val SLIDER_ROW_DP = 28
internal const val MORE_ROW_DP = 28
private const val SLIDER_TRACK_DP = 16
private const val CAPTION_SP = 10
