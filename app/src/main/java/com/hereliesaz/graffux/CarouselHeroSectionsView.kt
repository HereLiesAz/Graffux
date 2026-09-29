package com.hereliesaz.graffux

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The expanded card's grouped settings ([heroSections]): a small header per group, then its
 * segmented choices, compact sliders ([HeroControls]) and switches. Tags are
 * `carousel.expanded.{section,choice,slider,toggle}.<id>`.
 */
@Suppress("FunctionNaming")
@Composable
internal fun HeroSectionsView(
    entry: CarouselEntry,
    sections: List<HeroSection>,
    content: CarouselContent,
    tint: Color,
) {
    Column(Modifier.fillMaxWidth()) {
        sections.forEach { section ->
            Text(
                section.title.uppercase(),
                color = tint.copy(alpha = DETAIL_ALPHA),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = HEADER_SP.sp, letterSpacing = 1.sp),
                modifier = Modifier
                    .padding(start = 8.dp, top = 8.dp, bottom = 2.dp)
                    .testTag("carousel.expanded.section.${section.title}"),
            )
            section.choices.forEach { HeroChoiceRow(entry, it, content, tint) }
            if (section.sliders.isNotEmpty()) {
                HeroControls(entry, section.sliders, content, tint, tagPrefix = "carousel.expanded")
            }
            section.toggles.forEach { HeroToggleRow(entry, it, content, tint) }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun HeroChoiceRow(entry: CarouselEntry, choice: HeroChoice, content: CarouselContent, tint: Color) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .height(CHOICE_DP.dp)
            .testTag("carousel.expanded.choice.${choice.id}"),
    ) {
        choice.options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == choice.selected,
                onClick = { content.onChoose(entry, choice.setter, index) },
                shape = SegmentedButtonDefaults.itemShape(index, choice.options.size),
                contentPadding = PaddingValues(horizontal = 2.dp),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = tint,
                    activeContentColor = MaterialTheme.colorScheme.surface.takeIf { tint != it }
                        ?: MaterialTheme.colorScheme.onSurface,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = tint,
                    activeBorderColor = tint,
                    inactiveBorderColor = tint.copy(alpha = DETAIL_ALPHA),
                ),
                modifier = Modifier.testTag("carousel.expanded.choice.${choice.id}.$index"),
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = OPTION_SP.sp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun HeroToggleRow(entry: CarouselEntry, toggle: HeroToggle, content: CarouselContent, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(TOGGLE_DP.dp)
            .toggleable(toggle.checked, role = Role.Switch) { content.onToggle(entry, toggle.setter, it) }
            .padding(horizontal = 8.dp)
            .testTag("carousel.expanded.toggle.${toggle.id}"),
    ) {
        Text(
            toggle.label,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = toggle.checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = tint),
        )
    }
}

private const val HEADER_SP = 10
private const val OPTION_SP = 10
private const val CHOICE_DP = 32
private const val TOGGLE_DP = 36
