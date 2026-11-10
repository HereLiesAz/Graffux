package com.hereliesaz.graffux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.GraffuxIcons

/**
 * The sheet's stages as [SheetStages], from one place: MainActivity and the tests build them with this,
 * so what the controls offer cannot drift from what the swipes do. [ui] reads the current state, [set]
 * writes it; the card sheets (not the design or photo sheets) have the grid stages, and the brush page has
 * no tab-list stage, so a step down from its carousel shuts the sheet.
 */
internal fun sheetStagesFor(ui: () -> CarouselUi, set: (CarouselUi) -> Unit, designMode: Boolean): SheetStages {
    fun gridAllowed() = !designMode && !ui().photo
    fun brushPage() = gridAllowed() && carouselContextOf(ui().category) == CarouselContext.BRUSHES
    // The photo sheet opens on its tab list with no tab chosen; its body is blank until one is, so there is
    // nothing to step up to yet (picking a set on the wheel is how it gets one).
    fun somethingAbove() = !ui().photo || ui().tab != null
    fun canUp() = somethingAbove() && ui().steppedUp(gridAllowed = gridAllowed()) != ui()
    return SheetStages(
        onStepUp = { if (canUp()) set(ui().steppedUp(gridAllowed = gridAllowed())) },
        onStepDown = { ui().steppedDown(tabListStage = !brushPage())?.let(set) != null },
        canStepUp = { canUp() },
        canStepDown = { ui().steppedDown(tabListStage = !brushPage()) != null },
        showsSetWheel = { !ui().showingCarousel && !brushPage() },
    )
}

/**
 * The stage controls, laid over the top corners of the sheet's content (they take no height of their own,
 * so the open sheet is exactly as tall as before): 48dp "Show less" at the start (it reads "Close sheet" at
 * the lowest stage) and 48dp "Show more" at the end (drawn away, untappable and unannounced at the top
 * stage). They are the one accessibility path for the steps (they are focusable buttons); there are no
 * duplicate actions on the sheet.
 */
@Suppress("FunctionNaming")
@Composable
internal fun SheetStageControls(stages: SheetStages, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val canDown = stages.canStepDown()
    val canUp = stages.canStepUp()
    val downLabel = stringResource(if (canDown) R.string.sheet_show_less else R.string.sheet_close)
    val upLabel = stringResource(R.string.sheet_show_more)
    val wheelOnRight = LocalSetWheelOnRight.current
    BoxWithConstraints(modifier.testTag("carousel.stageBar")) {
        // With the set wheel showing (the tab-list stage) it fills the corner away from the rail; the controls
        // go to the free corner beside it, mirrored for a left-handed layout, and when that corner has room
        // for only one control it is the one that closes.
        val wheelSide = stages.showsSetWheel()
        val free = maxWidth - minOf(maxWidth - PickerGap * 2, WheelMaxWidth) - PickerGap * 2
        val room = if (wheelSide) (free / StageButtonSize).toInt() else 2
        val atStart = if (wheelSide) wheelOnRight else true
        val downAlign = if (atStart) Alignment.TopStart else Alignment.TopEnd
        val upAlign = if (wheelSide) downAlign else Alignment.TopEnd
        if (room >= 1) {
            StageButton(
                icon = GraffuxIcons.ChevronDown,
                label = downLabel,
                tag = "carousel.stage.down",
                enabled = true,
                modifier = Modifier.align(downAlign),
            ) { if (!stages.onStepDown()) onClose() }
        }
        if (room >= 2) {
            StageButton(
                icon = GraffuxIcons.ChevronUp,
                label = upLabel,
                tag = "carousel.stage.up",
                enabled = canUp,
                modifier = Modifier
                    .align(upAlign)
                    // Beside the other control, toward the middle, when both share one corner.
                    .padding(
                        start = if (wheelSide && atStart) StageButtonSize else 0.dp,
                        end = if (wheelSide && !atStart) StageButtonSize else 0.dp,
                    ),
            ) { stages.onStepUp() }
        }
    }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun StageButton(
    icon: Int,
    label: String,
    tag: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(StageButtonSize)
            // The tag goes before the clearing modifier: semantics after it (inside it) are dropped.
            .testTag(tag)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                        .semantics { contentDescription = label }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private val StageButtonSize = 48.dp

/** The strip along the top of a grid stage that the stage controls own: nothing interactive is laid in it. */
internal val StageControlClearance = StageButtonSize
