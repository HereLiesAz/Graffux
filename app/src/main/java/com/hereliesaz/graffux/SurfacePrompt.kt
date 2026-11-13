@file:Suppress("MagicNumber", "MaxLineLength", "FunctionNaming", "MatchingDeclarationName", "LongParameterList")

package com.hereliesaz.graffux

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.hereliesaz.graffitixr.common.model.ReliefBuildStage
import com.hereliesaz.graffitixr.common.model.ReliefBuildState
import com.hereliesaz.graffitixr.common.model.SurfaceDrawNotice
import com.hereliesaz.graffitixr.common.model.surfaceDrawArmed
import com.hereliesaz.graffitixr.common.scene.ReliefSurface
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import com.hereliesaz.graffitixr.design.theme.Amber
import com.hereliesaz.graffitixr.design.theme.ReliefStrings
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
        SurfaceDrawNotice.HAS_ART -> s.hasArt
        SurfaceDrawNotice.NOT_READABLE -> s.notReadable
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
    /** While armed, on a photo layer: build the surface from the image's depth instead of drawing it. */
    onBuildFromImage: (() -> Unit)? = null,
    /** While armed for a group's first (front) surface: skip drawing and use the default flat sheet. */
    onSkip: (() -> Unit)? = null,
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
        if (armed && onBuildFromImage != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onBuildFromImage)
                    .padding(end = 14.dp)
                    .testTag("surface.prompt.buildRelief"),
            ) { Text(rememberAppStrings().relief.rail, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
        }
        Row(Modifier.align(Alignment.End)) {
            if (armed && onSkip != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClick = onSkip)
                        .padding(horizontal = 14.dp)
                        .testTag("surface.prompt.skip"),
                ) { Text(strings.skip, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 14.dp)
                    .testTag("surface.prompt.cancel"),
            ) {
                Text(if (armed) strings.cancel else strings.ok, style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}

/**
 * The popups: the top-of-canvas notices in one vertical layout: the Draw-surface chip, then the app's messages
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
    /** "Build surface from image" in progress, and the active layer's relief; see [ReliefChip]. */
    reliefBuild: ReliefBuildState? = null,
    relief: ReliefSurface? = null,
    reliefActions: ReliefChipActions? = null,
    onBuildFromImage: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    /** "Relocate rotation point" is armed: its prompt rides in the same stack, above the others. */
    pivotArmed: Boolean = false,
    onCancelPivot: () -> Unit = {},
) {
    // Every popup sits at the top of the screen, under the status bar and any display cutout and between the
    // rails (the caller pads the sides by the rail width). PopupPolicy still decides how long one stays: an
    // IMPORTANT one (the armed instruction, the "set" decision, a warning) waits for the user, a TRANSIENT one
    // leaves in proportion to its text. The cards draw no pointer input of their own beyond their buttons, so a
    // stroke still reaches the canvas around them.
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Column(
            Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            if (pivotArmed) PivotPromptChip(onCancelPivot)
            SurfacePromptChip(armed, notice, onCancel, view = view, views = views, onDrawView = onDrawView, onBuildFromImage = onBuildFromImage, onSkip = onSkip)
            if (reliefActions != null) ReliefChip(reliefBuild, relief, reliefActions)
            MessageHost(alerts, okLabel = okLabel, countLabel = countLabel)
        }
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

/** What the relief chip can ask for. */
internal class ReliefChipActions(
    val onCancel: () -> Unit,
    /** Live strength, a fraction of the photo's longer side. */
    val onStrength: (Float) -> Unit,
    val onStrengthDone: () -> Unit,
    val onInvert: () -> Unit,
)

/** The relief chip's words: building progress (with Cancel), or the finished relief's hint. */
internal data class ReliefChipContent(val title: String, val text: String, val building: Boolean)

/** The chip's words, or null when it has nothing to say (no build under way, no relief active). */
internal fun reliefChipContent(build: ReliefBuildState?, relief: ReliefSurface?, s: ReliefStrings): ReliefChipContent? = when {
    build != null -> ReliefChipContent(
        s.title,
        when (build.stage) {
            ReliefBuildStage.CHECKING -> s.checking
            ReliefBuildStage.ESTIMATING -> s.estimating
            ReliefBuildStage.BUILDING -> s.building
        },
        building = true,
    )
    relief != null -> ReliefChipContent(s.readyTitle, s.readyHint, building = false)
    else -> null
}

/**
 * The "Build surface from image" chip, in the setup chip's chrome. While the depth model runs it says which step
 * it is on and offers Cancel (the UI never blocks); on an active relief it carries the live strength slider and the
 * near/far swap. Each control is at least 48dp and named for TalkBack.
 */
@Suppress("LongMethod")
@Composable
internal fun ReliefChip(build: ReliefBuildState?, relief: ReliefSurface?, actions: ReliefChipActions, modifier: Modifier = Modifier) {
    val strings = rememberAppStrings().relief
    val content = reliefChipContent(build, relief, strings) ?: return
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    BackHandler(enabled = content.building, onBack = actions.onCancel)
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(shape)
            .background(RailFill)
            .border(1.dp, colors.primary, shape)
            .testTag("relief.chip")
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
    ) {
        Column(
            Modifier.clearAndSetSemantics {
                contentDescription = "${content.title}. ${content.text}"
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(content.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.testTag("relief.chip.title"))
            Text(content.text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.testTag("relief.chip.text"))
        }
        if (content.building) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.End)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = actions.onCancel)
                    .padding(horizontal = 14.dp)
                    .testTag("relief.chip.cancel"),
            ) { Text(strings.cancel, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
        } else if (relief != null) {
            val label = strings.strength
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface, modifier = Modifier.padding(top = 8.dp))
            Slider(
                value = relief.strength.coerceIn(0f, ReliefSurface.MAX_STRENGTH),
                onValueChange = actions.onStrength,
                onValueChangeFinished = actions.onStrengthDone,
                valueRange = 0f..ReliefSurface.MAX_STRENGTH,
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = label }
                    .testTag("relief.chip.strength"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text(strings.invert, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = relief.inverted,
                    onCheckedChange = { actions.onInvert() },
                    modifier = Modifier.semantics { contentDescription = strings.invert }.testTag("relief.chip.invert"),
                )
            }
        }
    }
}

