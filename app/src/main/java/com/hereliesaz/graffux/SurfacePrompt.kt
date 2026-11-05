@file:Suppress("MagicNumber", "MaxLineLength", "FunctionNaming", "MatchingDeclarationName", "LongParameterList")

package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.SurfaceDrawNotice
import com.hereliesaz.graffitixr.design.theme.Amber
import com.hereliesaz.graffitixr.design.theme.SurfaceStrings
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings

/** What the chip shows: the plain prompt while armed (with [notice] below it), or only the [notice]. */
internal data class SurfacePromptContent(val title: String?, val text: String, val warning: Boolean, val done: Boolean)

/** The chip's words for the state, or null when it has nothing to say. */
internal fun surfacePromptContent(armed: Boolean, notice: SurfaceDrawNotice?, s: SurfaceStrings): SurfacePromptContent? {
    val noticeText = when (notice) {
        SurfaceDrawNotice.NO_LAYER -> s.noLayer
        SurfaceDrawNotice.LOCKED -> s.locked
        SurfaceDrawNotice.UNAVAILABLE -> s.unavailable
        SurfaceDrawNotice.TOO_SHORT -> s.tooShort
        SurfaceDrawNotice.DONE -> s.done
        null -> null
    }
    return when {
        armed -> SurfacePromptContent(s.title, noticeText ?: s.body, warning = noticeText != null, done = false)
        noticeText != null -> SurfacePromptContent(null, noticeText, warning = notice != SurfaceDrawNotice.DONE, done = notice == SurfaceDrawNotice.DONE)
        else -> null
    }
}

/**
 * The "Draw surface" prompt. Armed it says plainly what to do, and stays until a line is captured or
 * the user cancels (the Cancel button, the Back key, or the 3D slot): no timeout. Unarmed it carries
 * why the 3D slot could not arm, or that the surface is set, with OK. It is the app's message-card
 * chrome in its own composable, because a toast leaves by itself and this must not. A refusal wears
 * the warning edge and says so in words, so colour is never the only cue.
 */
@Composable
internal fun SurfacePromptChip(
    armed: Boolean,
    notice: SurfaceDrawNotice?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberAppStrings().surface
    val content = surfacePromptContent(armed, notice, strings) ?: return
    // Armed, Back is Cancel; this is composed after the screen's own handlers, so it is asked first.
    BackHandler(enabled = armed, onBack = onCancel)
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val spoken = listOfNotNull(content.title, content.text).joinToString(". ")
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(shape)
            .background(RailFill)
            .border(if (content.warning) 2.dp else 1.dp, if (content.warning) Amber else colors.primary, shape)
            .testTag("surface.prompt")
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 0.dp),
    ) {
        // The words take the full width between the rails; the button sits under them.
        Column(
            Modifier
                .padding(end = 12.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            content.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.testTag("surface.prompt.title"))
            }
            Text(content.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.testTag("surface.prompt.text"))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.End)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 14.dp)
                .testTag("surface.prompt.cancel"),
        ) {
            Text(if (armed) strings.cancel else strings.ok, style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

/**
 * The top-of-canvas notices in one vertical layout: the Draw-surface chip, then the app's messages
 * under it. Sharing a column lets the chip's real height (large font scales wrap it further) push
 * the messages down, where a fixed offset would let them overlap.
 */
@Composable
internal fun NoticeStack(
    armed: Boolean,
    notice: SurfaceDrawNotice?,
    onCancel: () -> Unit,
    alerts: MessageController,
    okLabel: String,
    countLabel: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        SurfacePromptChip(armed, notice, onCancel)
        MessageHost(alerts, okLabel = okLabel, countLabel = countLabel)
    }
}
