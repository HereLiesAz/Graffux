package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp

/**
 * The "Relocate rotation point" prompt: it says plainly what to do and stays until a tap places the
 * point or the user cancels (Cancel, or Back), like the Draw-surface prompt. Announced politely.
 */
@Suppress("FunctionNaming")
@Composable
internal fun PivotPromptChip(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onCancel)
    val prompt = stringResource(R.string.pivot_prompt)
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(shape)
            .background(RailFill)
            .border(1.dp, colors.primary, shape)
            .testTag("pivot.prompt")
            .padding(start = 16.dp, top = 12.dp, end = 4.dp),
    ) {
        Text(
            prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier
                .padding(end = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = prompt
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.End)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 14.dp)
                .testTag("pivot.prompt.cancel"),
        ) {
            Text(
                stringResource(R.string.pivot_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
    }
}