/** The active layer's relief, when its surface is one (the chip's strength and swap act on it). */
internal fun activeRelief(state: com.hereliesaz.graffitixr.common.model.EditorUiState): ReliefSurface? =
    com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(state.layers, state.activeLayerId)?.group?.surface?.relief

/** The relief chip's controls on the real view-model. */
internal fun reliefChipActions(
    vm: com.hereliesaz.graffitixr.feature.editor.EditorViewModel,
    state: com.hereliesaz.graffitixr.common.model.EditorUiState,
): ReliefChipActions {
    val group = com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(state.layers, state.activeLayerId)
        ?.group?.takeIf { it.surface?.relief != null }?.id
    return ReliefChipActions(
        onCancel = { vm.onCancelReliefBuild() },
        onStrength = { f -> group?.let { vm.onReliefStrength(it, f) } },
        onStrengthDone = { vm.onReliefTuneDone() },
        onInvert = { group?.let { vm.onReliefInvert(it) } },
    )
}

/** Whether the active layer is a photo that "Build surface from image" can start on (the chip offers it while armed). */
internal fun canBuildRelief(state: com.hereliesaz.graffitixr.common.model.EditorUiState): Boolean {
    val layer = state.layers.firstOrNull { it.id == state.activeLayerId } ?: return false
    return layer.type == com.hereliesaz.graffitixr.common.model.LayerType.RASTER && layer.bitmap != null &&
        layer.surfaceViewOf == null && com.hereliesaz.graffitixr.common.scene.SurfaceMath.ownerOf(state.layers, layer.id) == null
}
