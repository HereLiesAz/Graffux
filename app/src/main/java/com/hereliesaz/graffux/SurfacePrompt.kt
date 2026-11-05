@file:Suppress("MagicNumber", "MaxLineLength", "FunctionNaming", "MatchingDeclarationName", "LongParameterList")

package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import com.hereliesaz.graffitixr.design.theme.Amber
import com.hereliesaz.graffitixr.design.theme.SurfaceStrings
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings

/** What the chip shows: the plain prompt while armed (with [notice] below it), or only the [notice]. */
internal data class SurfacePromptContent(
    val title: String?,
    val text: String,
    val warning: Boolean,
    val done: Boolean,
    /** Which of the three views are drawn, in words; null when the chip is not tracking views. */
    val status: String? = null,
)

/** Which views of the group's surface have been drawn (the chip's done markers). */
internal data class SurfaceViewsStatus(val front: Boolean, val side: Boolean, val top: Boolean) {
    fun of(view: SurfaceView) = when (view) {
        SurfaceView.FRONT -> front
        SurfaceView.SIDE -> side
        SurfaceView.TOP -> top
    }

    val all: Boolean get() = front && side && top
}

/** The chip's words for the state, or null when it has nothing to say. */
@Suppress("CyclomaticComplexMethod")
internal fun surfacePromptContent(
    armed: Boolean,
    notice: SurfaceDrawNotice?,
    s: SurfaceStrings,
    view: SurfaceView = SurfaceView.FRONT,
    views: SurfaceViewsStatus? = null,
): SurfacePromptContent? {
    val noticeText = when (notice) {
        SurfaceDrawNotice.NO_LAYER -> s.noLayer
        SurfaceDrawNotice.LOCKED -> s.locked
        SurfaceDrawNotice.UNAVAILABLE -> s.unavailable
        SurfaceDrawNotice.TOO_SHORT -> s.tooShort
        SurfaceDrawNotice.DONE -> s.done
        SurfaceDrawNotice.VIEW_DONE -> s.viewDone
        null -> null
    }
    val finished = notice == SurfaceDrawNotice.DONE || notice == SurfaceDrawNotice.VIEW_DONE
    fun word(v: SurfaceView) = if (views?.of(v) == true) s.viewIsDone else s.viewNotDrawn
    val status = views?.let {
        val line = s.viewsStatus.format(word(SurfaceView.FRONT), word(SurfaceView.SIDE), word(SurfaceView.TOP))
        if (!armed && finished && !it.all) "$line ${s.viewsHint}" else line
    }
    val title = when (view) {
        SurfaceView.FRONT -> s.title
        SurfaceView.SIDE -> s.titleSide
        SurfaceView.TOP -> s.titleTop
    }
    val body = when (view) {
        SurfaceView.FRONT -> s.body
        SurfaceView.SIDE -> s.bodySide
        SurfaceView.TOP -> s.bodyTop
    }
    return when {
        armed -> SurfacePromptContent(title, noticeText ?: body, warning = noticeText != null, done = false, status = status)
        noticeText != null -> SurfacePromptContent(null, noticeText, warning = !finished, done = finished, status = if (finished) status else null)
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
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun SurfacePromptChip(
    armed: Boolean,
    notice: SurfaceDrawNotice?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    view: SurfaceView = SurfaceView.FRONT,
    views: SurfaceViewsStatus? = null,
    /** Starts drawing another view of the group; offered with the "set" message when given. */
    onDrawView: ((SurfaceView) -> Unit)? = null,
) {
    val strings = rememberAppStrings().surface
    val content = surfacePromptContent(armed, notice, strings, view, views) ?: return
    // Armed, Back is Cancel; this is composed after the screen's own handlers, so it is asked first.
    BackHandler(enabled = armed, onBack = onCancel)
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val spoken = listOfNotNull(content.title, content.text, content.status).joinToString(". ")
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
            content.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, modifier = Modifier.padding(top = 4.dp).testTag("surface.prompt.views"))
            }
        }
        if (content.done && views != null && onDrawView != null) {
            Row {
                for (v in listOf(SurfaceView.SIDE, SurfaceView.TOP)) {
                    val label = when (v) {
                        SurfaceView.SIDE -> if (views.side) strings.redrawSide else strings.drawSide
                        else -> if (views.top) strings.redrawTop else strings.drawTop
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable(role = Role.Button) { onDrawView(v) }
                            .padding(end = 14.dp)
                            .testTag("surface.prompt.draw.${v.name.lowercase()}"),
                    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
                }
            }
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
    view: SurfaceView = SurfaceView.FRONT,
    views: SurfaceViewsStatus? = null,
    onDrawView: ((SurfaceView) -> Unit)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        SurfacePromptChip(armed, notice, onCancel, view = view, views = views, onDrawView = onDrawView)
        MessageHost(alerts, okLabel = okLabel, countLabel = countLabel)
    }
}

/**
 * The done markers for the group the chip is about: the armed group, else the active layer's surface
 * group. Null when there is none (the chip then shows no view line).
 */
internal fun surfaceViewsStatus(state: com.hereliesaz.graffitixr.common.model.EditorUiState): SurfaceViewsStatus? {
    val group = state.layers.firstOrNull { it.id == state.surfaceDrawGroupId }
        ?: com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(state.layers, state.activeLayerId)?.group
    val surface = group?.surface ?: return null
    return SurfaceViewsStatus(
        front = surface.isValid,
        side = surface.side?.isValid == true,
        top = surface.top?.isValid == true,
    )
}
