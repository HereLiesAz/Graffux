package com.hereliesaz.graffux

import androidx.compose.foundation.layout.Column
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

/**
 * The hero card's inline sliders and its "More" button, above the card's tip and name.
 *
 * A drag that starts on a slider belongs to it: the slider consumes the horizontal drag, and the
 * carousel row's own `draggable` only moves on a gesture nobody below it consumed. This block sits
 * outside the card's click target (see `CarouselItem`), so a tap between sliders runs nothing.
 */
@Suppress("FunctionNaming")
@Composable
internal fun HeroControls(
    entry: CarouselEntry,
    adjustments: List<HeroAdjustment>,
    content: CarouselContent,
    tint: Color,
) {
    val onMore = content.onMore?.takeIf { heroHasMore(entry) }
    if (adjustments.isEmpty() && onMore == null) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .testTag("carousel.hero.controls"),
    ) {
        adjustments.forEach { a ->
            Column(Modifier.fillMaxWidth().height(SLIDER_ROW_DP.dp)) {
                Text(heroAdjustmentText(a), color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Slider(
                    value = a.value.coerceIn(a.range),
                    onValueChange = { content.onAdjust(a.setter, heroSetterValue(a, it)) },
                    valueRange = a.range,
                    colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SLIDER_TRACK_DP.dp)
                        .testTag("carousel.hero.slider.${a.id}"),
                )
            }
        }
        if (onMore != null) {
            TextButton(
                onClick = { onMore(entry) },
                modifier = Modifier.height(MORE_ROW_DP.dp).testTag("carousel.hero.more"),
            ) {
                Text("More", color = tint, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Heights of the rows above; the strip sizes the hero card from the same numbers. */
internal const val SLIDER_ROW_DP = 44
internal const val MORE_ROW_DP = 32
private const val SLIDER_TRACK_DP = 28
